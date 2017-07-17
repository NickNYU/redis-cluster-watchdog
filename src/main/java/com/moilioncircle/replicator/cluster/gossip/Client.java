/*
 * Copyright 2016 leon chen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.moilioncircle.replicator.cluster.gossip;

import com.moilioncircle.replicator.cluster.ClusterNode;
import com.moilioncircle.replicator.cluster.Server;
import com.moilioncircle.replicator.cluster.codec.RedisDecoder;
import com.moilioncircle.replicator.cluster.codec.RedisEncoder;
import com.moilioncircle.replicator.cluster.config.ConfigInfo;
import com.moilioncircle.replicator.cluster.util.Arrays;
import com.moilioncircle.replicator.cluster.util.net.NioBootstrapConfiguration;
import com.moilioncircle.replicator.cluster.util.net.NioBootstrapImpl;
import com.moilioncircle.replicator.cluster.util.net.transport.Transport;
import com.moilioncircle.replicator.cluster.util.net.transport.TransportListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.concurrent.ExecutionException;

import static com.moilioncircle.replicator.cluster.ClusterConstants.*;
import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;

/**
 * @author Leon Chen
 * @since 2.1.0
 */
public class Client {
    private static final Log logger = LogFactory.getLog(Client.class);
    private Server server;
    private ThinGossip gossip;

    public Client(ThinGossip gossip) {
        this.gossip = gossip;
        this.server = gossip.server;
    }

    public void clientInit() {
        NioBootstrapImpl<Object> cfd = new NioBootstrapImpl<>(true, new NioBootstrapConfiguration());
        cfd.setEncoder(RedisEncoder::new);
        cfd.setDecoder(RedisDecoder::new);
        cfd.setup();
        cfd.setTransportListener(new TransportListener<Object>() {
            @Override
            public void onConnected(Transport<Object> transport) {
                logger.info("[acceptor] > " + transport.toString());
            }

            @Override
            public void onMessage(Transport<Object> transport, Object message) {
                gossip.executor.execute(() -> {
                    ConfigInfo oldInfo = ConfigInfo.valueOf(gossip.server.cluster);
                    clusterCommand(transport, (byte[][]) message);
                    ConfigInfo newInfo = ConfigInfo.valueOf(gossip.server.cluster);
                    if (!oldInfo.equals(newInfo)) gossip.configManager.clusterSaveConfig();
                });
            }

            @Override
            public void onDisconnected(Transport<Object> transport, Throwable cause) {
                logger.info("[acceptor] < " + transport.toString());
            }
        });
        try {
            cfd.connect(null, gossip.configuration.getClusterAnnouncePort()).get();
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            } else {
                throw new UnsupportedOperationException(e.getCause());
            }
        }
    }

    public void clusterCommand(Transport<Object> t, byte[][] message) {
        String arg0 = new String(message[0]);
        if (!arg0.equalsIgnoreCase("cluster")) {
            t.write("-ERR Unsupported operation [" + Arrays.deepToString(message) + "]\r\n".getBytes(), true);
            return;
        }
        // cluster commands
        String[] argv = new String[message.length];
        for (int i = 0; i < message.length; i++) {
            argv[i] = new String(message[i]);
        }
        if (argv[1].equalsIgnoreCase("meet") && (argv.length == 4 || argv.length == 5)) {
            int cport = 0;
            int port = parseInt(argv[3]);
            if (argv.length == 5) {
                cport = parseInt(argv[4]);
            } else {
                cport = port + CLUSTER_PORT_INCR;
            }

            if (gossip.clusterStartHandshake(argv[2], port, cport)) {
                t.write("+OK\r\n".getBytes(), true);
            } else {
                t.write("-ERR Invalid node address specified:" + argv[2] + ":" + argv[3] + "\r\n".getBytes(), true);
            }
        } else if (argv[1].equalsIgnoreCase("nodes") && argv.length == 2) {
            /* CLUSTER NODES */
            String ci = gossip.configManager.clusterGenNodesDescription(0);
            t.write("$" + ci.length() + "\r\n" + ci + "\r\n".getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("myid") && argv.length == 2) {
            /* CLUSTER MYID */
            t.write("+" + server.myself.name + "\r\n".getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("flushslots") && argv.length == 2) {
            t.write("-ERR Unsupported operation [cluster " + argv[1] + "]\r\n".getBytes(), true);
        } else if ((argv[1].equalsIgnoreCase("addslots") || argv[1].equalsIgnoreCase("delslots")) && argv.length >= 3) {
            t.write("-ERR Unsupported operation [cluster " + argv[1] + "]\r\n".getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("setslot") && argv.length >= 4) {
            t.write("-ERR Unsupported operation [cluster " + argv[1] + "]\r\n".getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("bumpepoch") && argv.length == 2) {
            boolean retval = clusterBumpConfigEpochWithoutConsensus();
            String reply = new StringBuilder("+").append(retval ? "BUMPED" : "STILL").append(" ").append(server.myself.configEpoch).append("\r\n").toString();
            t.write(reply.getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("info") && argv.length == 2) {
            String[] statestr = {"ok", "fail", "needhelp"};
            int slotsAssigned = 0, slotsOk = 0, slotsFail = 0, slotsPfail = 0;

            for (int j = 0; j < CLUSTER_SLOTS; j++) {
                ClusterNode n = server.cluster.slots[j];

                if (n == null) continue;
                slotsAssigned++;
                if (nodeFailed(n)) {
                    slotsFail++;
                } else if (nodePFailed(n)) {
                    slotsPfail++;
                } else {
                    slotsOk++;
                }
            }

            long myepoch = (nodeIsSlave(server.myself) && server.myself.slaveof != null) ? server.myself.slaveof.configEpoch : server.myself.configEpoch;

            StringBuilder info = new StringBuilder("cluster_state:").append(statestr[server.cluster.state]).append("\r\n")
                    .append("cluster_slots_assigned:").append(slotsAssigned).append("\r\n")
                    .append("cluster_slots_ok:").append(slotsOk).append("\r\n")
                    .append("cluster_slots_pfail:").append(slotsPfail).append("\r\n")
                    .append("cluster_slots_fail:").append(slotsFail).append("\r\n")
                    .append("cluster_known_nodes:").append(server.cluster.nodes.size()).append("\r\n")
                    .append("cluster_size:").append(server.cluster.size).append("\r\n")
                    .append("cluster_current_epoch:").append(server.cluster.currentEpoch).append("\r\n")
                    .append("cluster_my_epoch:").append(myepoch).append("\r\n");


            long totMsgSent = 0;
            long totMsgReceived = 0;

            for (int i = 0; i < CLUSTERMSG_TYPE_COUNT; i++) {
                if (server.cluster.statsBusMessagesSent[i] == 0) continue;
                totMsgSent += server.cluster.statsBusMessagesSent[i];
                info.append("cluster_stats_messages_" + gossip.configManager.clusterGetMessageTypeString(i) + "_sent:").append(server.cluster.statsBusMessagesSent[i]).append("\r\n");
            }

            info.append("cluster_stats_messages_sent:").append(totMsgSent).append("\r\n");

            for (int i = 0; i < CLUSTERMSG_TYPE_COUNT; i++) {
                if (server.cluster.statsBusMessagesReceived[i] == 0) continue;
                totMsgReceived += server.cluster.statsBusMessagesReceived[i];
                info.append("cluster_stats_messages_" + gossip.configManager.clusterGetMessageTypeString(i) + "_received:").append(server.cluster.statsBusMessagesReceived[i]).append("\r\n");
            }

            info.append("cluster_stats_messages_received:").append(totMsgReceived).append("\r\n");
            t.write("$" + info.length() + "\r\n" + info.toString() + "\r\n".getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("saveconfig") && argv.length == 2) {
            if (!gossip.configManager.clusterSaveConfig()) {
                t.write("-ERR Error saving the cluster node config\r\n".getBytes(), true);
            }
            t.write("+OK\r\n".getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("keyslot") && argv.length == 3) {
            t.write(":" + String.valueOf(gossip.slotManger.keyHashSlot(argv[2])) + "\r\n".getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("countkeysinslot") && argv.length == 3) {
            t.write("-ERR Unsupported operation [cluster " + argv[1] + "]\r\n".getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("forget") && argv.length == 3) {
            ClusterNode n = gossip.nodeManager.clusterLookupNode(argv[2]);

            if (n == null) {
                t.write("-ERR Unknown node " + argv[2] + "\r\n".getBytes(), true);
                return;
            } else if (n.equals(server.myself)) {
                t.write("-ERR I tried hard but I can't forget myself...\r\n".getBytes(), true);
                return;
            } else if (nodeIsSlave(server.myself) && server.myself.slaveof.equals(n)) {
                t.write("-ERR Can't forget my master!\r\n".getBytes(), true);
                return;
            }
            gossip.blacklistManager.clusterBlacklistAddNode(n);
            gossip.nodeManager.clusterDelNode(n);
            gossip.clusterUpdateState();
            t.write("+OK\r\n".getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("replicate") && argv.length == 3) {
            ClusterNode n = gossip.nodeManager.clusterLookupNode(argv[2]);

            if (n == null) {
                t.write("-ERR Unknown node " + argv[2] + "\r\n".getBytes(), true);
                return;
            }

            if (n.equals(server.myself)) {
                t.write("-ERR Can't replicate myself\r\n".getBytes(), true);
                return;
            }

            if (nodeIsSlave(n)) {
                t.write("-ERR I can only replicate a master, not a slave.\r\n".getBytes(), true);
                return;
            }

            if (nodeIsMaster(server.myself) && (server.myself.numslots != 0)) {
                t.write("-ERR To set a master the node must be empty and without assigned slots.\r\n".getBytes(), true);
                return;
            }

            gossip.clusterSetMaster(n);
            gossip.clusterUpdateState();
            t.write("+OK\r\n".getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("slaves") && argv.length == 3) {
            ClusterNode n = gossip.nodeManager.clusterLookupNode(argv[2]);

            if (n == null) {
                t.write("-ERR Unknown node " + argv[2] + "\r\n".getBytes(), true);
                return;
            }

            if (nodeIsSlave(n)) {
                t.write("-ERR The specified node is not a master\r\n".getBytes(), true);
                return;
            }

            StringBuilder ci = new StringBuilder();
            for (int j = 0; j < n.numslaves; j++) {
                ci.append(gossip.configManager.clusterGenNodeDescription(n.slaves.get(j)));
            }
            t.write("$" + ci.length() + "\r\n" + ci.toString() + "\r\n".getBytes(), true);
        } else if (argv[1].equalsIgnoreCase("count-failure-reports") && argv.length == 3) {
            /* CLUSTER COUNT-FAILURE-REPORTS <NODE ID> */
            ClusterNode n = gossip.nodeManager.clusterLookupNode(argv[2]);

            if (n == null) {
                t.write("-ERR Unknown node " + argv[2] + "\r\n".getBytes(), true);
                return;
            } else {
                t.write(":" + String.valueOf(gossip.nodeManager.clusterNodeFailureReportsCount(n)).getBytes() + "\r\n", true);
            }
        } else if (argv[1].equalsIgnoreCase("set-config-epoch") && argv.length == 3) {
            long epoch = parseLong(argv[2]);

            if (epoch < 0) {
                t.write("-ERR Invalid config epoch specified: " + epoch + "\r\n".getBytes(), true);
            } else if (server.cluster.nodes.size() > 1) {
                t.write("-ERR The user can assign a config epoch only when the node does not know any other node.\r\n".getBytes(), true);
            } else if (server.myself.configEpoch != 0) {
                t.write("-ERR Node config epoch is already non-zero\r\n".getBytes(), true);
            } else {
                server.myself.configEpoch = epoch;
                logger.warn("configEpoch set to " + server.myself.configEpoch + " via CLUSTER SET-CONFIG-EPOCH");
                if (server.cluster.currentEpoch < epoch)
                    server.cluster.currentEpoch = epoch;
                gossip.clusterUpdateState();
                t.write("+OK\r\n".getBytes(), true);
            }
        } else if (argv[1].equalsIgnoreCase("reset") && (argv.length == 2 || argv.length == 3)) {
            t.write("-ERR Unsupported operation [cluster " + argv[1] + "]\r\n".getBytes(), true);
        } else {
            t.write("-ERR Wrong CLUSTER subcommand or number of arguments\r\n".getBytes(), true);
        }
    }

    public boolean clusterBumpConfigEpochWithoutConsensus() {
        long maxEpoch = gossip.clusterGetMaxEpoch();
        if (server.myself.configEpoch == 0 || server.myself.configEpoch != maxEpoch) {
            server.cluster.currentEpoch++;
            server.myself.configEpoch = server.cluster.currentEpoch;
            logger.warn("New configEpoch set to " + server.myself.configEpoch);
            return true;
        }
        return false;
    }
}
