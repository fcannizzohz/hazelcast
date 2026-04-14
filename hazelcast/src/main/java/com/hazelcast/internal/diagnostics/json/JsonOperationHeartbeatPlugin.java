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
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.impl.operationservice.impl.InvocationMonitor;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that detects operation-heartbeat deviations.
 *
 * <p>Emits a single NDJSON line with {@code "name":"OperationHeartbeat"} listing
 * all members whose heartbeat deviation exceeds the configured maximum.
 * Emits nothing if no deviating members are found.
 *
 * <p>Uses the same properties as
 * {@link com.hazelcast.internal.diagnostics.OperationHeartbeatPlugin}.
 *
 * @since 6.0
 */
public class JsonOperationHeartbeatPlugin extends JsonDiagnosticsPlugin {

    /** Period in seconds. Mirrors {@code OperationHeartbeatPlugin.PERIOD_SECONDS}. */
    public static final HazelcastProperty PERIOD_SECONDS
            = new HazelcastProperty("hazelcast.diagnostics.operation-heartbeat.seconds", 10, SECONDS);

    /** Maximum allowed deviation percentage before a member is logged. */
    public static final HazelcastProperty MAX_DEVIATION_PERCENTAGE
            = new HazelcastProperty("hazelcast.diagnostics.operation-heartbeat.max-deviation-percentage", 33);

    private static final float HUNDRED = 100f;

    private final ConcurrentMap<Address, AtomicLong> heartbeatPerMember;
    private final long expectedIntervalMillis;
    private final long periodMillis;
    private final int maxDeviationPercentage;

    public JsonOperationHeartbeatPlugin(ILogger logger, HazelcastProperties properties,
                                        InvocationMonitor invocationMonitor) {
        super(logger, properties);
        this.expectedIntervalMillis = invocationMonitor.getHeartbeatBroadcastPeriodMillis();
        this.heartbeatPerMember = invocationMonitor.getHeartbeatPerMember();
        this.periodMillis = getMillis(PERIOD_SECONDS);
        this.maxDeviationPercentage = getInteger(MAX_DEVIATION_PERCENTAGE);
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        long nowMillis = System.currentTimeMillis();
        List<Map.Entry<Address, AtomicLong>> deviating = new ArrayList<>();
        for (Map.Entry<Address, AtomicLong> entry : heartbeatPerMember.entrySet()) {
            long noHeartbeatMillis = nowMillis - entry.getValue().longValue();
            float deviation = HUNDRED * ((float) (noHeartbeatMillis - expectedIntervalMillis)) / expectedIntervalMillis;
            if (deviation >= maxDeviationPercentage) {
                deviating.add(entry);
            }
        }
        if (deviating.isEmpty()) {
            return;
        }

        writer.startEntry(nowMillis, "OperationHeartbeat");
        writer.startArray("members");
        for (Map.Entry<Address, AtomicLong> entry : deviating) {
            long lastHeartbeatMillis = entry.getValue().longValue();
            long noHeartbeatMillis = nowMillis - lastHeartbeatMillis;
            double deviation = HUNDRED * ((double) (noHeartbeatMillis - expectedIntervalMillis)) / expectedIntervalMillis;
            writer.startArrayItem();
            writer.writeString("address", entry.getKey().toString());
            writer.writeDouble("deviation_pc", deviation);
            writer.writeLong("noHeartbeat_ms", noHeartbeatMillis);
            writer.writeLong("lastHeartbeat_ms", lastHeartbeatMillis);
            writer.writeLong("now_ms", nowMillis);
            writer.endArrayItem();
        }
        writer.endArray();
        writer.endEntry();
    }
}
