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

import com.hazelcast.internal.networking.OutboundFrame;
import com.hazelcast.internal.networking.nio.NioChannel;
import com.hazelcast.internal.networking.nio.NioOutboundPipeline;
import com.hazelcast.internal.nio.Packet;
import com.hazelcast.internal.serialization.SerializationService;
import com.hazelcast.internal.server.ServerConnection;
import com.hazelcast.internal.server.tcp.TcpServerConnection;
import com.hazelcast.internal.util.ItemCounter;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.operationservice.Operation;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that detects connections with large packet queues.
 *
 * <p>Samples packet queues of overloaded connections and emits a single NDJSON line
 * with {@code "name":"OverloadedConnections"} describing the sampled content.
 * Emits nothing if no connection exceeds the configured threshold.
 *
 * <p>This plugin is expensive because it deserialises sampled packets.
 * It is disabled by default and should only be used for debugging.
 *
 * <p>Uses the same properties as
 * {@link com.hazelcast.internal.diagnostics.OverloadedConnectionsPlugin}.
 *
 * @since 6.0
 */
public class JsonOverloadedConnectionsPlugin extends JsonDiagnosticsPlugin {

    /** Period in seconds; 0 = disabled. Mirrors {@code OverloadedConnectionsPlugin.PERIOD_SECONDS}. */
    public static final HazelcastProperty PERIOD_SECONDS = new HazelcastProperty(
            "hazelcast.diagnostics.overloaded.connections.period.seconds", 0, SECONDS);

    /** Minimum queue depth before a connection is considered overloaded. */
    public static final HazelcastProperty THRESHOLD = new HazelcastProperty(
            "hazelcast.diagnostics.overloaded.connections.threshold", 10000);

    /** Number of packets to sample per overloaded connection. */
    public static final HazelcastProperty SAMPLES = new HazelcastProperty(
            "hazelcast.diagnostics.overloaded.connections.samples", 1000);

    private static final Queue<OutboundFrame> EMPTY_QUEUE = new LinkedList<>();

    private final NodeEngineImpl nodeEngine;
    private final SerializationService serializationService;
    private final Random random = new Random();
    private final long periodMillis;
    private final int threshold;
    private final int samples;

    public JsonOverloadedConnectionsPlugin(ILogger logger, HazelcastProperties properties,
                                           NodeEngineImpl nodeEngine) {
        super(logger, properties);
        this.nodeEngine = nodeEngine;
        this.serializationService = nodeEngine.getSerializationService();
        this.periodMillis = properties.getMillis(PERIOD_SECONDS);
        this.threshold = properties.getInteger(THRESHOLD);
        this.samples = properties.getInteger(SAMPLES);
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        Collection<ServerConnection> connections = nodeEngine.getNode().getServer().getConnections();
        List<ConnectionSample> results = new ArrayList<>();
        for (ServerConnection connection : connections) {
            if (connection instanceof TcpServerConnection tcp) {
                trySample(tcp, false, results);
                trySample(tcp, true, results);
            }
        }
        if (results.isEmpty()) {
            return;
        }

        long epoch = System.currentTimeMillis();
        writer.startEntry(epoch, "OverloadedConnections");
        writer.startArray("connection");
        for (ConnectionSample sample : results) {
            renderConnectionSample(writer, sample);
        }
        writer.endArray();
        writer.endEntry();
    }

    private void trySample(TcpServerConnection connection, boolean priority,
                           List<ConnectionSample> results) {
        Queue<OutboundFrame> queue = getOutboundQueue(connection, priority);
        ArrayList<OutboundFrame> snapshot = new ArrayList<>(queue);
        if (snapshot.size() < threshold) {
            return;
        }

        ItemCounter<String> occurrences = new ItemCounter<>();
        int sampleCount = min(samples, snapshot.size());
        int actualSampleCount = 0;
        for (int i = 0; i < sampleCount; i++) {
            String key = toKey(snapshot.get(random.nextInt(snapshot.size())));
            if (key != null) {
                occurrences.add(key, 1);
                actualSampleCount++;
            }
        }

        String from = formatSocketAddress((InetSocketAddress) connection.getChannel().localSocketAddress());
        String to = formatSocketAddress((InetSocketAddress) connection.getChannel().remoteSocketAddress());
        results.add(new ConnectionSample(from, to, priority, snapshot.size(), actualSampleCount, occurrences));
    }

    private void renderConnectionSample(JsonEntryWriter writer, ConnectionSample sample) {
        writer.startArrayItem();
        writer.writeString("from", sample.from);
        writer.writeString("to", sample.to);
        if (sample.priority) {
            writer.writeLong("urgentPacketCount", sample.packetCount);
        } else {
            writer.writeLong("packetCount", sample.packetCount);
        }
        writer.writeLong("sampleCount", sample.sampleCount);
        writer.startObject("samples");
        if (sample.sampleCount > 0) {
            writer.startArray("entries");
            for (String key : sample.occurrences.keySet()) {
                long count = sample.occurrences.get(key);
                if (count > 0) {
                    double percentage = (double) count / sample.sampleCount;
                    writer.startArrayItem();
                    writer.writeString("connectionType", key);
                    writer.writeLong("sampleCount", count);
                    writer.writeDouble("percentage", percentage);
                    writer.endArrayItem();
                }
            }
            writer.endArray();
        }
        writer.endObject();
        writer.endArrayItem();
    }

    private Queue<OutboundFrame> getOutboundQueue(TcpServerConnection connection, boolean priority) {
        if (connection.getChannel() instanceof NioChannel nioChannel) {
            NioOutboundPipeline outboundPipeline = nioChannel.outboundPipeline();
            return priority ? outboundPipeline.priorityWriteQueue : outboundPipeline.writeQueue;
        }
        return EMPTY_QUEUE;
    }

    private String toKey(OutboundFrame packet) {
        if (packet instanceof Packet) {
            try {
                Object result = serializationService.toObject(packet);
                if (result == null) {
                    return "null";
                } else if (result instanceof Operation operation) {
                    return operation.getClass().getName();
                } else {
                    return result.getClass().getName();
                }
            } catch (Exception e) {
                logger.severe(e);
                return null;
            }
        }
        return packet.getClass().getName();
    }

    private static String formatSocketAddress(InetSocketAddress addr) {
        if (addr == null) {
            return "null";
        }
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }

    private static final class ConnectionSample {
        final String from;
        final String to;
        final boolean priority;
        final int packetCount;
        final int sampleCount;
        final ItemCounter<String> occurrences;

        ConnectionSample(String from, String to, boolean priority, int packetCount,
                         int sampleCount, ItemCounter<String> occurrences) {
            this.from = from;
            this.to = to;
            this.priority = priority;
            this.packetCount = packetCount;
            this.sampleCount = sampleCount;
            this.occurrences = occurrences;
        }
    }
}
