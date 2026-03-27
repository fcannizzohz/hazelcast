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

package com.hazelcast.internal.diagnostics;

import com.hazelcast.internal.metrics.MetricDescriptor;
import com.hazelcast.internal.metrics.MetricsRegistry;
import com.hazelcast.internal.metrics.ProbeUnit;
import com.hazelcast.internal.metrics.collectors.MetricsCollector;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import java.util.Locale;

import static com.hazelcast.internal.metrics.MetricTarget.DIAGNOSTICS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A {@link DiagnosticsPlugin} that displays the content of the
 * {@link MetricsRegistry}.
 */
public class MetricsPlugin extends DiagnosticsPlugin {

    /**
     * The period in seconds the {@link MetricsPlugin} runs.
     * <p>
     * The MetricsPlugin periodically writes the contents of the MetricsRegistry
     * to the logfile. For debugging purposes make sure
     * {@link ClusterProperty#METRICS_DEBUG} is set to {@code true}.
     * <p>
     * This plugin is very cheap to use.
     * <p>
     * If set to 0, the plugin is disabled.
     */
    public static final HazelcastProperty PERIOD_SECONDS
            = new HazelcastProperty("hazelcast.diagnostics.metrics.period.seconds", 60, SECONDS);

    private final MetricsRegistry metricsRegistry;
    private final MetricsCollectorImpl metricCollector = new MetricsCollectorImpl();
    private final HazelcastProperties properties;
    private long periodMillis;

    public MetricsPlugin(ILogger logger, MetricsRegistry metricsRegistry,
                         HazelcastProperties properties) {
        super(logger);
        this.metricsRegistry = metricsRegistry;
        this.properties = properties;
        readProperties();

    }

    @Override
    public void onStart() {
        super.onStart();
        logger.info("Plugin:active, period-millis:" + periodMillis);
    }

    @Override
    public void onShutdown() {
        super.onShutdown();
        logger.info("Plugin:inactive");
    }

    @Override
    void readProperties() {
        this.periodMillis = properties.getMillis(overrideProperty(PERIOD_SECONDS));
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void run(DiagnosticsLogWriter writer) {
        if (!isActive()) {
            return;
        }
        metricCollector.writer = writer;
        // we set the time explicitly so that for this particular rendering of the probes, all metrics have exactly
        // the same timestamp
        metricCollector.timeMillis = System.currentTimeMillis();
        if (writer.getFormat() == DiagnosticsLogFormat.JSON) {
            // JSON: wrap the entire collection in a single section so all metrics from one
            // collection cycle are emitted as a single flat JSON object rather than one line
            // per metric. Content: {"metric.key":value, ...} — no "entries" array wrapper.
            writer.startSection(MetricsCollectorImpl.SECTION_NAME, metricCollector.timeMillis);
            metricsRegistry.collect(metricCollector);
            writer.endSection();
        } else {
            metricsRegistry.collect(metricCollector);
        }
        metricCollector.writer = null;
    }

    private static class MetricsCollectorImpl implements MetricsCollector {
        private static final String SECTION_NAME = "Metric";

        private DiagnosticsLogWriter writer;
        private long timeMillis;

        @Override
        public void collectLong(MetricDescriptor descriptor, long value) {
            // MetricCollector is called from a different thread than the Plugin.run(),
            // during shutdown although plugin is closed, it has no ability to close the metric collector.
            // So we need to check if writer is null or not.
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                if (writer.getFormat() == DiagnosticsLogFormat.JSON) {
                    writer.writeKeyValueEntry(metricKey(descriptor), value);
                } else {
                    writer.writeSectionKeyValue(SECTION_NAME, timeMillis, metricKey(descriptor), value);
                }
            }
        }

        @Override
        public void collectDouble(MetricDescriptor descriptor, double value) {
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                if (writer.getFormat() == DiagnosticsLogFormat.JSON) {
                    writer.writeKeyValueEntry(metricKey(descriptor), value);
                } else {
                    writer.writeSectionKeyValue(SECTION_NAME, timeMillis, metricKey(descriptor), value);
                }
            }
        }

        @Override
        public void collectException(MetricDescriptor descriptor, Exception e) {
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                String exceptionValue = e.getClass().getName() + ':' + e.getMessage();
                if (writer.getFormat() == DiagnosticsLogFormat.JSON) {
                    writer.writeKeyValueEntry(metricKey(descriptor), exceptionValue);
                } else {
                    writer.writeSectionKeyValue(SECTION_NAME, timeMillis, metricKey(descriptor), exceptionValue);
                }
            }
        }

        @Override
        public void collectNoValue(MetricDescriptor descriptor) {
            if (writer != null && descriptor.isTargetIncluded(DIAGNOSTICS)) {
                if (writer.getFormat() == DiagnosticsLogFormat.JSON) {
                    writer.writeKeyValueEntry(metricKey(descriptor), "NA");
                } else {
                    writer.writeSectionKeyValue(SECTION_NAME, timeMillis, metricKey(descriptor), "NA");
                }
            }
        }

        private String metricKey(MetricDescriptor descriptor) {
            if (writer.getFormat() == DiagnosticsLogFormat.JSON) {
                return toJsonMetricKey(descriptor);
            }
            return descriptor.metricString();
        }
    }

    /**
     * Formats a metric descriptor as a human-readable JSON key.
     * Format: {@code [prefix.]metric[discriminator=value](unit)}
     * where the discriminator and unit parts are omitted when not present.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code jvm.memory.heap.used(bytes)}</li>
     *   <li>{@code map.size[instance=myMap]}</li>
     *   <li>{@code os.cpu.load}</li>
     * </ul>
     */
    static String toJsonMetricKey(MetricDescriptor descriptor) {
        StringBuilder key = new StringBuilder();
        String prefix = descriptor.prefix();
        if (prefix != null) {
            key.append(prefix).append('.');
        }
        String metric = descriptor.metric();
        if (metric != null) {
            key.append(metric);
        }
        String discriminatorValue = descriptor.discriminatorValue();
        if (discriminatorValue != null) {
            key.append('[').append(descriptor.discriminator())
               .append('=').append(discriminatorValue).append(']');
        }
        descriptor.readTags((tag, tagValue) ->
                key.append('[').append(tag).append('=').append(tagValue).append(']'));
        ProbeUnit unit = descriptor.unit();
        if (unit != null) {
            key.append('(').append(unit.name().toLowerCase(Locale.ROOT)).append(')');
        }
        return key.toString();
    }
}
