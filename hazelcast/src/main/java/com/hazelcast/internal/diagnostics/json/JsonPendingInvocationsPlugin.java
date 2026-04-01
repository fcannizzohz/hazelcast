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

import com.hazelcast.internal.util.ItemCounter;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.impl.operationservice.impl.Invocation;
import com.hazelcast.spi.impl.operationservice.impl.InvocationRegistry;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import static com.hazelcast.internal.diagnostics.OperationDescriptors.toOperationDesc;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that periodically emits pending invocations aggregated
 * by operation type.
 *
 * <p>Uses the same properties as the standard
 * {@link com.hazelcast.internal.diagnostics.PendingInvocationsPlugin}.
 *
 * @since 6.0
 */
public final class JsonPendingInvocationsPlugin extends JsonDiagnosticsPlugin {

    /** Period in seconds; 0 = disabled. Mirrors {@code PendingInvocationsPlugin.PERIOD_SECONDS}. */
    public static final HazelcastProperty PERIOD_SECONDS
            = new HazelcastProperty("hazelcast.diagnostics.pending.invocations.period.seconds", 0, SECONDS);

    /** Minimum count per operation type before it appears in output. */
    public static final HazelcastProperty THRESHOLD
            = new HazelcastProperty("hazelcast.diagnostics.pending.invocations.threshold", 1);

    private final InvocationRegistry invocationRegistry;
    private final ItemCounter<String> occurrenceMap = new ItemCounter<>();
    private final long periodMillis;
    private final int threshold;

    public JsonPendingInvocationsPlugin(ILogger logger, HazelcastProperties properties,
                                        InvocationRegistry invocationRegistry) {
        super(logger, properties);
        this.invocationRegistry = invocationRegistry;
        this.periodMillis = getMillis(PERIOD_SECONDS);
        this.threshold = getInteger(THRESHOLD);
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        occurrenceMap.reset();
        for (Invocation invocation : invocationRegistry) {
            occurrenceMap.add(toOperationDesc(invocation.op), 1);
        }

        long epoch = System.currentTimeMillis();
        writer.startEntry(epoch, "PendingInvocations");
        writer.writeLong("count", invocationRegistry.size());
        writer.startObject("invocations");
        boolean hasEntries = false;
        for (String op : occurrenceMap.keySet()) {
            long count = occurrenceMap.get(op);
            if (count >= threshold) {
                if (!hasEntries) {
                    writer.startArray("entries");
                    hasEntries = true;
                }
                writer.startArrayItem();
                writer.writeString("operation", op);
                writer.writeLong("count", count);
                writer.endArrayItem();
            }
        }
        if (hasEntries) {
            writer.endArray();
        }
        writer.endObject();
        writer.endEntry();
    }
}
