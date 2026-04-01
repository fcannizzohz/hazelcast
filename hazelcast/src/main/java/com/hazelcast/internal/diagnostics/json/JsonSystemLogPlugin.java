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

import com.hazelcast.cluster.Address;
import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipAdapter;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.core.LifecycleListener;
import com.hazelcast.instance.impl.NodeExtension;
import com.hazelcast.internal.cluster.ClusterVersionListener;
import com.hazelcast.internal.nio.Connection;
import com.hazelcast.internal.nio.ConnectionListenable;
import com.hazelcast.internal.nio.ConnectionListener;
import com.hazelcast.internal.server.ServerConnection;
import com.hazelcast.logging.ILogger;
import com.hazelcast.partition.MigrationListener;
import com.hazelcast.partition.MigrationState;
import com.hazelcast.partition.ReplicaMigrationEvent;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;
import com.hazelcast.version.Version;

import java.time.Instant;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that captures cluster and connection system events and
 * emits them as NDJSON lines.
 *
 * <p>Handles the same event types as
 * {@link com.hazelcast.internal.diagnostics.SystemLogPlugin}:
 * lifecycle changes, membership changes, connection events, partition migration
 * events (optionally), and cluster version changes.
 *
 * <p>Each event produces one NDJSON line. Events are queued by listeners and
 * drained on the scheduler thread during each {@link #run} call.
 *
 * @since 6.0
 */
public class JsonSystemLogPlugin extends JsonDiagnosticsPlugin {

    /** If this plugin is enabled. Mirrors {@code SystemLogPlugin.ENABLED}. */
    public static final HazelcastProperty ENABLED
            = new HazelcastProperty("hazelcast.diagnostics.systemlog.enabled", "true");

    /** Whether partition migration events are logged. Mirrors {@code SystemLogPlugin.LOG_PARTITIONS}. */
    public static final HazelcastProperty LOG_PARTITIONS
            = new HazelcastProperty("hazelcast.diagnostics.systemlog.partitions", "false");

    private static final long PERIOD_MILLIS = SECONDS.toMillis(1);

    private final Queue<Object> logQueue = new ConcurrentLinkedQueue<>();
    private final ConnectionListenable connectionObservable;
    private final HazelcastInstance hazelcastInstance;
    private final Address thisAddress;
    private final NodeExtension nodeExtension;
    private final boolean logPartitions;
    private final boolean enabled;

    private ClusterVersionListenerImpl clusterVersionListener;
    private ConnectionListenerImpl connectionListener;
    private MembershipListenerImpl membershipListener;
    private MigrationListenerImpl migrationListener;
    private LifecycleListenerImpl lifecycleListener;

    public JsonSystemLogPlugin(ILogger logger,
                               HazelcastProperties properties,
                               ConnectionListenable connectionObservable,
                               HazelcastInstance hazelcastInstance,
                               NodeExtension nodeExtension) {
        super(logger, properties);
        this.connectionObservable = connectionObservable;
        this.hazelcastInstance = hazelcastInstance;
        this.thisAddress = getThisAddress(hazelcastInstance);
        this.nodeExtension = nodeExtension;
        this.logPartitions = getBoolean(LOG_PARTITIONS);
        this.enabled = getBoolean(ENABLED);
    }

    @Override
    public long getPeriodMillis() {
        if (!enabled) {
            return DISABLED_PERIOD_MS;
        }
        return PERIOD_MILLIS;
    }

    @Override
    public void onStart() {
        if (connectionListener == null) {
            connectionListener = new ConnectionListenerImpl();
            connectionObservable.addConnectionListener(connectionListener);
        }
        if (membershipListener == null) {
            membershipListener = new MembershipListenerImpl();
            hazelcastInstance.getCluster().addMembershipListener(membershipListener);
        }
        if (logPartitions && migrationListener == null) {
            migrationListener = new MigrationListenerImpl();
            hazelcastInstance.getPartitionService().addMigrationListener(migrationListener);
        }
        if (lifecycleListener == null) {
            lifecycleListener = new LifecycleListenerImpl();
            hazelcastInstance.getLifecycleService().addLifecycleListener(lifecycleListener);
        }
        if (nodeExtension != null && clusterVersionListener == null) {
            clusterVersionListener = new ClusterVersionListenerImpl();
            nodeExtension.registerListener(clusterVersionListener);
        }
    }

    @Override
    public void onShutdown() {
        logQueue.clear();
    }

    @Override
    public void run(JsonEntryWriter writer) {
        for (;;) {
            Object item = logQueue.poll();
            if (item == null) {
                return;
            }
            long epoch = System.currentTimeMillis();
            if (item instanceof LifecycleEvent event) {
                renderLifecycle(writer, epoch, event);
            } else if (item instanceof MembershipEvent event) {
                renderMembership(writer, epoch, event);
            } else if (item instanceof MigrationState state) {
                renderMigrationState(writer, epoch, state);
            } else if (item instanceof ReplicaMigrationEvent event) {
                renderReplicaMigration(writer, epoch, event);
            } else if (item instanceof ConnectionEvent event) {
                renderConnection(writer, epoch, event);
            } else if (item instanceof Version version) {
                renderClusterVersion(writer, epoch, version);
            }
        }
    }

    // ------------------------------------------------------------------ renderers

    private static void renderLifecycle(JsonEntryWriter writer, long epoch, LifecycleEvent event) {
        writer.startEntry(epoch, "Lifecycle");
        writer.startArray("entries");
        writer.startArrayItem();
        writer.writeString("state", event.getState().name());
        writer.endArrayItem();
        writer.endArray();
        writer.endEntry();
    }

    private void renderMembership(JsonEntryWriter writer, long epoch, MembershipEvent event) {
        String name;
        if (event.getEventType() == MembershipEvent.MEMBER_ADDED) {
            name = "MemberAdded";
        } else if (event.getEventType() == MembershipEvent.MEMBER_REMOVED) {
            name = "MemberRemoved";
        } else {
            return;
        }
        writer.startEntry(epoch, name);
        writer.writeString("member", formatAddress(event.getMember().getAddress()));
        writer.startObject("Members");
        writer.startArray("entries");
        Set<Member> members = event.getMembers();
        if (members != null) {
            boolean first = true;
            for (Member member : members) {
                writer.startArrayItem();
                writer.writeString("address", formatAddress(member.getAddress()));
                writer.writeBoolean("isThis", member.getAddress().equals(thisAddress));
                writer.writeBoolean("isMaster", first);
                writer.endArrayItem();
                first = false;
            }
        }
        writer.endArray();
        writer.endObject();
        writer.endEntry();
    }

    private static void renderMigrationState(JsonEntryWriter writer, long epoch, MigrationState state) {
        writer.startEntry(epoch, "MigrationState");
        writer.writeString("startTime", Instant.ofEpochMilli(state.getStartTime()).toString());
        writer.writeLong("plannedMigrations", state.getPlannedMigrations());
        writer.writeLong("completedMigrations", state.getCompletedMigrations());
        writer.writeLong("remainingMigrations", state.getRemainingMigrations());
        writer.writeLong("totalElapsedTime(ms)", state.getTotalElapsedTime());
        writer.endEntry();
    }

    private static void renderReplicaMigration(JsonEntryWriter writer, long epoch, ReplicaMigrationEvent event) {
        String name = event.isSuccess() ? "MigrationCompleted" : "MigrationFailed";
        writer.startEntry(epoch, name);
        Member source = event.getSource();
        writer.writeString("source", source == null ? "null" : source.getAddress().toString());
        writer.writeString("destination", event.getDestination().getAddress().toString());
        writer.writeLong("partitionId", event.getPartitionId());
        writer.writeLong("replicaIndex", event.getReplicaIndex());
        writer.writeLong("elapsedTime(ms)", event.getElapsedTime());
        // embed nested MigrationState
        MigrationState ms = event.getMigrationState();
        writer.startObject("MigrationState");
        writer.writeString("startTime", Instant.ofEpochMilli(ms.getStartTime()).toString());
        writer.writeLong("plannedMigrations", ms.getPlannedMigrations());
        writer.writeLong("completedMigrations", ms.getCompletedMigrations());
        writer.writeLong("remainingMigrations", ms.getRemainingMigrations());
        writer.writeLong("totalElapsedTime(ms)", ms.getTotalElapsedTime());
        writer.endObject();
        writer.endEntry();
    }

    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    private static void renderConnection(JsonEntryWriter writer, long epoch, ConnectionEvent event) {
        String name = event.added ? "ConnectionAdded" : "ConnectionRemoved";
        writer.startEntry(epoch, name);
        Connection connection = event.connection;
        writer.startArray("entries");
        writer.startArrayItem();
        Address remoteAddr = connection.getRemoteAddress();
        writer.writeString("remoteAddress", remoteAddr != null ? formatAddress(remoteAddr) : "null");
        writer.endArrayItem();
        writer.endArray();
        if (connection instanceof ServerConnection serverConnection) {
            writer.writeString("type", serverConnection.getConnectionType());
        }
        writer.writeBoolean("isAlive", connection.isAlive());
        if (!event.added) {
            String closeReason = connection.getCloseReason();
            Throwable closeCause = connection.getCloseCause();
            if (closeReason == null && closeCause != null) {
                closeReason = closeCause.getMessage();
            }
            if (closeReason != null) {
                writer.writeString("closeReason", closeReason);
            }
            if (closeCause != null) {
                writer.startObject("CloseCause");
                writer.startArray("entries");
                writer.startArrayItem();
                writer.writeString("exceptionClass", closeCause.getClass().getName());
                writer.writeString("message", closeCause.getMessage());
                writer.endArrayItem();
                for (StackTraceElement element : closeCause.getStackTrace()) {
                    writer.startArrayItem();
                    writer.writeString("text", element.toString());
                    writer.endArrayItem();
                }
                writer.endArray();
                writer.endObject();
            }
        }
        writer.endEntry();
    }

    private static void renderClusterVersion(JsonEntryWriter writer, long epoch, Version version) {
        writer.startEntry(epoch, "ClusterVersionChanged");
        writer.startArray("entries");
        writer.startArrayItem();
        writer.writeString("version", version.toString());
        writer.endArrayItem();
        writer.endArray();
        writer.endEntry();
    }

    // ------------------------------------------------------------------ helpers

    private static Address getThisAddress(HazelcastInstance hazelcastInstance) {
        try {
            return hazelcastInstance.getCluster().getLocalMember().getAddress();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ event wrapper

    static final class ConnectionEvent {
        final boolean added;
        final Connection connection;

        ConnectionEvent(boolean added, Connection connection) {
            this.added = added;
            this.connection = connection;
        }
    }

    // ------------------------------------------------------------------ listeners

    private final class LifecycleListenerImpl implements LifecycleListener {
        @Override
        public void stateChanged(LifecycleEvent event) {
            logQueue.add(event);
        }
    }

    private final class ConnectionListenerImpl implements ConnectionListener {
        @Override
        public void connectionAdded(Connection connection) {
            logQueue.add(new ConnectionEvent(true, connection));
        }

        @Override
        public void connectionRemoved(Connection connection) {
            logQueue.add(new ConnectionEvent(false, connection));
        }
    }

    private final class MembershipListenerImpl extends MembershipAdapter {
        @Override
        public void memberAdded(MembershipEvent event) {
            logQueue.add(event);
        }

        @Override
        public void memberRemoved(MembershipEvent event) {
            logQueue.add(event);
        }
    }

    private final class MigrationListenerImpl implements MigrationListener {
        @Override
        public void migrationStarted(MigrationState state) {
            logQueue.add(state);
        }

        @Override
        public void migrationFinished(MigrationState state) {
            logQueue.add(state);
        }

        @Override
        public void replicaMigrationCompleted(ReplicaMigrationEvent event) {
            logQueue.add(event);
        }

        @Override
        public void replicaMigrationFailed(ReplicaMigrationEvent event) {
            logQueue.add(event);
        }
    }

    private final class ClusterVersionListenerImpl implements ClusterVersionListener {
        @Override
        public void onClusterVersionChange(Version newVersion) {
            logQueue.add(newVersion);
        }
    }
}
