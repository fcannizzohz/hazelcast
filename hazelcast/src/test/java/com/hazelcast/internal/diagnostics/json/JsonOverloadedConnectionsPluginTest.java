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
 * Tests for {@link JsonOverloadedConnectionsPlugin}.
 *
 * <p>In a single-node cluster with no overloaded connections, the plugin should
 * emit nothing because no connection queue exceeds the configured threshold.
 * Schema validation of the actual output path is covered by
 * {@link JsonOverloadedConnectionsPluginSlowTest}, which requires real TCP networking.
 */
@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class JsonOverloadedConnectionsPluginTest extends HazelcastTestSupport {

    private StringWriter sw;
    private JsonEntryWriter entryWriter;
    private JsonOverloadedConnectionsPlugin plugin;
    private HazelcastInstance hz;

    @Before
    public void setUp() {
        Config config = new Config()
                .setProperty("hazelcast.diagnostics.overloaded.connections.period.seconds", "1")
                .setProperty("hazelcast.diagnostics.overloaded.connections.threshold", "10000");
        hz = createHazelcastInstance(config);
        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        plugin = new JsonOverloadedConnectionsPlugin(
                nodeEngine.getLogger(JsonOverloadedConnectionsPlugin.class),
                nodeEngine.getProperties(),
                nodeEngine);
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
    public void testPeriod_configuredFromProperty() {
        assertTrue(plugin.getPeriodMillis() > 0);
    }

    @Test
    public void testRun_noOutputWhenNoOverloadedConnections() {
        plugin.run(entryWriter);
        assertTrue("Plugin must not emit when no connections are overloaded", sw.toString().isEmpty());
    }
}
