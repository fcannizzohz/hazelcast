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
import com.hazelcast.internal.metrics.LongProbeFunction;
import com.hazelcast.internal.metrics.MetricsRegistry;
import com.hazelcast.internal.metrics.Probe;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.internal.metrics.MetricTarget.DIAGNOSTICS;
import static com.hazelcast.internal.metrics.ProbeLevel.MANDATORY;
import static com.hazelcast.test.Accessors.getNodeEngineImpl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class JsonMetricsPluginTest extends HazelcastTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringWriter sw;
    private JsonEntryWriter entryWriter;
    private JsonMetricsPlugin plugin;
    private MetricsRegistry metricsRegistry;
    private HazelcastInstance hz;

    @Before
    public void setUp() {
        Config config = new Config()
                .setProperty("hazelcast.diagnostics.metrics.period.seconds", "1");
        hz = createHazelcastInstance(config);
        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        metricsRegistry = nodeEngine.getMetricsRegistry();
        plugin = new JsonMetricsPlugin(
                nodeEngine.getLogger(JsonMetricsPlugin.class),
                nodeEngine.getProperties(),
                metricsRegistry);
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
        assertEquals(TimeUnit.SECONDS.toMillis(1), plugin.getPeriodMillis());
    }

    @Test
    public void testRun_producesMultipleLines() {
        plugin.run(entryWriter);
        String output = sw.toString();
        String[] lines = output.split("\n");
        assertTrue("Expected multiple metric lines, got: " + lines.length, lines.length > 1);
    }

    @Test
    public void testRun_allLinesSchemaValid() {
        plugin.run(entryWriter);
        for (String line : sw.toString().split("\n")) {
            if (!line.isEmpty()) {
                DiagnosticsSchemaValidator.get().assertValid(line);
            }
        }
    }

    @Test
    public void testRun_allLinesHaveNameMetric() throws Exception {
        plugin.run(entryWriter);
        for (String line : sw.toString().split("\n")) {
            if (!line.isEmpty()) {
                JsonNode root = MAPPER.readTree(line);
                assertEquals("Metric", root.get("name").asText());
            }
        }
    }

    @Test
    public void testRun_longMetricValue() throws Exception {
        metricsRegistry.registerStaticProbe(this, "testLongMetric", MANDATORY,
                (LongProbeFunction<JsonMetricsPluginTest>) source -> 42L);
        plugin.run(entryWriter);
        boolean found = false;
        for (String line : sw.toString().split("\n")) {
            if (!line.isEmpty()) {
                JsonNode content = MAPPER.readTree(line).get("content");
                if (content.has("[metric=testLongMetric]")) {
                    assertEquals(42L, content.get("[metric=testLongMetric]").asLong());
                    found = true;
                }
            }
        }
        assertTrue("Expected testLongMetric in output", found);
    }

    @Test
    public void testRun_excludedMetricNotPresent() throws Exception {
        metricsRegistry.registerStaticMetrics(new ExcludedProbeSource(), "excluded");
        plugin.run(entryWriter);
        for (String line : sw.toString().split("\n")) {
            if (!line.isEmpty()) {
                JsonNode content = MAPPER.readTree(line).get("content");
                assertFalse("Excluded metric must not appear",
                        content.has("[unit=count,metric=excluded.excludedMetric]"));
            }
        }
    }

    @Test
    public void testRun_eachLineHasOneMetric() throws Exception {
        plugin.run(entryWriter);
        for (String line : sw.toString().split("\n")) {
            if (!line.isEmpty()) {
                JsonNode content = MAPPER.readTree(line).get("content");
                assertEquals("Each Metric line must have exactly one field", 1, content.size());
            }
        }
    }

    private static final class ExcludedProbeSource {
        @Probe(name = "excludedMetric", excludedTargets = DIAGNOSTICS)
        private long excludedMetric = 99;
    }
}
