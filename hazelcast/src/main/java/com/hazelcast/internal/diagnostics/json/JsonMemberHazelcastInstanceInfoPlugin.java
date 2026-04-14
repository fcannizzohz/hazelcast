/*
 * Copyright (c) 2008-2026, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.diagnostics.json;

import com.hazelcast.cluster.Address;
import com.hazelcast.cluster.impl.MemberImpl;
import com.hazelcast.instance.impl.NodeState;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.properties.HazelcastProperty;

import java.util.Collection;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that reports Hazelcast member instance information.
 *
 * <p>Emits a single NDJSON line with {@code "name":"HazelcastInstance"} containing
 * the local member address, cluster membership details, node state, and member list.
 *
 * <p>Uses the same properties as
 * {@link com.hazelcast.internal.diagnostics.MemberHazelcastInstanceInfoPlugin}.
 *
 * @since 6.0
 */
public class JsonMemberHazelcastInstanceInfoPlugin extends JsonDiagnosticsPlugin {

    /** Period in seconds; mirrors {@code MemberHazelcastInstanceInfoPlugin.PERIOD_SECONDS}. */
    public static final HazelcastProperty PERIOD_SECONDS = new HazelcastProperty(
            "hazelcast.diagnostics.memberinfo.period.seconds", 60, SECONDS);

    private final NodeEngineImpl nodeEngine;
    private final long periodMillis;

    public JsonMemberHazelcastInstanceInfoPlugin(NodeEngineImpl nodeEngine) {
        super(nodeEngine.getLogger(JsonMemberHazelcastInstanceInfoPlugin.class), nodeEngine.getProperties());
        this.nodeEngine = nodeEngine;
        this.periodMillis = nodeEngine.getProperties().getMillis(PERIOD_SECONDS);
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        long epoch = System.currentTimeMillis();
        writer.startEntry(epoch, "HazelcastInstance");

        writer.writeString("thisAddress", formatAddress(nodeEngine.getNode().getThisAddress()));
        writer.writeBoolean("isRunning", nodeEngine.getNode().isRunning());
        writer.writeBoolean("isLite", nodeEngine.getNode().isLiteMember());
        writer.writeBoolean("joined", nodeEngine.getNode().getClusterService().isJoined());

        NodeState state = nodeEngine.getNode().getState();
        writer.writeString("nodeState", state == null ? "null" : state.toString());

        writer.writeString("clusterName", nodeEngine.getConfig().getClusterName());
        UUID clusterId = nodeEngine.getClusterService().getClusterId();
        writer.writeString("clusterId", clusterId != null ? clusterId.toString() : "null");
        writer.writeLong("clusterSize", nodeEngine.getClusterService().getSize());
        writer.writeBoolean("isMaster", nodeEngine.getClusterService().isMaster());

        Address masterAddress = nodeEngine.getClusterService().getMasterAddress();
        writer.writeString("masterAddress", masterAddress == null ? "null" : formatAddress(masterAddress));

        Collection<MemberImpl> members = nodeEngine.getClusterService().getMemberImpls();
        writer.startObject("Members");
        if (!members.isEmpty()) {
            writer.startArray("entries");
            for (MemberImpl member : members) {
                writer.startArrayItem();
                writer.writeString("address", formatAddress(member.getAddress()));
                writer.endArrayItem();
            }
            writer.endArray();
        }
        writer.endObject();

        writer.endEntry();
    }
}
