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

import com.hazelcast.cluster.impl.MemberImpl;
import com.hazelcast.internal.cluster.ClusterService;
import com.hazelcast.internal.cluster.impl.ClusterHeartbeatManager;
import com.hazelcast.internal.cluster.impl.ClusterServiceImpl;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that detects member-heartbeat deviations.
 *
 * <p>Emits a single NDJSON line with {@code "name":"MemberHeartbeats"} listing
 * all members whose heartbeat deviation exceeds the configured maximum.
 * Emits nothing if no deviating members are found.
 *
 * <p>Uses the same properties as
 * {@link com.hazelcast.internal.diagnostics.MemberHeartbeatPlugin}.
 *
 * @since 6.0
 */
public class JsonMemberHeartbeatPlugin extends JsonDiagnosticsPlugin {

    /** Period in seconds. Mirrors {@code MemberHeartbeatPlugin.PERIOD_SECONDS}. */
    public static final HazelcastProperty PERIOD_SECONDS = new HazelcastProperty(
            "hazelcast.diagnostics.member-heartbeat.period.seconds", 10, SECONDS);

    /** Maximum allowed deviation percentage. */
    public static final HazelcastProperty MAX_DEVIATION_PERCENTAGE
            = new HazelcastProperty("hazelcast.diagnostics.member-heartbeat.max-deviation-percentage", 100);

    private static final float HUNDRED = 100f;

    private final ClusterService clusterService;
    private final long periodMillis;
    private final int maxDeviationPercentage;

    public JsonMemberHeartbeatPlugin(ILogger logger, HazelcastProperties properties,
                                     ClusterService clusterService) {
        super(logger, properties);
        this.clusterService = clusterService;
        this.periodMillis = getMillis(PERIOD_SECONDS);
        this.maxDeviationPercentage = getInteger(MAX_DEVIATION_PERCENTAGE);
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        if (!(clusterService instanceof ClusterServiceImpl clusterServiceImpl)) {
            return;
        }
        ClusterHeartbeatManager manager = clusterServiceImpl.getClusterHeartbeatManager();
        long expectedInterval = manager.getHeartbeatIntervalMillis();
        long nowMillis = System.currentTimeMillis();

        List<MemberImpl> deviating = new ArrayList<>();
        for (MemberImpl member : clusterServiceImpl.getMemberImpls()) {
            long lastHeartbeat = manager.getLastHeartbeatTime(member);
            if (lastHeartbeat == 0L) {
                continue;
            }
            long noHeartbeat = nowMillis - lastHeartbeat;
            float deviation = HUNDRED * ((float) (noHeartbeat - expectedInterval)) / expectedInterval;
            if (deviation >= maxDeviationPercentage) {
                deviating.add(member);
            }
        }
        if (deviating.isEmpty()) {
            return;
        }

        ClusterHeartbeatManager hbManager = clusterServiceImpl.getClusterHeartbeatManager();
        writer.startEntry(nowMillis, "MemberHeartbeats");
        writer.startArray("members");
        for (MemberImpl member : deviating) {
            long lastHeartbeat = hbManager.getLastHeartbeatTime(member);
            long noHeartbeat = nowMillis - lastHeartbeat;
            double deviation = HUNDRED * ((double) (noHeartbeat - expectedInterval)) / expectedInterval;
            writer.startArrayItem();
            writer.writeString("address", formatAddress(member.getAddress()));
            writer.writeDouble("deviation_pct", deviation);
            writer.writeLong("noHeartbeat_ms", noHeartbeat);
            writer.writeLong("lastHeartbeat_ms", lastHeartbeat);
            writer.writeLong("now_ms", nowMillis);
            writer.endArrayItem();
        }
        writer.endArray();
        writer.endEntry();
    }
}
