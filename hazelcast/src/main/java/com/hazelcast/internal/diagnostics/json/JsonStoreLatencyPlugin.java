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

import com.hazelcast.internal.diagnostics.DiagnosticsLogWriter;
import com.hazelcast.internal.diagnostics.StoreLatencyPlugin;
import com.hazelcast.internal.util.LatencyDistribution;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.properties.HazelcastProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JSON-aware replacement for {@link StoreLatencyPlugin}.
 *
 * <p>This class extends {@link StoreLatencyPlugin} to serve as the instrumentation
 * point for map stores, cache loaders/writers, queue stores, and ringbuffer stores
 * — exactly as the standard plugin does. It must extend {@code StoreLatencyPlugin}
 * so that the store wrappers (e.g. {@code MapStoreWrapper}) find it via
 * {@code Diagnostics.getPlugin(StoreLatencyPlugin.class)}.
 *
 * <p>In JSON mode the standard {@link com.hazelcast.internal.diagnostics.DiagnosticsLog}
 * write path is a no-op; this plugin's {@link #runJson(JsonEntryWriter)} is instead
 * invoked by {@link JsonDiagnosticsLog} on its own scheduler thread. One NDJSON line
 * is emitted per instrumented service (e.g. {@code MapStore}, {@code ICache}).
 *
 * <p>The plugin maintains its own local {@link LatencyDistribution} map in parallel
 * with the parent's internal structures, so that it can emit per-service JSON lines
 * without access to the parent's private inner classes.
 *
 * <p>Uses the same properties as {@link StoreLatencyPlugin}.
 *
 * @since 6.0
 */
public class JsonStoreLatencyPlugin extends StoreLatencyPlugin {

    /**
     * Three-level map: serviceName → dataStructureName → methodName → distribution.
     * Written by probe-recording threads; read (and reset) by the JSON scheduler thread.
     */
    private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, LatencyDistribution>>>
            jsonDistributions = new ConcurrentHashMap<>();

    private final long resetPeriodMillis;
    private long jsonIteration;
    private long resetFrequency;

    public JsonStoreLatencyPlugin(ILogger logger, HazelcastProperties properties) {
        super(logger, properties);
        this.resetPeriodMillis = properties.getMillis(RESET_PERIOD_SECONDS);
        long period = properties.getMillis(PERIOD_SECONDS);
        if (period == 0 || resetPeriodMillis == 0) {
            this.resetFrequency = 0;
        } else {
            this.resetFrequency = Math.max(1, resetPeriodMillis / period);
        }
    }

    @Override
    public LatencyProbe newProbe(String serviceName, String dataStructureName, String methodName) {
        LatencyProbe parentProbe = super.newProbe(serviceName, dataStructureName, methodName);
        LatencyDistribution dist = jsonDistributions
                .computeIfAbsent(serviceName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(dataStructureName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(methodName, k -> new LatencyDistribution());
        return nanos -> {
            parentProbe.recordValue(nanos);
            dist.recordNanos(nanos);
        };
    }

    /**
     * Overridden to suppress standard text-format output in JSON mode.
     * All output is routed through {@link #runJson(JsonEntryWriter)}.
     */
    @Override
    public void run(DiagnosticsLogWriter writer) {
        // no-op: JSON output is handled by JsonDiagnosticsLog via runJson()
    }

    /**
     * Emits one NDJSON line per instrumented service, then resets statistics if
     * the configured reset frequency has been reached.
     *
     * <p>Must be called from the {@link JsonDiagnosticsLog} scheduler thread.
     *
     * @param writer the JSON entry writer
     */
    public void runJson(JsonEntryWriter writer) {
        jsonIteration++;
        long epoch = System.currentTimeMillis();
        for (Map.Entry<String, ConcurrentMap<String, ConcurrentMap<String, LatencyDistribution>>> serviceEntry
                : jsonDistributions.entrySet()) {
            renderService(writer, epoch, serviceEntry.getKey(), serviceEntry.getValue());
        }
        if (resetFrequency > 0 && jsonIteration % resetFrequency == 0) {
            resetJsonDistributions();
        }
    }

    private void renderService(JsonEntryWriter writer, long epoch, String serviceName,
                                ConcurrentMap<String, ConcurrentMap<String, LatencyDistribution>> dsMap) {
        writer.startEntry(epoch, serviceName);
        for (Map.Entry<String, ConcurrentMap<String, LatencyDistribution>> dsEntry : dsMap.entrySet()) {
            writer.startObject(dsEntry.getKey());
            for (Map.Entry<String, LatencyDistribution> methodEntry : dsEntry.getValue().entrySet()) {
                LatencyDistribution dist = methodEntry.getValue();
                if (dist.count() == 0) {
                    continue;
                }
                writer.startObject(methodEntry.getKey());
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
            writer.endObject();
        }
        writer.endEntry();
    }

    private void resetJsonDistributions() {
        for (ConcurrentMap<String, ConcurrentMap<String, LatencyDistribution>> dsMap : jsonDistributions.values()) {
            for (ConcurrentMap<String, LatencyDistribution> methodMap : dsMap.values()) {
                for (Map.Entry<String, LatencyDistribution> entry : methodMap.entrySet()) {
                    entry.setValue(new LatencyDistribution());
                }
            }
        }
    }
}
