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

package com.hazelcast.internal.diagnostics;

import com.hazelcast.internal.util.LatencyDistribution;
import com.hazelcast.logging.Logger;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.CharArrayWriter;
import java.io.PrintWriter;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class OperationProfilerPluginTest extends AbstractDiagnosticsPluginTest {

    private OperationProfilerPlugin plugin;
    private ConcurrentMap<Class, LatencyDistribution> opLatencyDistribution;

    @Before
    public void setup() {
        Properties props = new Properties();
        props.put(OperationProfilerPlugin.PERIOD_SECONDS.getName(), "5");
        HazelcastProperties properties = new HazelcastProperties(props);
        opLatencyDistribution = new ConcurrentHashMap<>();
        plugin = new OperationProfilerPlugin(
                Logger.getLogger(OperationProfilerPlugin.class), opLatencyDistribution, properties);
        plugin.onStart();
    }

    @Test
    public void testGetPeriodMillis() {
        assertEquals(SECONDS.toMillis(5), plugin.getPeriodMillis());
    }

    @Test
    public void testRun_emptyDistribution_emitsEmptySection() {
        plugin.run(logWriter);
        assertContains("OperationsProfiler");
    }

    @Test
    public void testRun_withRecordedOperation_standardFormat() {
        LatencyDistribution dist = new LatencyDistribution();
        dist.recordNanos(500_000); // 500 µs
        opLatencyDistribution.put(String.class, dist);

        plugin.run(logWriter);

        assertContains("java.lang.String");
        assertContains("count=1");
        assertContains("latency-distribution");
    }

    @Test
    public void testRun_withRecordedOperation_jsonFormat() {
        LatencyDistribution dist = new LatencyDistribution();
        dist.recordNanos(500_000);
        opLatencyDistribution.put(String.class, dist);

        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterJsonImpl jsonWriter = new DiagnosticsLogWriterJsonImpl(false, null);
        jsonWriter.init(new PrintWriter(out));

        plugin.run(jsonWriter);

        String output = out.toString();
        assertTrue("Expected OperationsProfiler section", output.contains("\"OperationsProfiler\""));
        assertTrue("Expected operation class name as section", output.contains("\"java.lang.String\""));
        assertTrue("Expected count key", output.contains("\"count\":1"));
        assertTrue("Expected latency-distribution section", output.contains("\"latency-distribution\""));
    }

    @Test
    public void testRun_zeroCountDistribution_skippedInOutput() {
        LatencyDistribution dist = new LatencyDistribution();
        // no recordings — count == 0
        opLatencyDistribution.put(Integer.class, dist);

        plugin.run(logWriter);

        assertNotContains("java.lang.Integer");
    }
}
