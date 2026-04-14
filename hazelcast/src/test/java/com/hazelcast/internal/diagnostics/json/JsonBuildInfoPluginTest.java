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
import com.hazelcast.instance.BuildInfoProvider;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class JsonBuildInfoPluginTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringWriter sw;
    private JsonEntryWriter entryWriter;
    private JsonBuildInfoPlugin plugin;

    @Before
    public void setUp() {
        sw = new StringWriter();
        entryWriter = new JsonEntryWriter(new PrintWriter(sw));
        ILogger logger = mock(ILogger.class);
        HazelcastProperties props = new HazelcastProperties(new Properties());
        plugin = new JsonBuildInfoPlugin(logger, props);
    }

    @Test
    public void testPeriod_runOnce() {
        assertEquals(JsonDiagnosticsPlugin.RUN_ONCE_PERIOD_MS, plugin.getPeriodMillis());
    }

    @Test
    public void testRun_producesOneLine() {
        plugin.run(entryWriter);
        String output = sw.toString();
        String[] lines = output.split("\n");
        assertEquals(1, lines.length);
    }

    @Test
    public void testRun_schemaValid() throws Exception {
        plugin.run(entryWriter);
        String line = sw.toString().trim();
        var errors = DiagnosticsSchemaValidator.get().validate(line);
        assertTrue("Schema violations: " + errors, errors.isEmpty());
    }

    @Test
    public void testRun_envelope() throws Exception {
        plugin.run(entryWriter);
        JsonNode root = MAPPER.readTree(sw.toString().trim());
        assertEquals("BuildInfo", root.get("name").asText());
        assertTrue(root.get("epoch").asLong() > 0);
        assertNotNull(root.get("content"));
    }

    @Test
    public void testRun_requiredFields() throws Exception {
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertNotNull(content.get("Build"));
        assertNotNull(content.get("BuildNumber"));
        assertNotNull(content.get("Revision"));
        assertNotNull(content.get("Version"));
        assertNotNull(content.get("SerialVersion"));
        assertNotNull(content.get("Enterprise"));
    }

    @Test
    public void testRun_versionMatchesBuildInfo() throws Exception {
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertEquals(BuildInfoProvider.getBuildInfo().getVersion(), content.get("Version").asText());
    }

    @Test
    public void testRun_buildNumberIsInteger() throws Exception {
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertTrue("BuildNumber must be an integer node", content.get("BuildNumber").isIntegralNumber());
    }

    @Test
    public void testRun_enterpriseIsBoolean() throws Exception {
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertTrue("Enterprise must be a boolean node", content.get("Enterprise").isBoolean());
    }

    @Test
    public void testRun_serialVersionIsString() throws Exception {
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertTrue("SerialVersion must be a string node", content.get("SerialVersion").isTextual());
    }

    @Test
    public void testRun_noUpstreamRevisionWhenNull() throws Exception {
        // BuildInfoProvider may or may not have upstream. We just verify the output parses.
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        if (BuildInfoProvider.getBuildInfo().getUpstreamBuildInfo() == null) {
            assertFalse("UpstreamRevision should be absent when upstream is null",
                    content.has("UpstreamRevision"));
        } else {
            assertTrue("UpstreamRevision should be present when upstream exists",
                    content.has("UpstreamRevision"));
        }
    }
}
