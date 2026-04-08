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

import com.hazelcast.internal.management.dto.SlowOperationDTO;
import com.hazelcast.internal.management.dto.SlowOperationInvocationDTO;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.impl.operationservice.impl.OperationServiceImpl;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that reports slow operations.
 *
 * <p>Emits a single NDJSON line with {@code "name":"SlowOperations"} containing
 * all operations whose execution time exceeded the slow-operation threshold,
 * along with their stack traces and individual slow invocation details.
 *
 * <p>Uses the same properties as {@link com.hazelcast.internal.diagnostics.SlowOperationPlugin}.
 *
 * @since 6.0
 */
public class JsonSlowOperationPlugin extends JsonDiagnosticsPlugin {

    /** Period in seconds; mirrors {@code SlowOperationPlugin.PERIOD_SECONDS}. */
    public static final HazelcastProperty PERIOD_SECONDS = new HazelcastProperty(
            "hazelcast.diagnostics.slowoperations.period.seconds", 60, SECONDS);

    private final OperationServiceImpl operationService;
    private final long periodMillis;

    public JsonSlowOperationPlugin(ILogger logger, HazelcastProperties properties,
                                   OperationServiceImpl operationService) {
        super(logger, properties);
        this.operationService = operationService;
        this.periodMillis = computePeriod(properties);
    }

    private long computePeriod(HazelcastProperties properties) {
        if (!properties.getBoolean(ClusterProperty.SLOW_OPERATION_DETECTOR_ENABLED)) {
            return DISABLED_PERIOD_MS;
        }
        return properties.getMillis(PERIOD_SECONDS);
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        long epoch = System.currentTimeMillis();
        List<SlowOperationDTO> slowOperations = operationService.getSlowOperationDTOs();
        writer.startEntry(epoch, "SlowOperations");
        for (SlowOperationDTO dto : slowOperations) {
            renderSlowOperation(writer, dto);
        }
        writer.endEntry();
    }

    private void renderSlowOperation(JsonEntryWriter writer, SlowOperationDTO dto) {
        writer.startObject(dto.operation);
        writer.writeLong("invocations", dto.totalInvocations);

        writer.startObject("stackTrace");
        if (dto.stackTrace != null && !dto.stackTrace.isEmpty()) {
            writer.startArray("entries");
            String[] lines = dto.stackTrace.split(System.lineSeparator());
            for (String line : lines) {
                writer.startArrayItem();
                writer.writeString("line", line);
                writer.endArrayItem();
            }
            writer.endArray();
        }
        writer.endObject();

        writer.startObject("slowInvocations");
        if (dto.invocations != null && !dto.invocations.isEmpty()) {
            writer.startArray("entries");
            for (SlowOperationInvocationDTO inv : dto.invocations) {
                writer.startArrayItem();
                writer.writeLong("startedAt", inv.startedAt);
                writer.writeLong("duration_ms", inv.durationMs);
                writer.writeString("operationDetails", inv.operationDetails);
                writer.endArrayItem();
            }
            writer.endArray();
        }
        writer.endObject();

        writer.endObject();
    }
}
