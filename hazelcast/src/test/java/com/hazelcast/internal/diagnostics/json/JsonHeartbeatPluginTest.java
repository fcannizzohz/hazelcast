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

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.operationservice.impl.InvocationMonitor;
import com.hazelcast.spi.impl.operationservice.impl.OperationServiceImpl;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;

import static com.hazelcast.test.Accessors.getNodeEngineImpl;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link JsonOperationHeartbeatPlugin} and {@link JsonMemberHeartbeatPlugin}.
 *
 * <p>These plugins only emit output when heartbeat deviations are detected.
 * In a running single-node cluster without induced deviations, they should emit
 * nothing (the deviation threshold is deliberately low by default for operations
 * heartbeat but members see themselves so no cross-member heartbeat timing is
 * measured).
 */
@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class JsonHeartbeatPluginTest extends HazelcastTestSupport {

    private StringWriter sw;
    private JsonEntryWriter entryWriter;
    private HazelcastInstance hz;

    @Before
    public void setUp() {
        Config config = new Config()
                .setProperty("hazelcast.diagnostics.operation-heartbeat.seconds", "10")
                .setProperty("hazelcast.diagnostics.member-heartbeat.period.seconds", "10");
        hz = createHazelcastInstance(config);
        sw = new StringWriter();
        entryWriter = new JsonEntryWriter(new PrintWriter(sw));
    }

    @After
    public void tearDown() {
        if (hz != null) {
            hz.shutdown();
        }
    }

    @Test
    public void testOperationHeartbeat_period() {
        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        InvocationMonitor invocationMonitor =
                ((OperationServiceImpl) nodeEngine.getOperationService()).getInvocationMonitor();
        JsonOperationHeartbeatPlugin plugin = new JsonOperationHeartbeatPlugin(
                nodeEngine.getLogger(JsonOperationHeartbeatPlugin.class),
                nodeEngine.getProperties(),
                invocationMonitor);
        assertTrue(plugin.getPeriodMillis() > 0);
    }

    @Test
    public void testOperationHeartbeat_noOutputWhenNoDeviation() {
        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        InvocationMonitor invocationMonitor =
                ((OperationServiceImpl) nodeEngine.getOperationService()).getInvocationMonitor();
        // Set very high deviation threshold so nothing is reported
        Config cfg = new Config()
                .setProperty("hazelcast.diagnostics.operation-heartbeat.max-deviation-percentage", "10000");
        JsonOperationHeartbeatPlugin plugin = new JsonOperationHeartbeatPlugin(
                nodeEngine.getLogger(JsonOperationHeartbeatPlugin.class),
                new com.hazelcast.spi.properties.HazelcastProperties(cfg.getProperties()),
                invocationMonitor);
        plugin.run(entryWriter);
        assertTrue("Plugin must not emit when no deviation detected", sw.toString().isEmpty());
    }

    @Test
    public void testMemberHeartbeat_period() {
        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        JsonMemberHeartbeatPlugin plugin = new JsonMemberHeartbeatPlugin(
                nodeEngine.getLogger(JsonMemberHeartbeatPlugin.class),
                nodeEngine.getProperties(),
                nodeEngine.getClusterService());
        assertTrue(plugin.getPeriodMillis() > 0);
    }

    @Test
    public void testMemberHeartbeat_noOutputWhenNoDeviation() {
        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        // Set very high deviation threshold so nothing is reported
        Config cfg = new Config()
                .setProperty("hazelcast.diagnostics.member-heartbeat.max-deviation-percentage", "10000");
        JsonMemberHeartbeatPlugin plugin = new JsonMemberHeartbeatPlugin(
                nodeEngine.getLogger(JsonMemberHeartbeatPlugin.class),
                new com.hazelcast.spi.properties.HazelcastProperties(cfg.getProperties()),
                nodeEngine.getClusterService());
        plugin.run(entryWriter);
        assertTrue("Plugin must not emit when no deviation detected", sw.toString().isEmpty());
    }
}
