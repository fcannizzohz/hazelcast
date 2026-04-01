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

import com.hazelcast.internal.metrics.MetricDescriptor;
import com.hazelcast.internal.metrics.MetricsRegistry;
import com.hazelcast.internal.metrics.collectors.MetricsCollector;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import static com.hazelcast.internal.metrics.MetricTarget.DIAGNOSTICS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that periodically emits all metrics from the
 * {@link MetricsRegistry}.
 *
 * <p>Each metric produces one NDJSON line with {@code "name":"Metric"} and a
 * single key in {@code "content"} whose name is the metric descriptor string
 * and whose value is a number, string, or null.
 *
 * <p>Uses the same property and period as the standard
 * {@link com.hazelcast.internal.diagnostics.MetricsPlugin}.
 *
 * @since 6.0
 */
public class JsonMetricsPlugin extends JsonDiagnosticsPlugin {

    /**
     * Period in seconds; mirrors {@code MetricsPlugin.PERIOD_SECONDS}.
     * If 0, the plugin is disabled.
     */
    public static final HazelcastProperty PERIOD_SECONDS
            = new HazelcastProperty("hazelcast.diagnostics.metrics.period.seconds", 60, SECONDS);

    private final MetricsRegistry metricsRegistry;
    private final JsonMetricsCollector collector = new JsonMetricsCollector();
    private long periodMillis;

    public JsonMetricsPlugin(ILogger logger, HazelcastProperties properties,
                             MetricsRegistry metricsRegistry) {
        super(logger, properties);
        this.metricsRegistry = metricsRegistry;
        this.periodMillis = getMillis(PERIOD_SECONDS);
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        collector.writer = writer;
        // snapshot the timestamp so all metrics in this run share the same epoch
        collector.timeMillis = System.currentTimeMillis();
        metricsRegistry.collect(collector);
        collector.writer = null;
    }

    // ------------------------------------------------------------------ collector

    private static final class JsonMetricsCollector implements MetricsCollector {

        private static final String ENTRY_NAME = "Metric";

        private JsonEntryWriter writer;
        private long timeMillis;

        @Override
        public void collectLong(MetricDescriptor descriptor, long value) {
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                writer.startEntry(timeMillis, ENTRY_NAME);
                writer.writeLong(descriptor.metricString(), value);
                writer.endEntry();
            }
        }

        @Override
        public void collectDouble(MetricDescriptor descriptor, double value) {
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                writer.startEntry(timeMillis, ENTRY_NAME);
                writer.writeDouble(descriptor.metricString(), value);
                writer.endEntry();
            }
        }

        @Override
        public void collectException(MetricDescriptor descriptor, Exception e) {
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                writer.startEntry(timeMillis, ENTRY_NAME);
                writer.startObject(descriptor.metricString());
                writer.writeString("exceptionClass", e.getClass().getName());
                writer.writeString("message", e.getMessage());
                writer.endObject();
                writer.endEntry();
            }
        }

        @Override
        public void collectNoValue(MetricDescriptor descriptor) {
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                writer.startEntry(timeMillis, ENTRY_NAME);
                writer.writeNull(descriptor.metricString());
                writer.endEntry();
            }
        }
    }
}
