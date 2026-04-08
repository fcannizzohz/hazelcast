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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.cluster.Address;
import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.internal.nio.Connection;
import com.hazelcast.partition.MigrationState;
import com.hazelcast.partition.ReplicaMigrationEvent;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.QuickTest;
import com.hazelcast.version.Version;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;

import static com.hazelcast.test.Accessors.getNodeEngineImpl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class JsonSystemLogPluginTest extends HazelcastTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringWriter sw;
    private JsonEntryWriter entryWriter;
    private JsonSystemLogPlugin plugin;
    private Queue<Object> logQueue;
    private HazelcastInstance hz;

    @Before
    public void setUp() throws Exception {
        hz = createHazelcastInstance();
        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        sw = new StringWriter();
        entryWriter = new JsonEntryWriter(new PrintWriter(sw));

        plugin = new JsonSystemLogPlugin(
                nodeEngine.getLogger(JsonSystemLogPlugin.class),
                nodeEngine.getProperties(),
                nodeEngine.getNode().getServer(),
                hz,
                nodeEngine.getNode().getNodeExtension());

        // Access the internal queue for direct test injection
        Field queueField = JsonSystemLogPlugin.class.getDeclaredField("logQueue");
        queueField.setAccessible(true);
        logQueue = (Queue<Object>) queueField.get(plugin);
    }

    @After
    public void tearDown() {
        if (hz != null) {
            hz.shutdown();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    public void testLifecycle_schemaValid() throws Exception {
        logQueue.add(new LifecycleEvent(LifecycleEvent.LifecycleState.STARTED));
        plugin.run(entryWriter);
        String line = sw.toString().trim();
        assertNotNull(line);
        assertTrue("Must produce output", !line.isEmpty());
        DiagnosticsSchemaValidator.get().assertValid(line);
    }

    @Test
    public void testLifecycle_content() throws Exception {
        logQueue.add(new LifecycleEvent(LifecycleEvent.LifecycleState.STARTED));
        plugin.run(entryWriter);
        JsonNode root = MAPPER.readTree(sw.toString().trim());
        assertEquals("Lifecycle", root.get("name").asText());
        JsonNode entries = root.get("content").get("entries");
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals("STARTED", entries.get(0).get("state").asText());
    }

    @Test
    public void testLifecycle_allStates() {
        for (LifecycleEvent.LifecycleState state : LifecycleEvent.LifecycleState.values()) {
            sw.getBuffer().setLength(0);
            logQueue.add(new LifecycleEvent(state));
            plugin.run(entryWriter);
            DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
        }
    }

    // ------------------------------------------------------------------ membership

    @Test
    public void testMemberAdded_schemaValid() throws Exception {
        MembershipEvent event = buildMembershipEvent(MembershipEvent.MEMBER_ADDED);
        logQueue.add(event);
        plugin.run(entryWriter);
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }

    @Test
    public void testMemberAdded_content() throws Exception {
        MembershipEvent event = buildMembershipEvent(MembershipEvent.MEMBER_ADDED);
        logQueue.add(event);
        plugin.run(entryWriter);
        JsonNode root = MAPPER.readTree(sw.toString().trim());
        assertEquals("MemberAdded", root.get("name").asText());
        JsonNode content = root.get("content");
        assertNotNull(content.get("member"));
        JsonNode members = content.get("Members").get("entries");
        assertTrue(members.isArray());
        assertTrue(members.size() >= 1);
        assertTrue(members.get(0).has("address"));
        assertTrue(members.get(0).has("isThis"));
        assertTrue(members.get(0).has("isMaster"));
    }

    @Test
    public void testMemberRemoved_schemaValid() throws Exception {
        MembershipEvent event = buildMembershipEvent(MembershipEvent.MEMBER_REMOVED);
        logQueue.add(event);
        plugin.run(entryWriter);
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }

    // ------------------------------------------------------------------ migration

    @Test
    public void testMigrationState_schemaValid() {
        MigrationState state = buildMigrationState();
        logQueue.add(state);
        plugin.run(entryWriter);
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }

    @Test
    public void testMigrationState_content() throws Exception {
        MigrationState state = buildMigrationState();
        logQueue.add(state);
        plugin.run(entryWriter);
        JsonNode root = MAPPER.readTree(sw.toString().trim());
        assertEquals("MigrationState", root.get("name").asText());
        JsonNode content = root.get("content");
        assertNotNull(content.get("startTime"));
        assertEquals(100, content.get("plannedMigrations").asInt());
        assertEquals(50, content.get("completedMigrations").asInt());
        assertEquals(50, content.get("remainingMigrations").asInt());
        assertEquals(1000, content.get("totalElapsedTime_ms").asInt());
    }

    @Test
    public void testReplicaMigrationCompleted_schemaValid() throws Exception {
        ReplicaMigrationEvent event = buildReplicaMigration(true);
        logQueue.add(event);
        plugin.run(entryWriter);
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }

    @Test
    public void testReplicaMigrationFailed_schemaValid() throws Exception {
        ReplicaMigrationEvent event = buildReplicaMigration(false);
        logQueue.add(event);
        plugin.run(entryWriter);
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }

    // ------------------------------------------------------------------ cluster version

    @Test
    public void testClusterVersionChanged_schemaValid() {
        logQueue.add(Version.of(5, 5));
        plugin.run(entryWriter);
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }

    @Test
    public void testClusterVersionChanged_content() throws Exception {
        logQueue.add(Version.of(5, 5));
        plugin.run(entryWriter);
        JsonNode root = MAPPER.readTree(sw.toString().trim());
        assertEquals("ClusterVersionChanged", root.get("name").asText());
        assertEquals("5.5", root.get("content").get("entries").get(0).get("version").asText());
    }

    // ------------------------------------------------------------------ connection

    @Test
    public void testConnectionAdded_schemaValid() {
        Connection conn = mockConnection(true, null, null);
        logQueue.add(new JsonSystemLogPlugin.ConnectionEvent(true, conn));
        plugin.run(entryWriter);
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }

    @Test
    public void testConnectionRemoved_schemaValid() {
        Connection conn = mockConnection(false, "timeout", null);
        logQueue.add(new JsonSystemLogPlugin.ConnectionEvent(false, conn));
        plugin.run(entryWriter);
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }

    @Test
    public void testConnectionRemoved_withCause_schemaValid() {
        RuntimeException cause = new RuntimeException("connection reset");
        Connection conn = mockConnection(false, null, cause);
        logQueue.add(new JsonSystemLogPlugin.ConnectionEvent(false, conn));
        plugin.run(entryWriter);
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }

    // ------------------------------------------------------------------ queue drain

    @Test
    public void testRun_drainsMultipleEvents() {
        logQueue.add(new LifecycleEvent(LifecycleEvent.LifecycleState.STARTED));
        logQueue.add(new LifecycleEvent(LifecycleEvent.LifecycleState.SHUTTING_DOWN));
        plugin.run(entryWriter);
        String[] lines = sw.toString().split("\n");
        assertEquals(2, lines.length);
        for (String line : lines) {
            DiagnosticsSchemaValidator.get().assertValid(line);
        }
    }

    @Test
    public void testPeriod_enabled() {
        assertTrue(plugin.getPeriodMillis() > 0);
    }

    // ------------------------------------------------------------------ builders

    private MembershipEvent buildMembershipEvent(int type) throws UnknownHostException {
        Member member = mock(Member.class);
        Address addr = new Address("127.0.0.1", 5701);
        when(member.getAddress()).thenReturn(addr);

        Set<Member> members = new LinkedHashSet<>();
        members.add(member);

        MembershipEvent event = mock(MembershipEvent.class);
        when(event.getEventType()).thenReturn(type);
        when(event.getMember()).thenReturn(member);
        when(event.getMembers()).thenReturn(members);
        return event;
    }

    private static MigrationState buildMigrationState() {
        MigrationState state = mock(MigrationState.class);
        when(state.getStartTime()).thenReturn(System.currentTimeMillis());
        when(state.getPlannedMigrations()).thenReturn(100);
        when(state.getCompletedMigrations()).thenReturn(50);
        when(state.getRemainingMigrations()).thenReturn(50);
        when(state.getTotalElapsedTime()).thenReturn(1000L);
        return state;
    }

    private static ReplicaMigrationEvent buildReplicaMigration(boolean success) throws UnknownHostException {
        // Build all dependent mocks before starting stubs to avoid Mockito UnfinishedStubbing
        MigrationState migState = buildMigrationState();
        Member dest = mock(Member.class);
        when(dest.getAddress()).thenReturn(new Address("127.0.0.1", 5702));

        ReplicaMigrationEvent event = mock(ReplicaMigrationEvent.class);
        when(event.isSuccess()).thenReturn(success);
        when(event.getSource()).thenReturn(null);
        when(event.getDestination()).thenReturn(dest);
        when(event.getPartitionId()).thenReturn(7);
        when(event.getReplicaIndex()).thenReturn(1);
        when(event.getElapsedTime()).thenReturn(42L);
        when(event.getMigrationState()).thenReturn(migState);
        return event;
    }

    private static Connection mockConnection(boolean alive, String closeReason, Throwable closeCause) {
        Connection conn = mock(Connection.class);
        when(conn.toString()).thenReturn("Connection[127.0.0.1:5701]");
        when(conn.isAlive()).thenReturn(alive);
        when(conn.getCloseReason()).thenReturn(closeReason);
        when(conn.getCloseCause()).thenReturn(closeCause);
        return conn;
    }
}
