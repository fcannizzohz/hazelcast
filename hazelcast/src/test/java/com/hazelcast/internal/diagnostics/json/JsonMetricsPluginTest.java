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
import com.hazelcast.internal.metrics.ProbeUnit;
import com.hazelcast.internal.metrics.impl.MetricDescriptorImpl;
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
    public void testRun_producesExactlyOneLine() {
        plugin.run(entryWriter);
        String output = sw.toString().trim();
        String[] lines = output.split("\n");
        assertEquals("Expected exactly one Metric line per collection cycle", 1, lines.length);
    }

    @Test
    public void testRun_lineIsSchemaValid() {
        plugin.run(entryWriter);
        String line = sw.toString().trim();
        assertFalse("Expected non-empty output", line.isEmpty());
        var errors = DiagnosticsSchemaValidator.get().validate(line);
        assertTrue("Schema violations: " + errors, errors.isEmpty());
    }

    @Test
    public void testRun_lineHasNameMetric() throws Exception {
        plugin.run(entryWriter);
        String line = sw.toString().trim();
        JsonNode root = MAPPER.readTree(line);
        assertEquals("Metric", root.get("name").asText());
    }

    @Test
    public void testRun_singleLineContainsMultipleMetrics() throws Exception {
        plugin.run(entryWriter);
        String line = sw.toString().trim();
        JsonNode content = MAPPER.readTree(line).get("content");
        assertTrue("Single Metric line must contain more than one metric field", content.size() > 1);
    }

    @Test
    public void testRun_longMetricValue() throws Exception {
        metricsRegistry.registerStaticProbe(this, "testLongMetric", MANDATORY,
                (LongProbeFunction<JsonMetricsPluginTest>) source -> 42L);
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertTrue("Expected testLongMetric key in content", content.has("testLongMetric"));
        assertEquals(42L, content.get("testLongMetric").asLong());
    }

    @Test
    public void testRun_excludedMetricNotPresent() throws Exception {
        metricsRegistry.registerStaticMetrics(new ExcludedProbeSource(), "excluded");
        plugin.run(entryWriter);
        JsonNode content = MAPPER.readTree(sw.toString().trim()).get("content");
        assertFalse("Excluded metric must not appear",
                content.has("excluded.excludedMetric_count"));
    }

    // ---- buildKey unit tests -----------------------------------------------

    @Test
    public void testBuildKey_prefixAndMetricAndUnit() {
        MetricDescriptorImpl d = descriptor().withPrefix("map").withMetric("hits").withUnit(ProbeUnit.COUNT);
        assertEquals("map.hits_count", JsonMetricsPlugin.buildKey(d));
    }

    @Test
    public void testBuildKey_withDiscriminatorInsertsNameBetweenPrefixAndMetric() {
        MetricDescriptorImpl d = descriptor()
                .withPrefix("map")
                .withDiscriminator("name", "employees")
                .withMetric("hits")
                .withUnit(ProbeUnit.COUNT);
        assertEquals("map.employees.hits_count", JsonMetricsPlugin.buildKey(d));
    }

    @Test
    public void testBuildKey_discriminatorWithoutPrefix() {
        MetricDescriptorImpl d = descriptor()
                .withDiscriminator("name", "myMap")
                .withMetric("hits")
                .withUnit(ProbeUnit.COUNT);
        assertEquals("myMap.hits_count", JsonMetricsPlugin.buildKey(d));
    }

    @Test
    public void testBuildKey_noDiscriminatorNoPrefix() {
        MetricDescriptorImpl d = descriptor().withMetric("blockingWorkerCount").withUnit(ProbeUnit.COUNT);
        assertEquals("blockingWorkerCount_count", JsonMetricsPlugin.buildKey(d));
    }

    @Test
    public void testBuildKey_noUnit() {
        MetricDescriptorImpl d = descriptor().withPrefix("os").withMetric("processCpuLoad");
        assertEquals("os.processCpuLoad", JsonMetricsPlugin.buildKey(d));
    }

    private static MetricDescriptorImpl descriptor() {
        return new MetricDescriptorImpl(() -> null);
    }

    // ---- probe source -------------------------------------------------------

    private static final class ExcludedProbeSource {
        @Probe(name = "excludedMetric", excludedTargets = DIAGNOSTICS)
        private long excludedMetric = 99;
    }
}
