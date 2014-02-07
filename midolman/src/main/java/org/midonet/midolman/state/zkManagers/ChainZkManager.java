/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.state.zkManagers;

import java.util.*;

import org.midonet.midolman.rules.RuleList;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.rules.Rule;

/**
 * ZooKeeper DAO class for Chains.
 */
public class ChainZkManager extends AbstractZkManager {

    public static class ChainConfig {

        // The chain name should only be used for logging.
        public String name = null;
        public Map<String, String> properties = new HashMap<String, String>();

        public ChainConfig() {
        }

        public ChainConfig(String name) {
            this.name = name;
        }
    }

    private final static Logger log =
        LoggerFactory.getLogger(ChainZkManager.class);

    /*
     * creates a back reference for the given type and device ID.
     */
    public List<Op> prepareChainBackRefCreate(UUID chainId,
                                              ResourceType resourceType,
                                              UUID deviceId)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        String refPath = paths.getChainBackRefsPath(chainId);
        /*
         * This check exists for backwards compatibility. It is possible that
         * if the deployment was upgraded and no new references were created,
         * Then this path does not exist.
         */
        if (!zk.exists(refPath)) {
            ops.add(Op.create(refPath, null,Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        }

        String backRefPath = paths.getChainBackRefPath(chainId,
                resourceType.toString(), deviceId);
        /*
         * It is possible that this path already exists: if it was added by
         * another reference on this same object. Example: Inbound chain id
         * has been updated to the same is as the Outbound chain id.
         */
        if (!zk.exists(backRefPath)) {
            ops.add(Op.create(backRefPath, null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        }

        return ops;
    }

    /*
     * Removes a back reference for the given type and device ID.
     */
    public List<Op> prepareChainBackRefDelete(UUID chainId,
                                              ResourceType resourceType,
                                              UUID deviceId)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        String backRefPath = paths.getChainBackRefPath(chainId,
                resourceType.toString(), deviceId);
        /*
         * This check exists for backwards compatibility. It is possible that
         * if the deployment was upgraded and no new references were created,
         * Then this path does not exist.
         */
        if (zk.exists(backRefPath)) {
            ops.add(Op.delete(backRefPath, -1));
        }

        return ops;
    }

    /*
     * this complicated function allows us to simplify checking for
     * backreferences later. What it does is account for the fact that on
     * routers, bridges, and ports, the inbound filter and outbound filter may
     *  be the same.
     */
    public List<Op> prepareUpdateFilterBackRef(ResourceType resourceType,
                                               UUID oldIn, UUID newIn,
                                               UUID oldOut, UUID newOut,
                                               UUID deviceId)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        Set<UUID> oldRefs = new HashSet<UUID>();
        if (oldIn != null) oldRefs.add(oldIn);
        if (oldOut != null) oldRefs.add(oldOut);

        Set<UUID> newRefs = new HashSet<UUID>();
        if (newIn != null) newRefs.add(newIn);
        if (newOut != null) newRefs.add(newOut);

        for (UUID newRef : newRefs) {
            if (!oldRefs.contains(newRef)) {
                ops.addAll(prepareChainBackRefCreate(newRef, resourceType,
                                                     deviceId));
            }
        }

        for (UUID oldRef: oldRefs) {
            if (!newRefs.contains(oldRef)) {
                ops.addAll(prepareChainBackRefDelete(oldRef, resourceType,
                                                     deviceId));
            }
        }

        return ops;
    }

    /**
     * Constructor to set ZooKeeper and base path.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public ChainZkManager(ZkManager zk, PathBuilder paths,
                          Serializer serializer) {
        super(zk, paths, serializer);
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new chain.
     *
     * @param id
     *            ID of the chain.
     * @param config
     *            ChainConfig object.
     * @return A list of Op objects to represent the operations to perform.
     * @throws org.midonet.midolman.serialization.SerializationException
     *             Serialization error occurred.
     */
    public List<Op> prepareChainCreate(UUID id, ChainConfig config)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(paths.getChainPath(id),
                serializer.serialize(config),
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getChainRulesPath(id),
                serializer.serialize(new RuleList()),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getChainBackRefsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        return ops;
    }

    /**
     * Constructs a list of operations to perform in a chain deletion.
     *
     * @param id
     *            Chain ID
     * @return A list of Op objects representing the operations to perform.
     * @throws org.midonet.midolman.state.StateAccessException
     */
    public List<Op> prepareChainDelete(UUID id)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        RuleZkManager ruleZkManager = new RuleZkManager(zk, paths, serializer);
        RouterZkManager routerZkManager = new RouterZkManager(zk, paths,
                serializer);
        BridgeZkManager bridgeZkManager = new BridgeZkManager(zk, paths,
                serializer);
        PortZkManager portZkManager = new PortZkManager(zk, paths, serializer);
        List<UUID> ruleIds = ruleZkManager.getRuleList(id).getRuleList();
        for (UUID ruleId : ruleIds) {
            Rule rule = ruleZkManager.get(ruleId);
            ops.addAll(ruleZkManager.prepareRuleDelete(ruleId, rule));
        }

        String chainRefsPath = paths.getChainBackRefsPath(id);
        if (zk.exists(chainRefsPath)) {
            Collection<String> refs = zk.getChildren(chainRefsPath);

            for (String child : refs) {
                String type = paths.getTypeFromBackRef(child);
                UUID childId = paths.getUUIDFromBackRef(child);

                if (type.equals(ResourceType.RULE.toString())) {
                    ops.addAll(ruleZkManager.prepareRuleDelete(childId));
                } else if (type.equals(ResourceType.ROUTER.toString())) {
                    ops.addAll(routerZkManager.prepareClearRefsToChains(
                            childId, id));
                } else if (type.equals(ResourceType.BRIDGE.toString())) {
                    ops.addAll(bridgeZkManager.prepareClearRefsToChains(
                            childId, id));
                } else if (type.equals(ResourceType.PORT.toString())) {
                    ops.addAll(portZkManager.prepareClearRefsToChains(
                            childId, id));
                }

                if (!type.equals(ResourceType.RULE.toString())) {
                    // Skip deleting the rule back ref for rules because
                    // it is removed as a part of the rule deletion.
                    String backRefPath = chainRefsPath + "/" + child;
                    log.debug("Preparing to delete: " + backRefPath);
                    ops.add(Op.delete(backRefPath, -1));
                }
            }

            log.debug("Preparing to delete:" + chainRefsPath);
            ops.add(Op.delete(chainRefsPath, -1));
        }

        String chainRulePath = paths.getChainRulesPath(id);
        log.debug("Preparing to delete: " + chainRulePath);
        ops.add(Op.delete(chainRulePath, -1));

        String chainPath = paths.getChainPath(id);
        log.debug("Preparing to delete: " + chainPath);
        ops.add(Op.delete(chainPath, -1));
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new chain entry.
     *
     * @param chain
     *            ChainConfig object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws SerializationException
     *             Serialization error occurred.
     */
    public UUID create(ChainConfig chain) throws StateAccessException,
            SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareChainCreate(id, chain));
        return id;
    }

    /**
     * Checks whether a chain with the given ID exists.
     *
     * @param id
     *            Chain ID to check
     * @return True if exists
     * @throws StateAccessException
     */
    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getChainPath(id));
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a chain with the given ID.
     *
     * @param id
     *            The ID of the chain.
     * @return ChainConfig object found.
     * @throws StateAccessException
     */
    public ChainConfig get(UUID id) throws StateAccessException,
            SerializationException {
        byte[] data = zk.get(paths.getChainPath(id), null);
        return serializer.deserialize(data, ChainConfig.class);
    }

    /**
     * Updates the ChainConfig values with the given ChainConfig object.
     *
     * @param id
     * @param config
     *            ChainConfig object to save.
     * @throws StateAccessException
     */
    public void update(UUID id, ChainConfig config) throws StateAccessException,
            SerializationException {
        byte[] data = serializer.serialize(config);
        zk.update(paths.getChainPath(id), data);
    }

    /***
     * Deletes a chain and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the chain to delete.
     * @throws SerializationException
     *             Serialization error occurred.
     */
    public void delete(UUID id) throws StateAccessException,
            SerializationException
    {
        zk.multi(prepareChainDelete(id));
    }
}
