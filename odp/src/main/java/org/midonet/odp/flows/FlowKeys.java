/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.family.FlowFamily;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv6Addr;
import org.midonet.util.collection.WeakObjectPool;

/**
 * Builder class to allow easier building of FlowKey instances.
 */
public class FlowKeys {
    private static WeakObjectPool<FlowKey> FLOW_KEYS_POOL =
        new WeakObjectPool<>();

    public static <T extends FlowKey> T intern(T flowKey) {
        if (flowKey instanceof CachedFlowKey)
            return sharedReferenceOf(flowKey);
        else
            return flowKey;
    }

    public static <T extends FlowKey> T sharedReferenceOf(T flowKey) {
        @SuppressWarnings("unchecked")
        T shared = (T) FLOW_KEYS_POOL.sharedRef(flowKey);
        return shared;
    }

    public static FlowKeyInPort inPort(int portNumber) {
        return intern(new FlowKeyInPort(portNumber));
    }

    public static FlowKeyEthernet ethernet(byte[] src, byte[] dst) {
        return intern(new FlowKeyEthernet(src, dst));
    }

    public static FlowKeyEtherType etherType(short value) {
        return intern(new FlowKeyEtherType(value));
    }

    public static FlowKeyEtherType etherType(FlowKeyEtherType.Type type) {
        return etherType((short)type.value);
    }

    public static FlowKeyIPv4 ipv4(IPv4Addr src, IPv4Addr dst,
                                   IpProtocol protocol) {
        return ipv4(src, dst, protocol.value, (byte)0, (byte)0, IPFragmentType.None);
    }

    /**
     * Will default TOS to 0 if null. All other fields considered mandatory.
     */
    public static FlowKeyIPv4 ipv4(IPv4Addr src, IPv4Addr dst, byte protocol,
                                   Byte typeOfService, byte ttl,
                                   IPFragmentType fragmentType) {
        return intern(new FlowKeyIPv4(src.toInt(), dst.toInt(), protocol,
                                      typeOfService == null ? 0 : typeOfService,
                                      ttl, fragmentType.value));
    }

    public static FlowKeyIPv6 ipv6(IPv6Addr src, IPv6Addr dst,
                                   IpProtocol protocol) {
        return ipv6(src, dst, protocol.value, (byte)0, IPFragmentType.None);
    }

    public static FlowKeyIPv6 ipv6(IPv6Addr src, IPv6Addr dst,
                                   byte protocol) {
        return ipv6(src, dst, protocol, (byte)0, IPFragmentType.None);
    }

    public static FlowKeyIPv6 ipv6(IPv6Addr src, IPv6Addr dst, byte protocol,
                                   byte hlimit, IPFragmentType fragmentType) {
        return intern(new FlowKeyIPv6(src, dst, protocol,
                                      hlimit, fragmentType.value));
    }

    public static FlowKeyICMP icmp(byte type, byte code) {
        return intern(new FlowKeyICMP(type, code));
    }

    public static FlowKeyICMPEcho icmpEcho(byte type, byte code, short id) {
        return intern(new FlowKeyICMPEcho(type, code, id));
    }

    public static FlowKeyICMPError icmpError(byte type, byte code, byte[] data) {
        return intern(new FlowKeyICMPError(type, code, data));
    }

    public static FlowKeyICMPv6 icmpv6(int type, int code) {
        return intern(new FlowKeyICMPv6((byte) type, (byte) code));
    }

    public static FlowKeyUDP udp(int src, int dst) {
        return intern(new FlowKeyUDP(src, dst));
    }

    public static FlowKeyARP arp(byte[] sourceAddress, byte[] targetAddress,
                                 short opcode, int sourceIp, int targetIp) {
        return
            intern(new FlowKeyARP(sourceAddress, targetAddress, opcode,
                                  sourceIp, targetIp));
    }

    public static FlowKeyTCP tcp(int src, int dst) {
        return intern(new FlowKeyTCP(src, dst));
    }

    public static FlowKeyND neighborDiscovery(int[] target) {
        return intern(new FlowKeyND(target));
    }

    public static FlowKeyPriority priority(int priority) {
        return intern(new FlowKeyPriority(priority));
    }

    public static FlowKeyVLAN vlan(short vlan) {
        return intern(new FlowKeyVLAN(vlan));
    }

    public static FlowKeyEncap encap(List<FlowKey> keys) {
        return intern(new FlowKeyEncap(keys));
    }

    public static FlowKeyTunnel tunnel(long tunnelId, int Ipv4SrcAddr,
            int ipv4DstAddr) {
        return intern(new FlowKeyTunnel(tunnelId, Ipv4SrcAddr, ipv4DstAddr));
    }

    public static List<FlowKey> buildFrom(ByteBuffer buf) {
        return NetlinkMessage.getAttrValue(buf,
                                           OpenVSwitch.Flow.Attr.Key,
                                           FlowKey.Builder);
    }

    public static List<FlowKey> randomKeys() {
        List<FlowKey> keys = new ArrayList<>();
        while (rand.nextInt(100) >= 30 && keys.size() <= 10) {
            keys.add(randomKey());
        }
        return keys;
    }

    public static FlowKey randomKey() {
        FlowKey k = null;
        while (k == null) {
            k = FlowKey.Builder.newInstance((short)(1 + rand.nextInt(17)));
        }
        if (k instanceof Randomize) {
            ((Randomize)k).randomize();
        } else {
            byte[] bytes = new byte[1024];
            rand.nextBytes(bytes);
            k.deserialize(ByteBuffer.wrap(bytes));
        }
        return k;
    }

    public static Random rand = new Random();
}
