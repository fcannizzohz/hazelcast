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

import com.hazelcast.internal.util.LatencyDistribution;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that reports operation latency distributions.
 *
 * <p>Emits a single NDJSON line with {@code "name":"OperationsProfiler"} containing
 * per-operation-class latency statistics including count, total time, average, max,
 * and full bucket distribution.
 *
 * <p>Uses the same properties as
 * {@link com.hazelcast.internal.diagnostics.OperationProfilerPlugin}.
 *
 * @since 6.0
 */
public class JsonOperationProfilerPlugin extends JsonDiagnosticsPlugin {

    /** Period in seconds; mirrors {@code OperationProfilerPlugin.PERIOD_SECONDS}. */
    public static final HazelcastProperty PERIOD_SECONDS = new HazelcastProperty(
            "hazelcast.diagnostics.operation-profiler.period.seconds", 5, SECONDS);

    private final ConcurrentMap<Class, LatencyDistribution> opLatencyDistributions;
    private final long periodMillis;

    public JsonOperationProfilerPlugin(ILogger logger, HazelcastProperties properties,
                                       ConcurrentMap<Class, LatencyDistribution> opLatencyDistributions) {
        super(logger, properties);
        this.opLatencyDistributions = opLatencyDistributions;
        this.periodMillis = properties.getMillis(PERIOD_SECONDS);
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        long epoch = System.currentTimeMillis();
        writer.startEntry(epoch, "OperationsProfiler");
        writeLatencyDistributions(writer, opLatencyDistributions);
        writer.endEntry();
    }

    static void writeLatencyDistributions(JsonEntryWriter writer,
                                          ConcurrentMap<Class, LatencyDistribution> distributions) {
        for (Map.Entry<Class, LatencyDistribution> entry : distributions.entrySet()) {
            LatencyDistribution dist = entry.getValue();
            if (dist.count() == 0) {
                continue;
            }
            writer.startObject(entry.getKey().getName());
            writer.writeLong("count", dist.count());
            writer.writeLong("totalTime(us)", dist.totalMicros());
            writer.writeLong("avg(us)", dist.avgMicros());
            writer.writeLong("max(us)", dist.maxMicros());
            writer.startArray("latency_distribution");
            for (int b = 0; b < dist.bucketCount(); b++) {
                long value = dist.bucket(b);
                if (value > 0) {
                    long lo = (b == 0) ? 0L : (1L << b);
                    long hi = (1L << (b + 1)) - 1;
                    writer.startArrayItem();
                    writer.writeLong("lo(us)", lo);
                    writer.writeLong("hi(us)", hi);
                    writer.writeLong("count", value);
                    writer.endArrayItem();
                }
            }
            writer.endArray();
            writer.endObject();
        }
    }
}
