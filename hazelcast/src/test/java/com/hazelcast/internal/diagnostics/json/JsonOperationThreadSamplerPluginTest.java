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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class JsonOperationThreadSamplerPluginTest extends HazelcastTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringWriter sw;
    private JsonEntryWriter entryWriter;
    private JsonOperationThreadSamplerPlugin plugin;
    private HazelcastInstance hz;

    @Before
    public void setUp() {
        Config config = new Config()
                .setProperty("hazelcast.diagnostics.operationthreadsamples.period.seconds", "1");
        hz = createHazelcastInstance(config);
        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        plugin = new JsonOperationThreadSamplerPlugin(nodeEngine);
        sw = new StringWriter();
        entryWriter = new JsonEntryWriter(new PrintWriter(sw));
    }

    @After
    public void tearDown() {
        plugin.onShutdown();
        if (hz != null) {
            hz.shutdown();
        }
    }

    @Test
    public void testPeriod_configuredFromProperty() {
        assertTrue(plugin.getPeriodMillis() > 0);
    }

    @Test
    public void testRun_producesOneLine() {
        plugin.run(entryWriter);
        String[] lines = sw.toString().split("\n");
        assertEquals(1, lines.length);
    }

    @Test
    public void testRun_schemaValid() {
        plugin.run(entryWriter);
        var errors = DiagnosticsSchemaValidator.get().validate(sw.toString().trim());
        assertTrue("Schema violations: " + errors, errors.isEmpty());
    }

    @Test
    public void testRun_envelope() throws Exception {
        plugin.run(entryWriter);
        JsonNode root = MAPPER.readTree(sw.toString().trim());
        assertEquals("OperationThreadSamples", root.get("name").asText());
        assertTrue(root.get("epoch").asLong() > 0);
        assertNotNull(root.get("content"));
    }

    @Test
    public void testRun_contentHasRequiredSections() throws Exception {
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertNotNull("Partition section required", content.get("Partition"));
        assertNotNull("Generic section required", content.get("Generic"));
    }
}
