// Copyright 2012 Midokura Inc.

package org.midonet.midolman.simulation

import scala.Some
import collection.mutable
import collection.JavaConversions._
import java.util.UUID

import akka.actor.{ActorContext, ActorSystem}
import akka.dispatch.{ExecutionContext, Future, Promise}

import org.midonet.cache.Cache
import org.midonet.cluster.client._
import org.midonet.midolman.DeduplicationActor
import org.midonet.midolman.PacketWorkflow.{SendPacket,
                                            AddVirtualWildcardFlow,
                                            NoOp,
                                            SimulationResult}
import org.midonet.midolman.datapath.{FlowActionOutputToVrnPort,
                                      FlowActionOutputToVrnPortSet}
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.rules.RuleResult.{Action => RuleAction}
import org.midonet.midolman.state.ConditionSet
import org.midonet.midolman.topology._
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology.VirtualTopologyActor.RouterRequest
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.VirtualTopologyActor.BridgeRequest
import org.midonet.midolman.topology.VirtualTopologyActor.ChainRequest
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.topology.VirtualTopologyActor.VlanBridgeRequest
import org.midonet.odp.flows._
import org.midonet.packets.{Ethernet, ICMP, IPv4, IPv4Addr, IPv6Addr, TCP, UDP}
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}


object Coordinator {
    trait Action

    // ErrorDropAction is used to signal that the Drop is being requested
    // because of an error, not because the virtual topology justifies it.
    // The resulting Drop rule may be temporary to allow retrying.
    case class ErrorDropAction() extends Action
    case class DropAction() extends Action
    // NotIPv4Action implies a DROP flow. However, it differs from DropAction
    // in that the installed flow match can have all fields >L2 wildcarded.
    // TODO(pino): make the installed flow computation smarter so that it
    // TODO:       wildcards any field that wasn't used by the simulation. Then
    // TODO:       remove NotIPv4Action
    case class NotIPv4Action() extends Action
    case class ConsumedAction() extends Action
    trait ForwardAction extends Action
    case class ToPortAction(outPort: UUID) extends ForwardAction
    case class ToPortSetAction(portSetID: UUID) extends ForwardAction
    case class DoFlowAction[A <: FlowAction[A]](action: A) extends Action
    // This action is used when one simulation has to return N forward actions
    // A good example is when a bridge that has a vlan id set receives a
    // broadcast from the virtual network. It will output it to all its
    // materialized ports and to the logical port that connects it to the VAB
    case class ForkAction(actions: List[Future[Action]]) extends ForwardAction

    trait Device {

        /**
         * Process a packet described by the given match object. Note that the
         * Ethernet packet is the one originally ingressed the virtual network
         * - it does not reflect the changes made by other devices' handling of
         * the packet (whereas the match object does).
         *
         * @param pktContext The context for the simulation of this packet's
         * traversal of the virtual network. Use the context to subscribe
         * for notifications on the removal of any resulting flows, or to tag
         * any resulting flows for indexing.
         * @return An instance of Action that reflects what the device would do
         * after handling this packet (e.g. drop it, consume it, forward it).
         */
        def process(pktContext: PacketContext)
                   (implicit ec: ExecutionContext,
                    actorSystem: ActorSystem): Future[Action]
    }
}

/**
 * Coordinator object to simulate one packet traversing the virtual network.
 *
 * @param origMatch
 * @param origEthernetPkt
 * @param cookie
 * @param generatedPacketEgressPort Only used if this packet was generated
 *                                  by a virtual device. It's the ID of
 *                                  the port via which the packet
 *                                  egresses the device.
 * @param expiry The time (as returned by currentTime) at which to expire
 *               this simulation.
 * @param connectionCache The Cache to use for connection tracking.
 * @param ec
 * @param actorSystem
 */
class Coordinator(var origMatch: WildcardMatch,
                  val origEthernetPkt: Ethernet,
                  val cookie: Option[Int],
                  val generatedPacketEgressPort: Option[UUID],
                  val expiry: Long,
                  val connectionCache: Cache,
                  val parentCookie: Option[Int],
                  val tracedConditions: ConditionSet)
                 (implicit val ec: ExecutionContext,
                  val actorSystem: ActorSystem,
                  val actorContext: ActorContext) {
    import Coordinator._

    val log = LoggerFactory.getSimulationAwareLog(this.getClass)(actorSystem.eventStream)
    private val TEMPORARY_DROP_MILLIS = 5 * 1000
    private val IDLE_EXPIRATION_MILLIS = 60 * 1000
    private val RETURN_FLOW_EXPIRATION_MILLIS = 60 * 1000
    private val MAX_DEVICES_TRAVERSED = 12

    // Used to detect loops: devices simulated (with duplicates).
    private var numDevicesSimulated = 0
    private val devicesSimulated = mutable.Map[UUID, Int]()
    implicit private val pktContext = new PacketContext(cookie, origEthernetPkt,
         expiry, connectionCache, generatedPacketEgressPort.isDefined,
         parentCookie)
    pktContext.setMatch(origMatch)
    pktContext.setTraced(tracedConditions.matches(pktContext, origMatch, false))

    implicit def simulationActionToSuccessfulFuture(
        a: SimulationResult): Future[SimulationResult] = Promise.successful(a)


    private def dropFlow(temporary: Boolean, withTags: Boolean = false):
            SimulationResult = {
        // If the packet is from the datapath, install a temporary Drop flow.
        // Note: a flow with no actions drops matching packets.
        pktContext.freeze()
        pktContext.traceMessage(null, "Dropping flow")
        cookie match {
            case Some(_) =>
                val idleExp = if (temporary) 0 else IDLE_EXPIRATION_MILLIS
                val hardExp = if (temporary) TEMPORARY_DROP_MILLIS else 0
                val wflow = WildcardFlow(
                    wcmatch = origMatch,
                    hardExpirationMillis = hardExp,
                    idleExpirationMillis = idleExp)

                AddVirtualWildcardFlow(wflow,
                        pktContext.getFlowRemovedCallbacks,
                        if (withTags) pktContext.getFlowTags else Set.empty)

            case None => // Internally-generated packet. Do nothing.
                pktContext.getFlowRemovedCallbacks foreach { cb => cb.call() }
                NoOp()
        }
    }

    /**
     * Simulate the packet moving through the virtual topology. The packet
     * begins its journey through the virtual topology in one of these ways:
     * 1) it ingresses an exterior port of a virtual device (in which case the
     * packet arrives via the datapath switch from an entity outside the
     * virtual topology).
     * 2) it egresses an interior port of a virtual device (in which case the
     * packet was generated by that virtual device).
     *
     * In case 1, the match object for the packet was computed by the
     * FlowController and must contain an inPortID. If a wildcard flow is
     * eventually installed in the FlowController, the match will be a subset
     * of the match originally provided to the simulation. Note that in this
     * case the generatedPacketEgressPort argument should be null and will be
     * ignored.
     *
     * In case 2, the match object for the packet was computed by the Device
     * that emitted the packet and must not contain an inPortID. If the packet
     * is not dropped, it will eventually result in a packet being emitted
     * from one or more of the datapath ports. However, a flow is never
     * installed as a result of such a simulation. Note that in this case the
     * generatedPacketEgressPort argument must not be null.
     *
     * When the future returned by this method completes all the actions
     * resulting from the simulation (install flow and/or execute packet)
     * have been completed.
     */
    def simulate(): Future[SimulationResult] = {
        log.debug("Simulate a packet {}", origEthernetPkt)

        generatedPacketEgressPort match {
            case None => // This is a packet from the datapath
                origMatch.getInputPortUUID match {
                    case null =>
                        log.error(
                            "Coordinator cannot simulate a flow that " +
                            "NEITHER egressed a virtual device's interior " +
                            "port NOR ingressed a virtual device's exterior " +
                            "port. Match: %s; Packet: %s".format(
                                origMatch.toString, origEthernetPkt.toString))
                        dropFlow(temporary = true)

                    case id: UUID =>
                        dropFragmentedPackets(id) match {
                            case None =>
                                packetIngressesPort(id, getPortGroups = true)
                            case Some(future) =>
                                // packet dropped
                                future
                        }
                }

            case Some(egressID) =>
                origMatch.getInputPortUUID match {
                    case null =>
                        packetEgressesPort(egressID)

                    case id: UUID =>
                        log.error(
                            "Coordinator cannot simulate a flow that " +
                            "BOTH egressed a virtual device's interior " +
                            "port AND ingressed a virtual device's exterior " +
                            "port. Match: %s; Packet: %s".format(
                                origMatch.toString, origEthernetPkt.toString))
                        dropFlow(temporary = true)
                }
        }
    }

    private def dropFragmentedPackets(inPort: UUID): Option[Future[SimulationResult]] = {
        origMatch.getIpFragmentType match {
            case IPFragmentType.First =>
                origMatch.getEtherType.shortValue match {
                    case IPv4.ETHERTYPE =>
                        sendIpv4FragNeeded(inPort)
                        Some(dropFlow(temporary = true))

                    case ethertype =>
                        log.info("Dropping fragmented packet of unsupported " +
                                 "ethertype={}", ethertype)
                        Some(dropFlow(temporary = false))
                }
            case IPFragmentType.Later =>
                log.info("Dropping non-first fragment at simulation layer")
                val wMatch = new WildcardMatch().
                    setIpFragmentType(IPFragmentType.Later)
                val wFlow = WildcardFlow(wcmatch = wMatch)
                Some(AddVirtualWildcardFlow(wFlow, Set.empty, Set.empty))
            case _ =>
                None
        }
    }

    private def sendIpv4FragNeeded(inPort: UUID) {
        val origPkt: IPv4 = origEthernetPkt.getPayload match {
            case ip: IPv4 => ip
            case _ => null
        }

        if (origPkt == null)
            return

        /* Certain versions of Linux try to guess a lower MTU value if the MTU
         * suggested by the ICMP FRAG_NEEDED error is equal or bigger than the
         * size declared in the IP header found in the ICMP data. This happens
         * when it's the sender host who is fragmenting.
         *
         * In those cases, we can at least prevent the sender from lowering
         * the PMTU by increasing the length in the IP header.
         */
        val mtu = origPkt.getTotalLength match {
            case 0 => origPkt.serialize().length
            case nonZero => nonZero
        }
        origPkt.setTotalLength(mtu + 1)
        origPkt.setChecksum(0)

        val icmp = new ICMP()
        icmp.setFragNeeded(mtu, origPkt)
        val ip = new IPv4()
        ip.setPayload(icmp)
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        ip.setSourceAddress(origPkt.getDestinationAddress)
        ip.setDestinationAddress(origPkt.getSourceAddress)
        val eth = new Ethernet()
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(ip)
        eth.setSourceMACAddress(origEthernetPkt.getDestinationMACAddress)
        eth.setDestinationMACAddress(origEthernetPkt.getSourceMACAddress)

        DeduplicationActor.getRef() ! EmitGeneratedPacket(inPort, eth, cookie)
    }


    private def packetIngressesDevice(port: Port[_]): Future[SimulationResult] = {
        var deviceFuture: Future[Any] = null
        port match {
            case _: BridgePort[_] =>
                deviceFuture = expiringAsk(
                    BridgeRequest(port.deviceID, update = false), expiry)
            case _: RouterPort[_] =>
                deviceFuture = expiringAsk(
                    RouterRequest(port.deviceID, update = false), expiry)
            case _: VlanBridgePort[_] =>
                deviceFuture = expiringAsk(
                    VlanBridgeRequest(port.deviceID, update = false), expiry)
            case _ =>
                log.error("Port {} belongs to device {} which is neither a " +
                          "bridge, vlan-bridge, or router!", port, port.deviceID)
                return dropFlow(temporary = true)
        }
        deviceFuture map {Option(_)} fallbackTo { Promise.successful(None) } flatMap {
            case None =>
                log.warning("Failed to get device: {}", port.deviceID)
                pktContext.traceMessage(port.deviceID, "Couldn't get device")
                dropFlow(temporary = true)
            case Some(deviceReply) =>
                if (!deviceReply.isInstanceOf[Device]) {
                    log.error("VirtualTopologyManager didn't return a device!")
                    pktContext.traceMessage(port.deviceID, "ERROR Not a device")
                    dropFlow(temporary = true)
                } else {
                    numDevicesSimulated += 1
                    devicesSimulated.put(port.deviceID, numDevicesSimulated)
                    log.debug("Simulating packet with match {}, device {}",
                        pktContext.getMatch, port.deviceID)
                    pktContext.traceMessage(port.deviceID, "Entering device")
                    handleActionFuture(deviceReply.asInstanceOf[Device].process(
                        pktContext))
                }
        }
    }

    private def mergeSimulationResults(firstAction: SimulationResult,
                                       secondAction: SimulationResult,
                                       firstOrigMatch: WildcardMatch) : SimulationResult = {
        if (!firstAction.getClass.equals(secondAction.getClass)) {
            log.error("Matching actions of diffent types, {}, {}!", firstAction,
                secondAction)
            // we should restore original match
            origMatch = firstOrigMatch
            return dropFlow(temporary = true)
        }
        firstAction match {
            case p: SendPacket =>
                val actions = p.actions ++ secondAction.asInstanceOf[SendPacket].actions
                SendPacket(actions)

            case f: AddVirtualWildcardFlow =>
                val secondFlow = secondAction.asInstanceOf[AddVirtualWildcardFlow]
                val actions = f.flow.actions ++ secondFlow.flow.actions
                val hardExp =
                    if (f.flow.hardExpirationMillis >= secondFlow.flow.getHardExpirationMillis)
                        secondFlow.flow.getHardExpirationMillis
                    else f.flow.getHardExpirationMillis

                val idleExp =
                    if (f.flow.idleExpirationMillis >= secondFlow.flow.idleExpirationMillis)
                        secondFlow.flow.idleExpirationMillis
                    else f.flow.idleExpirationMillis

                val mergedFlow = WildcardFlow(wcmatch = f.flow.getMatch,
                                              actions = actions,
                                              hardExpirationMillis = hardExp,
                                              idleExpirationMillis = idleExp)
                //TODO(rossella) set the other fields Priority
                val callbacks = f.flowRemovalCallbacks ++ secondFlow.flowRemovalCallbacks
                val tags = f.tags ++ secondFlow.tags
                val res = AddVirtualWildcardFlow(mergedFlow, callbacks, tags)
                log.debug("Forked action merged results {}", res)
                res
            case a =>
                log.debug("Receive unrecognized action {}", a)
                origMatch = firstOrigMatch
                dropFlow(temporary = true)
        }
    }

    /**
     * Takes a Seq[Future[A]], evaluates them sequentially with the given
     * function and returns the results.
     */
    private def execSequential[A, B](l: Seq[Future[A]], handlr: A => Future[B]):
    Future[Seq[B]] = {
        def recurse(list: Seq[A], results: Seq[B]): Future[Seq[B]] = {
            list match {
                case Nil => Promise.successful(results)
                case head::tail => handlr(head) flatMap ( res =>
                    recurse(tail, results :+ res)
                )
            }
        }
        Future.sequence(l) flatMap ( elements => { recurse(elements, Nil) } )
    }

    private def handleActionFuture(actionF: Future[Action]): Future[SimulationResult] = {

        actionF recover {
            case e =>
                log.error(e, "Error instead of Action - {}", e)
                ErrorDropAction()
        } flatMap { case action =>
            log.info("Received action: {}", action)
            action match {

                case a: DoFlowAction[_] =>
                    a.action match {
                        case b: FlowActionPopVLAN =>
                            pktContext.unfreeze()
                            val flow = WildcardFlow(
                                wcmatch = origMatch,
                                actions = List(a.action))
                            val vlanId = pktContext.getMatch.getVlanIds.get(0)
                            pktContext.getMatch.removeVlanId(vlanId)
                            origMatch = pktContext.getMatch.clone()
                            pktContext.freeze()
                            AddVirtualWildcardFlow(flow,
                                pktContext.getFlowRemovedCallbacks,
                                pktContext.getFlowTags)
                        case b => NoOp()
                    }

                case f: ForkAction =>
                    val results = execSequential(f.actions,
                      (act: Coordinator.Action) => {
                          // Our handler for each action consists on evaluating
                          // the Coordinator action and pairing it with the
                          // original WMatch at that point in time
                          handleActionFuture(Promise.successful(act)) flatMap {
                              simRes => {
                                  val firstOrigMatch = origMatch.clone()
                                  origMatch = pktContext.getMatch.clone()
                                  pktContext.unfreeze()
                                  Promise.successful(simRes, firstOrigMatch)
                              }
                          }
                      })
                    // When ready, merge the results of the simulations. The
                    // resulting pair (SimulationResult, WildcardMatch) contains
                    // the merge action resulting from the other partial ones
                    results.map (results => results.reduceLeft(
                        (a, b) => (mergeSimulationResults(a._1, b._1, a._2), b._2)
                    )).map( r => r._1)

                case ToPortSetAction(portSetID) =>
                    pktContext.traceMessage(portSetID, "Flooded to port set")
                    emit(portSetID, isPortSet = true, null)

                case ToPortAction(outPortID) =>
                    pktContext.traceMessage(outPortID, "Forwarded to port")
                    packetEgressesPort(outPortID)

                case _: ConsumedAction =>
                    pktContext.traceMessage(null, "Consumed")
                    pktContext.freeze()
                    pktContext.getFlowRemovedCallbacks foreach {
                        cb => cb.call()
                    }
                    NoOp()

                case _: ErrorDropAction =>
                    pktContext.traceMessage(null, "Encountered error")
                    pktContext.freeze()
                    cookie match {
                        case None => // Do nothing.
                            pktContext.getFlowRemovedCallbacks foreach {
                                cb => cb.call()
                            }
                            NoOp()
                        case Some(_) =>
                            // Drop the flow temporarily
                            dropFlow(temporary = true)
                    }

                case _: DropAction =>
                    pktContext.traceMessage(null, "Dropping flow")
                    pktContext.freeze()
                    log.debug("Device returned DropAction for {}",
                        origMatch)
                    cookie match {
                        case None => // Do nothing.
                            pktContext.getFlowRemovedCallbacks foreach {
                                cb => cb.call()
                            }
                            NoOp()
                        case Some(_) =>
                            var temporary = false
                            temporary = pktContext.isConnTracked()
                            dropFlow(temporary, withTags = true)
                    }

                case _: NotIPv4Action =>
                    pktContext.traceMessage(null, "Unsupported protocol")
                    log.debug("Device returned NotIPv4Action for {}",
                        origMatch)
                    pktContext.freeze()
                    cookie match {
                        case None => // Do nothing.
                            pktContext.getFlowRemovedCallbacks foreach {
                                cb => cb.call()
                            }
                            NoOp()
                        case Some(_) =>
                            val notIPv4Match =
                                (new WildcardMatch()
                                    .setInputPortUUID(origMatch.getInputPortUUID)
                                    .setEthernetSource(origMatch.getEthernetSource)
                                    .setEthernetDestination(
                                        origMatch.getEthernetDestination)
                                    .setEtherType(origMatch.getEtherType))
                            AddVirtualWildcardFlow(
                                    WildcardFlow(wcmatch = notIPv4Match),
                                    pktContext.getFlowRemovedCallbacks,
                                    pktContext.getFlowTags)
                            // TODO(pino): Connection-tracking blob?
                    }


                case _ =>
                    pktContext.traceMessage(null,
                                            "ERROR Unexpected action returned")
                    log.error("Device returned unexpected action!")
                    dropFlow(temporary = true)
            } // end action match
        }
    }

    private def packetIngressesPort(portID: UUID, getPortGroups: Boolean):
            Future[SimulationResult] = {

        // Avoid loops - simulate at most X devices.
        if (numDevicesSimulated >= MAX_DEVICES_TRAVERSED) {
            return dropFlow(temporary = true)
        }

        // Get the RCU port object and start simulation.
        val portFuture = expiringAsk(PortRequest(portID, update = false), expiry)
        portFuture map {Option(_)} fallbackTo { Promise.successful(None) } flatMap {
            case None =>
                log.warning("Failed to get port: {}", portID)
                dropFlow(temporary = true)
            case Some(p) => p match {
                case port: Port[_] =>
                    if (getPortGroups &&
                        port.isInstanceOf[ExteriorPort[_]]) {
                        pktContext.setPortGroups(
                            port.asInstanceOf[ExteriorPort[_]].portGroups)
                    }

                    pktContext.setInputPort(port)
                    val future = applyPortFilter(port, port.inFilterID,
                        packetIngressesDevice _)
                    // add tag for flow invalidation
                    pktContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(portID))
                    future
                case _ =>
                    log.error("VirtualTopologyManager didn't return a port!")
                    dropFlow(temporary = true)
            }
        }
    }

    def applyPortFilter(port: Port[_], filterID: UUID,
                        thunk: (Port[_]) => Future[SimulationResult]):
                        Future[SimulationResult] = {
        if (filterID == null)
            return thunk(port)
        val chainFuture = expiringAsk(ChainRequest(filterID, update = false), expiry)
        chainFuture map {Option(_)} fallbackTo { Promise.successful(None) } flatMap {
            case None =>
                log.warning("Failed to get chain: {}", filterID)
                dropFlow(temporary = true)
            case Some(c) => c match {
                case chain: Chain =>
                    // add ChainID for flow invalidation
                    pktContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(filterID))
                    pktContext.addFlowTag(
                        FlowTagger.invalidateFlowsByDeviceFilter(port.id, filterID))

                    val result = Chain.apply(chain, pktContext,
                        pktContext.getMatch, port.id, true)
                    if (result.action == RuleAction.ACCEPT) {
                        thunk(port)
                    } else if (result.action == RuleAction.DROP ||
                        result.action == RuleAction.REJECT) {
                        dropFlow(temporary = false, withTags = true)
                    } else {
                        log.error("Port filter {} returned {}, not ACCEPT, " +
                            "DROP, or REJECT.", filterID, result.action)
                        dropFlow(temporary = true)
                    }
                case _ =>
                    log.error("VirtualTopologyActor didn't return a chain!")
                    dropFlow(temporary = true)
            }
        }
    }

    /**
     * Simulate the packet egressing a virtual port. This is NOT intended
     * for output-ing to PortSets.
     * @param portID
     */
    private def packetEgressesPort(portID: UUID): Future[SimulationResult] = {
        val portFuture =  expiringAsk(PortRequest(portID, update = false), expiry)
        portFuture map {Option(_)} fallbackTo { Promise.successful(None) } flatMap {
            case None =>
                log.warning("Failed to get port: {}", portID)
                dropFlow(temporary = true)
            case Some(reply) => reply match {
                case port: Port[_] =>
                    // add tag for flow invalidation
                    pktContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(portID))
                    pktContext.setOutputPort(port.id)
                    applyPortFilter(port, port.outFilterID, {
                        case _: ExteriorPort[_] =>
                            emit(portID, isPortSet = false, port)
                        case interiorPort: InteriorPort[_] =>
                            packetIngressesPort(interiorPort.peerID,
                                                getPortGroups = false)
                        case _ =>
                            log.error(
                                "Port {} neither interior nor exterior port",
                                port)
                            dropFlow(temporary = true)
                    })
                case _ =>
                    log.error("VirtualTopologyManager didn't return a port!")
                    dropFlow(temporary = true)
            }
        }
    }

    /**
     * Complete the simulation by emitting the packet from the specified
     * virtual port or PortSet.  If the packet was internally generated
     * this will do a SendPacket, otherwise it will do an AddWildcardFlow.
     * @param outputID The ID of the virtual port or PortSet from which the
     *                 packet must be emitted.
     * @param isPortSet Whether the packet is output to a port set.
     * @param port The port output to; unused if outputting to a port set.
     */
    private def emit(outputID: UUID, isPortSet: Boolean, port: Port[_]):
            SimulationResult = {
        val actions = actionsFromMatchDiff(origMatch, pktContext.getMatch)
        isPortSet match {
            case false =>
                log.debug("Emitting packet from vport {}", outputID)
                actions.append(new FlowActionOutputToVrnPort(outputID))
            case true =>
                log.debug("Emitting packet from port set {}", outputID)
                actions.append(new FlowActionOutputToVrnPortSet(outputID))
        }

        cookie match {
            case None =>
                log.debug("No cookie. SendPacket with actions {}", actions)
                pktContext.freeze()
                pktContext.getFlowRemovedCallbacks foreach { cb => cb.call() }
                SendPacket(actions.toList)
            case Some(_) =>
                log.debug("Cookie {}; Add a flow with actions {}",
                    cookie.get, actions)
                pktContext.freeze()
                // TODO(guillermo,pino) don't assume that portset id == bridge id
                if (pktContext.isConnTracked && pktContext.isForwardFlow) {
                    // Write the packet's data to the connectionCache.
                    installConnectionCacheEntry(outputID, pktContext.getMatch,
                            if (isPortSet) outputID else port.deviceID)
                }

                var idleExp = 0
                var hardExp = 0

                if (pktContext.isConnTracked) {
                    if (pktContext.isForwardFlow) {
                        // See #577: if fwd flow we expire the fwd bc. we want
                        // it to keep refreshing the cache key
                        hardExp = RETURN_FLOW_EXPIRATION_MILLIS / 2
                    } else {
                        // if ret flow, we need to simulate periodically to
                        // ensure that it's legit by checking that there is a
                        // matching fwd flow
                        hardExp = RETURN_FLOW_EXPIRATION_MILLIS
                    }
                } else {
                    idleExp = IDLE_EXPIRATION_MILLIS
                }
                val wFlow = WildcardFlow(
                        wcmatch = origMatch,
                        actions = actions.toList,
                        idleExpirationMillis = idleExp,
                        hardExpirationMillis = hardExp)

                AddVirtualWildcardFlow(wFlow,
                                       pktContext.getFlowRemovedCallbacks,
                                       pktContext.getFlowTags)
        }
    }

    private def installConnectionCacheEntry(outPortID: UUID,
                                            flowMatch: WildcardMatch,
                                            deviceID: UUID) {
        val key = PacketContext.connectionKey(
                        flowMatch.getNetworkDestinationIP(),
                        flowMatch.getTransportDestination(),
                        flowMatch.getNetworkSourceIP(),
                        flowMatch.getTransportSource(),
                        flowMatch.getNetworkProtocol(),
                        deviceID)
        log.debug("Installing conntrack entry: key:{}", key)
        connectionCache.set(key, "r")
    }

    /*
     * Compares two objects, which may be null, to determine if they should
     * cause flow actions.
     * The catch here is that if `modif` is null, the verdict is true regardless
     * because we don't create actions that set values to null.
     */
    private def matchObjectsSame(orig: Any, modif: Any): Boolean = {
        if (orig == null)
            modif == null
        else if (modif != null)
            orig.equals(modif)
        else
            true
    }

    private def actionsFromMatchDiff(orig: WildcardMatch, modif: WildcardMatch)
    : mutable.ListBuffer[FlowAction[_]] = {
        val actions = mutable.ListBuffer[FlowAction[_]]()
        if (!orig.getEthernetSource.equals(modif.getEthernetSource) ||
            !orig.getEthernetDestination.equals(modif.getEthernetDestination)) {
            actions.append(FlowActions.setKey(FlowKeys.ethernet(
                modif.getDataLayerSource, modif.getDataLayerDestination)))
        }
        if (!matchObjectsSame(orig.getNetworkSourceIP,
                              modif.getNetworkSourceIP) ||
            !matchObjectsSame(orig.getNetworkDestinationIP,
                              modif.getNetworkDestinationIP) ||
            !matchObjectsSame(orig.getNetworkTTL,
                              modif.getNetworkTTL)) {
            actions.append(FlowActions.setKey(
                modif.getNetworkSourceIP match {
                    case srcIP: IPv4Addr =>
                        FlowKeys.ipv4(srcIP,
                            modif.getNetworkDestinationIP.asInstanceOf[IPv4Addr],
                            modif.getNetworkProtocol)
                        //.setFrag(?)
                        .setTos(modif.getNetworkTypeOfService)
                        .setTtl(modif.getNetworkTTL)
                    case srcIP: IPv6Addr =>
                        FlowKeys.ipv6(srcIP,
                            modif.getNetworkDestinationIP.asInstanceOf[IPv6Addr],
                            modif.getNetworkProtocol)
                        //.setFrag(?)
                        .setHLimit(modif.getNetworkTTL)
                }
            ))
        }
        // Vlan tag
        if (!orig.getVlanIds.equals(modif.getVlanIds)) {
            val vlansToRemove = orig.getVlanIds.diff(modif.getVlanIds)
            val vlansToAdd = modif.getVlanIds.diff(orig.getVlanIds)
            log.debug("Vlan tags to pop {}, vlan tags to push {}",
                      vlansToRemove, vlansToAdd)

            for (vlan <- vlansToRemove) {
                actions.append(new FlowActionPopVLAN)
            }
            var count = vlansToAdd.size
            for (vlan <- vlansToAdd) {
                count -= 1
                val action: FlowActionPushVLAN = new FlowActionPushVLAN()
                // check if this is the last VLAN to push
                if (count == 0) {
                    action.setTagProtocolIdentifier(Ethernet.VLAN_TAGGED_FRAME)
                } else {
                    action.setTagControlIdentifier(Ethernet.PROVIDER_BRIDGING_TAG)
                }

                // vlan tag is the last 12 bits of this short, since we don't
                // care about the first 4 we just set it directly
                action.setTagControlIdentifier((vlan | 0x1000).toShort)
                actions.append(action)
            }
        }
        // ICMP errors
        if (!matchObjectsSame(orig.getIcmpData,
                              modif.getIcmpData)) {
            val icmpType = modif.getTransportSource()
            if (icmpType == ICMP.TYPE_PARAMETER_PROBLEM ||
                icmpType == ICMP.TYPE_UNREACH ||
                icmpType == ICMP.TYPE_TIME_EXCEEDED) {
                val actSetKey = FlowActions.setKey(null)
                actions.append(actSetKey)
                actSetKey.setFlowKey(FlowKeys.icmpError(
                    modif.getTransportSource,
                    modif.getTransportDestination,
                    modif.getIcmpData
                ))
            }
        }
        if (!matchObjectsSame(orig.getTransportSourceObject,
                              modif.getTransportSourceObject) ||
            !matchObjectsSame(orig.getTransportDestinationObject,
                              modif.getTransportDestinationObject)) {
            val actSetKey = FlowActions.setKey(null)
            actions.append(actSetKey)
            modif.getNetworkProtocol match {
                case TCP.PROTOCOL_NUMBER =>
                    actSetKey.setFlowKey(FlowKeys.tcp(
                        modif.getTransportSource,
                        modif.getTransportDestination))
                case UDP.PROTOCOL_NUMBER =>
                    actSetKey.setFlowKey(FlowKeys.udp(
                        modif.getTransportSource,
                        modif.getTransportDestination))
                case ICMP.PROTOCOL_NUMBER =>
                    // this case would only happen if icmp id in ECHO req/reply
                    // were translated, which is not the case, so leave alone
            }
        }
        actions
    }
} // end Coordinator class
