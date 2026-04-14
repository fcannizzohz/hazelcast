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

import com.hazelcast.internal.util.concurrent.ConcurrentItemCounter;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.operationexecutor.OperationExecutor;
import com.hazelcast.spi.impl.operationexecutor.OperationRunner;
import com.hazelcast.spi.impl.operationservice.impl.OperationServiceImpl;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import java.util.concurrent.locks.LockSupport;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that samples which operations are running on operation threads.
 *
 * <p>Starts a background sampler thread on {@link #onStart} and stops it on {@link #onShutdown}.
 * On each {@link #run} invocation, emits a single NDJSON line with {@code "name":"OperationThreadSamples"}
 * containing per-thread-category (Partition / Generic) sample distributions.
 *
 * <p>Uses the same properties as
 * {@link com.hazelcast.internal.diagnostics.OperationThreadSamplerPlugin}.
 *
 * @since 6.0
 */
public class JsonOperationThreadSamplerPlugin extends JsonDiagnosticsPlugin {

    /** Period in seconds; 0 = disabled. Mirrors {@code OperationThreadSamplerPlugin.PERIOD_SECONDS}. */
    public static final HazelcastProperty PERIOD_SECONDS = new HazelcastProperty(
            "hazelcast.diagnostics.operationthreadsamples.period.seconds", 0, SECONDS);

    /** Interval in milliseconds between individual samples. */
    public static final HazelcastProperty SAMPLER_PERIOD_MILLIS = new HazelcastProperty(
            "hazelcast.diagnostics.operationthreadsamples.sampler.period.millis", 100, MILLISECONDS);

    private static final double HUNDRED = 100.0;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final NodeEngineImpl nodeEngine;
    private final OperationExecutor executor;
    private final long periodMillis;
    private final long samplerPeriodMillis;
    private final ConcurrentItemCounter<String> partitionSamples = new ConcurrentItemCounter<>();
    private final ConcurrentItemCounter<String> genericSamples = new ConcurrentItemCounter<>();

    private volatile Thread samplerThread;

    public JsonOperationThreadSamplerPlugin(NodeEngineImpl nodeEngine) {
        super(nodeEngine.getLogger(JsonOperationThreadSamplerPlugin.class), nodeEngine.getProperties());
        this.nodeEngine = nodeEngine;
        HazelcastProperties props = nodeEngine.getProperties();
        OperationServiceImpl opService = nodeEngine.getOperationService();
        this.executor = opService.getOperationExecutor();
        this.periodMillis = props.getMillis(PERIOD_SECONDS);
        this.samplerPeriodMillis = props.getMillis(SAMPLER_PERIOD_MILLIS);
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void onStart() {
        Thread t = new Thread(this::samplerLoop, "hz-diag-op-sampler");
        t.setDaemon(true);
        samplerThread = t;
        t.start();
    }

    @Override
    public void onShutdown() {
        Thread t = samplerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void samplerLoop() {
        while (nodeEngine.isRunning() && !Thread.currentThread().isInterrupted()) {
            LockSupport.parkNanos(samplerPeriodMillis * NANOS_PER_MILLI);
            sample(executor.getPartitionOperationRunners(), partitionSamples);
            sample(executor.getGenericOperationRunners(), genericSamples);
        }
    }

    private static void sample(OperationRunner[] runners, ConcurrentItemCounter<String> counter) {
        for (OperationRunner runner : runners) {
            Object task = runner.currentTask();
            if (task != null) {
                counter.inc(task.getClass().getName());
            }
        }
    }

    @Override
    public void run(JsonEntryWriter writer) {
        long epoch = System.currentTimeMillis();
        writer.startEntry(epoch, "OperationThreadSamples");
        writer.startObject("Partition");
        writeSamples(writer, partitionSamples);
        writer.endObject();
        writer.startObject("Generic");
        writeSamples(writer, genericSamples);
        writer.endObject();
        writer.endEntry();
    }

    private static void writeSamples(JsonEntryWriter writer, ConcurrentItemCounter<String> samples) {
        long total = samples.total();
        if (total == 0) {
            return;
        }
        writer.startArray("entries");
        for (String name : samples.keySet()) {
            long count = samples.get(name);
            double percentage = HUNDRED * count / total;
            writer.startArrayItem();
            writer.writeString("operation", name);
            writer.writeLong("samples", count);
            writer.writeDouble("percentage_pc", percentage);
            writer.endArrayItem();
        }
        writer.endArray();
    }
}
