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
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class JsonSystemPropertiesPluginTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringWriter sw;
    private JsonEntryWriter entryWriter;
    private JsonSystemPropertiesPlugin plugin;

    @Before
    public void setUp() {
        sw = new StringWriter();
        entryWriter = new JsonEntryWriter(new PrintWriter(sw));
        ILogger logger = mock(ILogger.class);
        HazelcastProperties props = new HazelcastProperties(new Properties());
        plugin = new JsonSystemPropertiesPlugin(logger, props);
        plugin.onStart();
    }

    @Test
    public void testPeriod_runOnce() {
        assertEquals(JsonDiagnosticsPlugin.RUN_ONCE_PERIOD_MS, plugin.getPeriodMillis());
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
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }

    @Test
    public void testRun_envelope() throws Exception {
        plugin.run(entryWriter);
        JsonNode root = MAPPER.readTree(sw.toString().trim());
        assertEquals("SystemProperties", root.get("name").asText());
        assertTrue(root.get("epoch").asLong() > 0);
        assertNotNull(root.get("content"));
    }

    @Test
    public void testRun_contentIsStringMap() throws Exception {
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        // all values must be strings (stringMap schema)
        content.fields().forEachRemaining(entry ->
                assertTrue("Value for key '" + entry.getKey() + "' must be a string",
                        entry.getValue().isTextual() || entry.getValue().isNull()));
    }

    @Test
    public void testRun_containsJavaOsVersion() throws Exception {
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertNotNull("os.name should be present", content.get("os.name"));
        assertNotNull("java.version should be present", content.get("java.version"));
    }

    @Test
    public void testRun_containsJvmArgs() throws Exception {
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertNotNull("java.vm.args should be present", content.get(JsonSystemPropertiesPlugin.JVM_ARGS_KEY));
    }

    @Test
    public void testRun_excludesJavaAwtProps() throws Exception {
        System.setProperty("java.awt.test.prop", "testvalue");
        try {
            plugin.run(entryWriter);
            JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
            assertTrue("java.awt.* properties must be excluded",
                    !content.has("java.awt.test.prop"));
        } finally {
            System.clearProperty("java.awt.test.prop");
        }
    }

    @Test
    public void testRun_excludesNonRelevantProps() throws Exception {
        System.setProperty("myapp.custom.property", "irrelevant");
        try {
            plugin.run(entryWriter);
            JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
            assertTrue("custom properties must be excluded",
                    !content.has("myapp.custom.property"));
        } finally {
            System.clearProperty("myapp.custom.property");
        }
    }
}
