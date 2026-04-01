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

import com.hazelcast.internal.networking.Networking;
import com.hazelcast.internal.networking.nio.NioNetworking;
import com.hazelcast.internal.networking.nio.NioThread;
import com.hazelcast.internal.server.Server;
import com.hazelcast.internal.server.tcp.TcpServer;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that reports IO thread imbalance.
 *
 * <p>Emits a single NDJSON line with {@code "name":"NetworkingImbalance"} containing
 * per-thread statistics for all NIO input and output threads, including frame counts,
 * byte counts, event counts, and percentage shares of total work.
 *
 * <p>Only active when the networking layer is {@link NioNetworking}; disabled otherwise.
 *
 * <p>Uses the same properties as
 * {@link com.hazelcast.internal.diagnostics.NetworkingImbalancePlugin}.
 *
 * @since 6.0
 */
public class JsonNetworkingImbalancePlugin extends JsonDiagnosticsPlugin {

    /** Period in seconds; 0 = disabled. Mirrors {@code NetworkingImbalancePlugin.PERIOD_SECONDS}. */
    public static final HazelcastProperty PERIOD_SECONDS = new HazelcastProperty(
            "hazelcast.diagnostics.networking-imbalance.seconds", 0, SECONDS);

    private static final double HUNDRED = 100.0;

    private final NioNetworking networking;
    private final long periodMillis;

    public JsonNetworkingImbalancePlugin(ILogger logger, HazelcastProperties properties, Server server) {
        this(logger, properties, extractNetworking(server));
    }

    public JsonNetworkingImbalancePlugin(ILogger logger, HazelcastProperties properties, Networking networking) {
        super(logger, properties);
        this.networking = networking instanceof NioNetworking nio ? nio : null;
        this.periodMillis = this.networking == null ? DISABLED_PERIOD_MS : properties.getMillis(PERIOD_SECONDS);
    }

    private static Networking extractNetworking(Server server) {
        if (server instanceof TcpServer tcpServer) {
            return tcpServer.getNetworking();
        }
        return null;
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        long epoch = System.currentTimeMillis();
        writer.startEntry(epoch, "NetworkingImbalance");
        writer.startObject("InputThreads");
        renderThreads(writer, networking.getInputThreads());
        writer.endObject();
        writer.startObject("OutputThreads");
        renderThreads(writer, networking.getOutputThreads());
        writer.endObject();
        writer.endEntry();
    }

    private void renderThreads(JsonEntryWriter writer, NioThread[] threads) {
        if (threads == null) {
            return;
        }

        long totalFrames = 0;
        long totalPriorityFrames = 0;
        long totalBytes = 0;
        long totalEvents = 0;
        long totalTasks = 0;
        long totalHandleCount = 0;

        for (NioThread t : threads) {
            totalFrames += t.framesTransceived();
            totalPriorityFrames += t.priorityFramesTransceived();
            totalBytes += t.bytesTransceived();
            totalEvents += t.eventCount();
            totalTasks += t.completedTaskCount();
            totalHandleCount += t.handleCount();
        }

        for (NioThread t : threads) {
            writer.startObject(t.getName());
            writer.writeDouble("frames-percentage", pct(t.framesTransceived(), totalFrames));
            writer.writeLong("frames", t.framesTransceived());
            writer.writeDouble("priority-frames-percentage", pct(t.priorityFramesTransceived(), totalPriorityFrames));
            writer.writeLong("priority-frames", t.priorityFramesTransceived());
            writer.writeDouble("bytes-percentage", pct(t.bytesTransceived(), totalBytes));
            writer.writeLong("bytes", t.bytesTransceived());
            writer.writeDouble("events-percentage", pct(t.eventCount(), totalEvents));
            writer.writeLong("events", t.eventCount());
            writer.writeDouble("handle-count-percentage", pct(t.handleCount(), totalHandleCount));
            writer.writeLong("handle-count", t.handleCount());
            writer.writeDouble("tasks-percentage", pct(t.completedTaskCount(), totalTasks));
            writer.writeLong("tasks", t.completedTaskCount());
            writer.endObject();
        }
    }

    private static double pct(long amount, long total) {
        if (total == 0) {
            return 0.0;
        }
        return HUNDRED * amount / total;
    }
}
