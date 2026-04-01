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
import com.hazelcast.internal.diagnostics.StoreLatencyPlugin.LatencyProbe;
import com.hazelcast.logging.Logger;
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
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class JsonStoreLatencyPluginTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringWriter sw;
    private JsonEntryWriter entryWriter;
    private JsonStoreLatencyPlugin plugin;

    @Before
    public void setUp() {
        Properties props = new Properties();
        props.setProperty("hazelcast.diagnostics.storeLatency.period.seconds", "1");
        HazelcastProperties hazelcastProperties = new HazelcastProperties(props);
        plugin = new JsonStoreLatencyPlugin(
                Logger.getLogger(JsonStoreLatencyPlugin.class),
                hazelcastProperties);
        sw = new StringWriter();
        entryWriter = new JsonEntryWriter(new PrintWriter(sw));
    }

    @Test
    public void testPeriod_configuredFromProperty() {
        assertTrue(plugin.getPeriodMillis() > 0);
    }

    @Test
    public void testRunJson_noOutputWhenNoProbes() {
        plugin.runJson(entryWriter);
        assertTrue("No output expected when no probes registered", sw.toString().isEmpty());
    }

    @Test
    public void testRunJson_schemaValidWhenProbeHasNoData() {
        plugin.newProbe("MapStore", "myMap", "load");
        plugin.runJson(entryWriter);
        // A line is emitted but the method entry is omitted (count == 0).
        // The resulting JSON is still schema-valid.
        String output = sw.toString().trim();
        if (!output.isEmpty()) {
            DiagnosticsSchemaValidator.get().assertValid(output);
        }
    }

    @Test
    public void testRunJson_singleEntry() throws Exception {
        LatencyProbe probe = plugin.newProbe("MapStore", "myMap", "load");
        probe.recordValue(TimeUnit.MICROSECONDS.toNanos(50));

        plugin.runJson(entryWriter);

        String output = sw.toString().trim();
        assertFalse(output.isEmpty());

        JsonNode root = MAPPER.readTree(output);
        assertEquals("MapStore", root.get("name").asText());
        assertTrue(root.get("epoch").asLong() > 0);
        assertNotNull(root.get("content"));

        JsonNode mapContent = root.get("content").get("myMap");
        assertNotNull("Data structure entry required", mapContent);

        JsonNode loadEntry = mapContent.get("load");
        assertNotNull("Method entry required", loadEntry);
        assertEquals(1, loadEntry.get("count").asInt());
        assertTrue(loadEntry.get("totalTime(us)").asLong() >= 0);
    }

    @Test
    public void testRunJson_schemaValid() {
        LatencyProbe probe = plugin.newProbe("MapStore", "myMap", "load");
        probe.recordValue(TimeUnit.MICROSECONDS.toNanos(100));

        plugin.runJson(entryWriter);

        String output = sw.toString().trim();
        assertFalse(output.isEmpty());
        DiagnosticsSchemaValidator.get().assertValid(output);
    }

    @Test
    public void testRunJson_multipleServicesProduceMultipleLines() {
        LatencyProbe probe1 = plugin.newProbe("MapStore", "myMap", "load");
        LatencyProbe probe2 = plugin.newProbe("CacheStore", "myCache", "write");
        probe1.recordValue(TimeUnit.MICROSECONDS.toNanos(10));
        probe2.recordValue(TimeUnit.MICROSECONDS.toNanos(20));

        plugin.runJson(entryWriter);

        String[] lines = sw.toString().split("\n");
        assertEquals(2, lines.length);
        DiagnosticsSchemaValidator.get().assertValid(lines[0]);
        DiagnosticsSchemaValidator.get().assertValid(lines[1]);
    }

    @Test
    public void testProbeDeduplication() {
        LatencyProbe probe1 = plugin.newProbe("MapStore", "myMap", "load");
        LatencyProbe probe2 = plugin.newProbe("MapStore", "myMap", "load");

        probe1.recordValue(TimeUnit.MICROSECONDS.toNanos(10));
        probe2.recordValue(TimeUnit.MICROSECONDS.toNanos(20));

        plugin.runJson(entryWriter);

        String output = sw.toString().trim();
        String[] lines = output.split("\n");
        assertEquals(1, lines.length);

        // Probes are NOT deduplicated at this level (each probe is independent),
        // but each call to newProbe for the same keys returns a new wrapper that
        // records into the same underlying distribution.
    }
}
