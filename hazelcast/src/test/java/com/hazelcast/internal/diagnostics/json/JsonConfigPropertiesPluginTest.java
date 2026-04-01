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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class JsonConfigPropertiesPluginTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringWriter sw;
    private JsonEntryWriter entryWriter;

    @Before
    public void setUp() {
        sw = new StringWriter();
        entryWriter = new JsonEntryWriter(new PrintWriter(sw));
    }

    private JsonConfigPropertiesPlugin buildPlugin(Properties sysProps, Map<String, String> pluginProps) {
        ILogger logger = mock(ILogger.class);
        HazelcastProperties props = new HazelcastProperties(sysProps);
        return new JsonConfigPropertiesPlugin(logger, props, pluginProps);
    }

    @Test
    public void testPeriod_runOnce() {
        JsonConfigPropertiesPlugin plugin = buildPlugin(new Properties(), Collections.emptyMap());
        assertEquals(JsonDiagnosticsPlugin.RUN_ONCE_PERIOD_MS, plugin.getPeriodMillis());
    }

    @Test
    public void testRun_producesOneLine() {
        JsonConfigPropertiesPlugin plugin = buildPlugin(new Properties(), Collections.emptyMap());
        plugin.run(entryWriter);
        String[] lines = sw.toString().split("\n");
        assertEquals(1, lines.length);
    }

    @Test
    public void testRun_schemaValid() {
        JsonConfigPropertiesPlugin plugin = buildPlugin(new Properties(), Collections.emptyMap());
        plugin.run(entryWriter);
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }

    @Test
    public void testRun_envelope() throws Exception {
        JsonConfigPropertiesPlugin plugin = buildPlugin(new Properties(), Collections.emptyMap());
        plugin.run(entryWriter);
        JsonNode root = MAPPER.readTree(sw.toString().trim());
        assertEquals("ConfigProperties", root.get("name").asText());
        assertTrue(root.get("epoch").asLong() > 0);
        assertNotNull(root.get("content"));
    }

    @Test
    public void testRun_hazelcastPropertiesIncluded() throws Exception {
        Properties sysProps = new Properties();
        sysProps.setProperty("hazelcast.diagnostics.enabled", "true");
        JsonConfigPropertiesPlugin plugin = buildPlugin(sysProps, Collections.emptyMap());
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertNotNull("hazelcast.diagnostics.enabled should be present",
                content.get("hazelcast.diagnostics.enabled"));
        assertEquals("true", content.get("hazelcast.diagnostics.enabled").asText());
    }

    @Test
    public void testRun_pluginPropertiesIncluded() throws Exception {
        Map<String, String> pluginProps = new HashMap<>();
        pluginProps.put("hazelcast.diagnostics.metric.level", "INFO");
        JsonConfigPropertiesPlugin plugin = buildPlugin(new Properties(), pluginProps);
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertNotNull("plugin property should be present",
                content.get("hazelcast.diagnostics.metric.level"));
        assertEquals("INFO", content.get("hazelcast.diagnostics.metric.level").asText());
    }

    @Test
    public void testRun_contentIsStringMap() throws Exception {
        Properties sysProps = new Properties();
        sysProps.setProperty("hazelcast.foo", "bar");
        JsonConfigPropertiesPlugin plugin = buildPlugin(sysProps, Collections.emptyMap());
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        content.fields().forEachRemaining(entry ->
                assertTrue("Value for '" + entry.getKey() + "' must be string",
                        entry.getValue().isTextual() || entry.getValue().isNull()));
    }

    @Test
    public void testRun_schemaValid_withProperties() {
        Properties sysProps = new Properties();
        sysProps.setProperty("hazelcast.diagnostics.enabled", "true");
        Map<String, String> pluginProps = new HashMap<>();
        pluginProps.put("hazelcast.diagnostics.metric.level", "INFO");
        JsonConfigPropertiesPlugin plugin = buildPlugin(sysProps, pluginProps);
        plugin.run(entryWriter);
        DiagnosticsSchemaValidator.get().assertValid(sw.toString().trim());
    }
}
