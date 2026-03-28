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


import com.hazelcast.internal.util.LatencyDistribution;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A {@link DiagnosticsPlugin} that displays operation latency information.
 * <p>
 * This plugin measure the time an operation runs on an operation thread; if the operation is a blocking
 * operation or being offloaded, only the time on the operation thread is measured.
 */
public class OperationProfilerPlugin extends DiagnosticsPlugin {

    /**
     * The period in seconds the OperationProfilerPlugin runs.
     * <p>
     * This plugin is very cheap to use.
     * <p>
     * If set to 0, the plugin is disabled.
     */
    public static final HazelcastProperty PERIOD_SECONDS = new HazelcastProperty(
            "hazelcast.diagnostics.operation-profiler.period.seconds", 5, SECONDS);

    private final ConcurrentMap<Class, LatencyDistribution> opLatencyDistribution;
    private long periodMillis;
    private final HazelcastProperties props;

    public OperationProfilerPlugin(ILogger logger,
                                   ConcurrentMap<Class, LatencyDistribution> opLatencyDistribution,
                                   HazelcastProperties props) {
        super(logger);
        this.opLatencyDistribution = opLatencyDistribution;
        this.props = props;
        readProperties();
    }

    @Override
    void readProperties() {
        this.periodMillis = props.getMillis(overrideProperty(PERIOD_SECONDS));
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void onStart() {
        super.onStart();
        logger.info("Plugin:active, period-millis:" + periodMillis);
    }

    @Override
    public void onShutdown() {
        super.onShutdown();
        logger.info("Plugin:inactive, period-millis:" + periodMillis);
    }

    @Override
    public void run(DiagnosticsLogWriter writer) {
        if (!isActive()) {
            return;
        }
        writer.startSection("OperationsProfiler");

        write(writer, opLatencyDistribution);

        writer.endSection();
    }

    static void write(DiagnosticsLogWriter writer, ConcurrentMap<Class, LatencyDistribution> opLatencyDistribution) {
        for (Map.Entry<Class, LatencyDistribution> entry : opLatencyDistribution.entrySet()) {
            LatencyDistribution distribution = entry.getValue();
            if (distribution.count() == 0) {
                continue;
            }
            writer.startSection(entry.getKey().getName());
            writeLatencyStats(writer, distribution);
            writer.endSection();
        }
    }

    /**
     * Writes the common latency statistics (count, totalTime, avg, max) and
     * the latency-distribution for the given distribution.
     * The caller is responsible for opening and closing the enclosing section.
     * <p>
     * In JSON format the distribution is emitted as a {@code "latency_distribution"} array
     * of objects with {@code lower_us}, {@code upper_us}, and {@code count} fields.
     * In standard format it is emitted as a {@code latency-distribution} section with
     * bucket-label keys.
     */
    static void writeLatencyStats(DiagnosticsLogWriter writer, LatencyDistribution distribution) {
        writer.writeKeyValueEntry("count", distribution.count());
        writer.writeKeyValueEntry("totalTime(us)", distribution.totalMicros());
        writer.writeKeyValueEntry("avg(us)", distribution.avgMicros());
        writer.writeKeyValueEntry("max(us)", distribution.maxMicros());
        if (writer.getFormat() == DiagnosticsLogFormat.JSON) {
            for (int bucket = 0; bucket < distribution.bucketCount(); bucket++) {
                long value = distribution.bucket(bucket);
                if (value > 0) {
                    writer.startArrayItemSection("latency_distribution");
                    writer.writeKeyValueEntry("lower_us", LatencyDistribution.bucketMinUs(bucket));
                    writer.writeKeyValueEntry("upper_us", LatencyDistribution.bucketMaxUs(bucket));
                    writer.writeKeyValueEntry("count", value);
                    writer.endArrayItemSection();
                }
            }
        } else {
            writer.startSection("latency-distribution");
            for (int bucket = 0; bucket < distribution.bucketCount(); bucket++) {
                long value = distribution.bucket(bucket);
                if (value > 0) {
                    writer.writeKeyValueEntry(LatencyDistribution.LATENCY_KEYS[bucket], value);
                }
            }
            writer.endSection();
        }
    }
}
