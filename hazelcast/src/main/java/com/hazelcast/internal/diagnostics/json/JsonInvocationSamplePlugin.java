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

import com.hazelcast.internal.diagnostics.OperationDescriptors;
import com.hazelcast.internal.util.Clock;
import com.hazelcast.internal.util.ItemCounter;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.impl.operationservice.impl.Invocation;
import com.hazelcast.spi.impl.operationservice.impl.InvocationRegistry;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that samples pending and slow invocations.
 *
 * <p>Emits a single NDJSON line with {@code "name":"Invocations"} containing:
 * <ul>
 *   <li>{@code Pending}: slow invocations currently executing (above threshold)</li>
 *   <li>{@code History}: cumulative sample counts per operation type</li>
 *   <li>{@code SlowHistory}: cumulative slow-invocation counts per operation type</li>
 * </ul>
 *
 * <p>Uses the same properties as
 * {@link com.hazelcast.internal.diagnostics.InvocationSamplePlugin}.
 *
 * @since 6.0
 */
public class JsonInvocationSamplePlugin extends JsonDiagnosticsPlugin {

    /** Sample period in seconds; 0 = disabled. Mirrors {@code InvocationSamplePlugin.SAMPLE_PERIOD_SECONDS}. */
    public static final HazelcastProperty SAMPLE_PERIOD_SECONDS = new HazelcastProperty(
            "hazelcast.diagnostics.invocation.sample.period.seconds", 0, SECONDS);

    /** Duration threshold to consider an invocation slow. */
    public static final HazelcastProperty SLOW_THRESHOLD_SECONDS = new HazelcastProperty(
            "hazelcast.diagnostics.invocation.slow.threshold.seconds", 5, SECONDS);

    /** Maximum number of slow invocations to include in Pending output. */
    public static final HazelcastProperty SLOW_MAX_COUNT = new HazelcastProperty(
            "hazelcast.diagnostics.invocation.slow.max.count", 100);

    private static final String MAX_COUNT_TEXT = "max number of invocations to print reached.";

    private final InvocationRegistry invocationRegistry;
    private final long samplePeriodMillis;
    private final long thresholdMillis;
    private final int maxCount;
    private final ItemCounter<String> occurrences = new ItemCounter<>();
    private final ItemCounter<String> slowOccurrences = new ItemCounter<>();

    public JsonInvocationSamplePlugin(ILogger logger, HazelcastProperties properties,
                                      InvocationRegistry invocationRegistry) {
        super(logger, properties);
        this.invocationRegistry = invocationRegistry;
        this.samplePeriodMillis = properties.getMillis(SAMPLE_PERIOD_SECONDS);
        this.thresholdMillis = properties.getMillis(SLOW_THRESHOLD_SECONDS);
        this.maxCount = properties.getInteger(SLOW_MAX_COUNT);
    }

    @Override
    public long getPeriodMillis() {
        return samplePeriodMillis;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        long now = Clock.currentTimeMillis();
        long epoch = System.currentTimeMillis();
        writer.startEntry(epoch, "Invocations");
        writePending(writer, now);
        writeHistory(writer);
        writeSlowHistory(writer);
        writer.endEntry();
    }

    private void writePending(JsonEntryWriter writer, long now) {
        writer.startObject("Pending");
        boolean hasEntries = false;
        int count = 0;
        boolean maxPrinted = false;
        for (Invocation invocation : invocationRegistry) {
            long durationMs = now - invocation.firstInvocationTimeMillis;
            String operationDesc = OperationDescriptors.toOperationDesc(invocation.op);
            occurrences.add(operationDesc, 1);

            if (durationMs < thresholdMillis) {
                continue;
            }

            slowOccurrences.add(operationDesc, 1);
            count++;

            if (count <= maxCount) {
                if (!hasEntries) {
                    writer.startArray("entries");
                    hasEntries = true;
                }
                writer.startArrayItem();
                writer.writeString("operation", operationDesc);
                writer.writeLong("duration_ms", durationMs);
                writer.endArrayItem();
            } else if (!maxPrinted) {
                maxPrinted = true;
                if (!hasEntries) {
                    writer.startArray("entries");
                    hasEntries = true;
                }
                writer.startArrayItem();
                writer.writeString("text", MAX_COUNT_TEXT);
                writer.endArrayItem();
            }
        }
        if (hasEntries) {
            writer.endArray();
        }
        writer.endObject();
    }

    private void writeHistory(JsonEntryWriter writer) {
        writer.startObject("History");
        if (!occurrences.keySet().isEmpty()) {
            writer.startArray("entries");
            for (String item : occurrences.descendingKeys()) {
                writer.startArrayItem();
                writer.writeString("operation", item);
                writer.writeLong("samples", occurrences.get(item));
                writer.endArrayItem();
            }
            writer.endArray();
        }
        writer.endObject();
    }

    private void writeSlowHistory(JsonEntryWriter writer) {
        writer.startObject("SlowHistory");
        if (!slowOccurrences.keySet().isEmpty()) {
            writer.startArray("entries");
            for (String item : slowOccurrences.descendingKeys()) {
                writer.startArrayItem();
                writer.writeString("operation", item);
                writer.writeLong("samples", slowOccurrences.get(item));
                writer.endArrayItem();
            }
            writer.endArray();
        }
        writer.endObject();
    }
}
