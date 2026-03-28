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

import com.hazelcast.cluster.Member;
import com.hazelcast.internal.util.Clock;
import com.hazelcast.internal.util.ItemCounter;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.impl.operationservice.Operation;
import com.hazelcast.spi.impl.operationservice.impl.Invocation;
import com.hazelcast.spi.impl.operationservice.impl.InvocationRegistry;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import static com.hazelcast.internal.diagnostics.OperationDescriptors.toOperationDesc;
import static com.hazelcast.internal.util.StringUtil.timeToString;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A {@link DiagnosticsPlugin} that displays all invocations that have been
 * executing for some time.
 * <p>
 * It will display the current invocations and the invocation history. For
 * example, if an entry processor has been running for 5 minutes and the
 * {@link #SAMPLE_PERIOD_SECONDS} is set to 1 minute, then there will be
 * 5 samples for that given invocation. This is useful to track which
 * operations have been slow over a longer period of time.
 */
public class InvocationSamplePlugin extends DiagnosticsPlugin {

    /**
     * The sample period in seconds.
     * <p>
     * If set to 0, the plugin is disabled.
     */
    public static final HazelcastProperty SAMPLE_PERIOD_SECONDS
            = new HazelcastProperty("hazelcast.diagnostics.invocation.sample.period.seconds", 0, SECONDS);

    /**
     * The threshold in seconds to consider an invocation to be slow.
     */
    public static final HazelcastProperty SLOW_THRESHOLD_SECONDS
            = new HazelcastProperty("hazelcast.diagnostics.invocation.slow.threshold.seconds", 5, SECONDS);

    /**
     * The maximum number of slow invocations to print.
     */
    public static final HazelcastProperty SLOW_MAX_COUNT
            = new HazelcastProperty("hazelcast.diagnostics.invocation.slow.max.count", 100);

    private final InvocationRegistry invocationRegistry;
    private long samplePeriodMillis;
    private long thresholdMillis;
    private int maxCount;
    private final ItemCounter<String> slowOccurrences = new ItemCounter<>();
    private final ItemCounter<String> occurrences = new ItemCounter<>();
    private final HazelcastProperties props;

    public InvocationSamplePlugin(ILogger logger, InvocationRegistry invocationRegistry, HazelcastProperties props) {
        super(logger);
        this.invocationRegistry = invocationRegistry;
        this.props = props;
        readProperties();
    }

    @Override
    void readProperties() {
        this.samplePeriodMillis = props.getMillis(overrideProperty(SAMPLE_PERIOD_SECONDS));
        this.thresholdMillis = props.getMillis(overrideProperty(SLOW_THRESHOLD_SECONDS));
        this.maxCount = props.getInteger(overrideProperty(SLOW_MAX_COUNT));
    }

    @Override
    public long getPeriodMillis() {
        return samplePeriodMillis;
    }

    @Override
    public void onStart() {
        super.onStart();
        logger.info("Plugin:active: period-millis:" + samplePeriodMillis + " threshold-millis:" + thresholdMillis);
    }

    @Override
    public void onShutdown() {
        super.onShutdown();
        logger.info("Plugin:inactive");
    }

    @Override
    public void run(DiagnosticsLogWriter writer) {
        if (!isActive()) {
            return;
        }
        long now = Clock.currentTimeMillis();

        writer.startSection("Invocations");

        runCurrent(writer, now);

        renderOccurrences(writer, "History", occurrences);

        renderOccurrences(writer, "SlowHistory", slowOccurrences);

        writer.endSection();
    }

    int getMaxCount() {
        return maxCount;
    }

    long getThresholdMillis() {
        return thresholdMillis;
    }

    private void runCurrent(DiagnosticsLogWriter writer, long now) {
        boolean isJson = writer.getFormat() == DiagnosticsLogFormat.JSON;
        if (!isJson) {
            writer.startSection("Pending");
        }
        int count = 0;
        boolean maxPrinted = false;
        for (Invocation<?> invocation : invocationRegistry) {
            long durationMs = now - invocation.firstInvocationTimeMillis;
            String operationDesc = toOperationDesc(invocation.op);
            occurrences.add(operationDesc, 1);

            if (durationMs < thresholdMillis) {
                // short invocation, lets move on to the next
                continue;
            }

            // it is a slow invocation
            count++;
            if (count < maxCount) {
                if (isJson) {
                    writeInvocationJsonItem(writer, invocation, durationMs);
                } else {
                    writer.writeEntry(invocation + " duration=" + durationMs + " ms");
                }
            } else if (!maxPrinted) {
                maxPrinted = true;
                if (isJson) {
                    writer.startArrayItemSection("Pending");
                    writer.writeKeyValueEntry("warning", "max number of invocations to print reached.");
                    writer.endArrayItemSection();
                } else {
                    writer.writeEntry("max number of invocations to print reached.");
                }
            }
            slowOccurrences.add(operationDesc, 1);
        }
        if (!isJson) {
            writer.endSection();
        }
    }

    private void writeInvocationJsonItem(DiagnosticsLogWriter writer, Invocation<?> invocation, long durationMs) {
        writer.startArrayItemSection("Pending");

        Operation op = invocation.op;
        writer.startSection("Invocation");
        writer.writeKeyValueEntry("class", op.getClass().getName());
        writer.writeKeyValueEntry("serviceName", op.getServiceName());
        writer.writeKeyValueEntry("identityHash", System.identityHashCode(op));
        writer.writeKeyValueEntry("partitionId", op.getPartitionId());
        writer.writeKeyValueEntry("replicaIndex", op.getReplicaIndex());
        writer.writeKeyValueEntry("callId", op.getCallId());
        writer.writeKeyValueEntry("invocationTimeMs", op.getInvocationTime());
        writer.writeKeyValueEntry("invocationTime", timeToString(op.getInvocationTime()));
        writer.writeKeyValueEntry("waitTimeout", op.getWaitTimeout());
        writer.writeKeyValueEntry("callTimeout", op.getCallTimeout());
        writer.writeKeyValueEntry("tenantControl", op.getTenantControlOrNoop().toString());
        writer.endSection();

        writer.writeKeyValueEntry("tryCount", invocation.getTryCount());
        writer.writeKeyValueEntry("tryPauseMillis", invocation.getTryPauseMillis());
        writer.writeKeyValueEntry("invokeCount", invocation.getInvokeCount());
        writer.writeKeyValueEntry("callTimeoutMillis", invocation.getCallTimeoutMillis());
        writer.writeKeyValueEntry("firstInvocationTimeMs", invocation.firstInvocationTimeMillis);
        writer.writeKeyValueEntry("firstInvocationTime", timeToString(invocation.firstInvocationTimeMillis));
        long lastHb = invocation.getLastHeartbeatMillis();
        writer.writeKeyValueEntry("lastHeartbeatMillis", lastHb);
        writer.writeKeyValueEntry("lastHeartbeatTime", timeToString(lastHb));
        Member targetMember = invocation.getTargetMember();
        writer.writeKeyValueEntry("targetAddress",
                invocation.getTargetAddress() != null ? invocation.getTargetAddress().toString() : null);
        writer.writeKeyValueEntry("targetMember", targetMember != null ? targetMember.toString() : null);
        writer.writeKeyValueEntry("memberListVersion", invocation.getMemberListVersion());
        writer.writeKeyValueEntry("pendingResponse", invocation.getPendingResponseDesc());
        writer.writeKeyValueEntry("backupsAcksExpected", invocation.getBackupsAcksExpected());
        writer.writeKeyValueEntry("backupsAcksReceived", invocation.getBackupsAcksReceived());
        writer.writeKeyValueEntry("connection", invocation.getConnectionDesc());
        writer.writeKeyValueEntry("durationMs", durationMs);

        writer.endArrayItemSection();
    }

    private void renderOccurrences(DiagnosticsLogWriter writer, String sectionName, ItemCounter<String> counter) {
        if (writer.getFormat() == DiagnosticsLogFormat.JSON) {
            for (String item : counter.descendingKeys()) {
                writer.startArrayItemSection(sectionName);
                writer.writeKeyValueEntry(item, counter.get(item));
                writer.endArrayItemSection();
            }
        } else {
            writer.startSection(sectionName);
            for (String item : counter.descendingKeys()) {
                writer.writeEntry(item + " samples=" + counter.get(item));
            }
            writer.endSection();
        }
    }
}
