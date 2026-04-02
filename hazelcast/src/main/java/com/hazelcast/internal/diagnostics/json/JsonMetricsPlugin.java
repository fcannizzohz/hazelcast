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
 * <p>One {@code metricsRegistry.collect()} call produces exactly <b>one</b> NDJSON
 * line with {@code "name":"Metric"}.  All metrics in the collection cycle are
 * emitted as flat key-value pairs inside {@code "content"} — no per-metric
 * envelope, no wrapper objects.  This makes each line a self-contained snapshot
 * of the full metric state at that instant, which is the natural unit for
 * Loki stream ingestion and cross-metric jq correlation.
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

    private static final String ENTRY_NAME = "Metric";

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
        // One startEntry/endEntry pair wraps the entire collection cycle so that
        // all metrics end up as flat key-value pairs in a single NDJSON line.
        writer.startEntry(System.currentTimeMillis(), ENTRY_NAME);
        collector.writer = writer;
        metricsRegistry.collect(collector);
        collector.writer = null;
        writer.endEntry();
    }

    // ------------------------------------------------------------------ collector

    private static final class JsonMetricsCollector implements MetricsCollector {

        private JsonEntryWriter writer;

        @Override
        public void collectLong(MetricDescriptor descriptor, long value) {
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                writer.writeLong(descriptor.metricString(), value);
            }
        }

        @Override
        public void collectDouble(MetricDescriptor descriptor, double value) {
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                writer.writeDouble(descriptor.metricString(), value);
            }
        }

        @Override
        public void collectException(MetricDescriptor descriptor, Exception e) {
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                writer.startObject(descriptor.metricString());
                writer.writeString("exceptionClass", e.getClass().getName());
                writer.writeString("message", e.getMessage());
                writer.endObject();
            }
        }

        @Override
        public void collectNoValue(MetricDescriptor descriptor) {
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                writer.writeNull(descriptor.metricString());
            }
        }
    }
}
