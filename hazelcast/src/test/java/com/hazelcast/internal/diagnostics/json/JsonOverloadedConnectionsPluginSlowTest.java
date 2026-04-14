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
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.TcpIpConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.SlowTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;

import static com.hazelcast.test.Accessors.getNodeEngineImpl;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Slow test for {@link JsonOverloadedConnectionsPlugin} that requires real TCP networking.
 *
 * <p>Mock Hazelcast instances do not produce {@code TcpServerConnection} objects,
 * so this test uses {@link Hazelcast#newHazelcastInstance} with explicit TcpIp join
 * config to obtain real outbound connections.  It validates that the plugin's output
 * path (connection entries with sample data) produces schema-valid NDJSON.
 */
@RunWith(HazelcastSerialClassRunner.class)
@Category(SlowTest.class)
public class JsonOverloadedConnectionsPluginSlowTest extends HazelcastTestSupport {

    private HazelcastInstance local;
    private HazelcastInstance remote;
    private volatile boolean stop;

    @Before
    public void setUp() {
        Hazelcast.shutdownAll();

        Config config = new Config()
                .setProperty(JsonOverloadedConnectionsPlugin.PERIOD_SECONDS.getName(), "1")
                .setProperty(JsonOverloadedConnectionsPlugin.SAMPLES.getName(), "10")
                .setProperty(JsonOverloadedConnectionsPlugin.THRESHOLD.getName(), "2")
                .setProperty(ClusterProperty.IO_OUTPUT_THREAD_COUNT.getName(), "1")
                .setProperty(ClusterProperty.SOCKET_BIND_ANY.getName(), "false");

        NetworkConfig networkConfig = config.getNetworkConfig();
        networkConfig.getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        JoinConfig joinConfig = networkConfig.getJoin();
        joinConfig.getMulticastConfig().setEnabled(false);
        TcpIpConfig tcpIpConfig = joinConfig.getTcpIpConfig().setEnabled(true);
        tcpIpConfig.addMember("127.0.0.1:5701");
        tcpIpConfig.addMember("127.0.0.1:5702");

        local = Hazelcast.newHazelcastInstance(config);
        remote = Hazelcast.newHazelcastInstance(config);

        assertClusterSizeEventually(2, local, remote);
        warmUpPartitions(local, remote);
    }

    @After
    public void tearDown() {
        stop = true;
        Hazelcast.shutdownAll();
    }

    @Test
    public void testRun_schemaValidWhenConnectionQueueExceedsThreshold() {
        NodeEngineImpl nodeEngine = getNodeEngineImpl(local);
        JsonOverloadedConnectionsPlugin plugin = new JsonOverloadedConnectionsPlugin(
                nodeEngine.getLogger(JsonOverloadedConnectionsPlugin.class),
                nodeEngine.getProperties(),
                nodeEngine);

        // Drive traffic to the remote node so the outbound queue builds up
        String remoteKey = generateKeyOwnedBy(remote);
        spawn(() -> {
            IMap<String, String> map = local.getMap(getClass().getName());
            while (!stop) {
                map.getAsync(remoteKey);
            }
        });

        StringWriter sw = new StringWriter();
        JsonEntryWriter writer = new JsonEntryWriter(new PrintWriter(sw));

        assertTrueEventually(() -> {
            sw.getBuffer().setLength(0);
            plugin.run(writer);
            String output = sw.toString().trim();
            assertFalse("Plugin must emit when connection queue is overloaded", output.isEmpty());
            var errors = DiagnosticsSchemaValidator.get().validate(output);
            assertTrue("Schema violations: " + errors, errors.isEmpty());
        });
    }
}
