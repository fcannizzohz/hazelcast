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

import com.hazelcast.internal.networking.Networking;
import com.hazelcast.internal.networking.nio.NioNetworking;
import com.hazelcast.internal.networking.nio.NioThread;
import com.hazelcast.logging.ILogger;
import com.hazelcast.internal.server.Server;
import com.hazelcast.internal.server.tcp.TcpServer;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * The {@link NetworkingImbalancePlugin} is an experimental plugin meant for
 * detecting imbalance in the IO system.
 * <p>
 * This  plugin will probably mostly be used for internal purposes to get a
 * better understanding of imbalances. Normally imbalances are taken care of
 * by the IOBalancer; but we need to make sure it makes the right choice.
 * <p>
 * This plugin can be used on server and client side.
 */
public class NetworkingImbalancePlugin extends DiagnosticsPlugin {

    /**
     * The period in seconds this plugin runs.
     * <p>
     * If set to 0, the plugin is disabled.
     */
    public static final HazelcastProperty PERIOD_SECONDS
            = new HazelcastProperty("hazelcast.diagnostics.networking-imbalance.seconds", 0, SECONDS);

    private static final double HUNDRED = 100d;

    private final NioNetworking networking;
    private final HazelcastProperties properties;
    private long periodMillis;


    public NetworkingImbalancePlugin(ILogger logger, HazelcastProperties properties, Server server) {
        this(logger, properties, getThreadingModel(server));
    }

    public NetworkingImbalancePlugin(ILogger logger, HazelcastProperties properties, Networking networking) {
        super(logger);

        if (networking instanceof NioNetworking nioNetworking) {
            this.networking = nioNetworking;
        } else {
            this.networking = null;
        }
        this.properties = properties;
        readProperties();
    }

    @Override
    void readProperties() {
        this.periodMillis = this.networking == null ? 0 : properties.getMillis(overrideProperty(PERIOD_SECONDS));
    }

    private static Networking getThreadingModel(Server server) {
        if (!(server instanceof TcpServer)) {
            return null;
        }
        return ((TcpServer) server).getNetworking();
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void onStart() {
        super.onStart();
        logger.info("Plugin:active: period-millis:" + periodMillis);
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
        writer.startSection("NetworkingImbalance");

        writer.startSection("InputThreads");
        render(writer, networking.getInputThreads());
        writer.endSection();

        writer.startSection("OutputThreads");
        render(writer, networking.getOutputThreads());
        writer.endSection();

        writer.endSection();
    }

    private void render(DiagnosticsLogWriter writer, NioThread[] threads) {
        if (threads == null) {
            // this can become null due to stopping of the system
            return;
        }

        long totalPriorityFramesReceived = 0;
        long totalFramesReceived = 0;
        long totalBytesReceived = 0;
        long totalEvents = 0;
        long totalTaskCount = 0;
        long totalHandleCount = 0;

        for (NioThread thread : threads) {
            totalBytesReceived += thread.bytesTransceived();
            totalFramesReceived += thread.framesTransceived();
            totalPriorityFramesReceived += thread.priorityFramesTransceived();
            totalEvents += thread.eventCount();
            totalTaskCount += thread.completedTaskCount();
            totalHandleCount += thread.handleCount();
        }

        for (NioThread thread : threads) {
            writer.startSection(thread.getName());
            writePercentageEntry(writer, "frames-percentage", thread.framesTransceived(), totalFramesReceived);
            writer.writeKeyValueEntry("frames", thread.framesTransceived());
            writePercentageEntry(writer, "priority-frames-percentage",
                    thread.priorityFramesTransceived(), totalPriorityFramesReceived);
            writer.writeKeyValueEntry("priority-frames", thread.priorityFramesTransceived());
            writePercentageEntry(writer, "bytes-percentage", thread.bytesTransceived(), totalBytesReceived);
            writer.writeKeyValueEntry("bytes", thread.bytesTransceived());
            writePercentageEntry(writer, "events-percentage", thread.eventCount(), totalEvents);
            writer.writeKeyValueEntry("events", thread.eventCount());
            writePercentageEntry(writer, "handle-count-percentage", thread.handleCount(), totalHandleCount);
            writer.writeKeyValueEntry("handle-count", thread.handleCount());
            writePercentageEntry(writer, "tasks-percentage", thread.completedTaskCount(), totalTaskCount);
            writer.writeKeyValueEntry("tasks", thread.completedTaskCount());
            writer.endSection();
        }
    }

    private void writePercentageEntry(DiagnosticsLogWriter writer, String key, long amount, long total) {
        if (writer.getFormat() == DiagnosticsLogFormat.JSON) {
            writer.writeKeyValueEntry(key, toPercentageDouble(amount, total));
        } else {
            writer.writeKeyValueEntry(key, toPercentageString(amount, total));
        }
    }

    private double toPercentageDouble(long amount, long total) {
        if (amount == 0L) {
            return 0D;
        }
        if (total == 0L) {
            return Double.NaN;
        }
        return (HUNDRED * amount) / total;
    }

    private String toPercentageString(long amount, long total) {
        return String.format("%1$,.2f", toPercentageDouble(amount, total)) + " %";
    }
}
