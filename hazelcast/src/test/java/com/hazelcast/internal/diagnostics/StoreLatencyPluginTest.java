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

import com.hazelcast.internal.diagnostics.StoreLatencyPlugin.LatencyProbe;
import com.hazelcast.internal.diagnostics.StoreLatencyPlugin.LatencyProbeImpl;
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

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class StoreLatencyPluginTest extends AbstractDiagnosticsPluginTest {

    private StoreLatencyPlugin plugin;

    @Before
    public void setup() {
        Properties p = new Properties();
        p.put(StoreLatencyPlugin.PERIOD_SECONDS, "1");
        HazelcastProperties properties = new HazelcastProperties(p);
        plugin = new StoreLatencyPlugin(Logger.getLogger(StoreLatencyPlugin.class), properties);
        plugin.onStart();
    }

    @Test
    public void getProbe() {
        LatencyProbe probe = plugin.newProbe("foo", "queue", "somemethod");
        assertNotNull(probe);
    }

    @Test
    public void getProbe_whenSameProbeRequestedMoreThanOnce() {
        LatencyProbe probe1 = plugin.newProbe("foo", "queue", "somemethod");
        LatencyProbe probe2 = plugin.newProbe("foo", "queue", "somemethod");
        assertSame(probe1, probe2);
    }

    @Test
    public void testMaxMicros() {
        LatencyProbeImpl probe = (LatencyProbeImpl) plugin.newProbe("foo", "queue", "somemethod");
        probe.recordValue(MICROSECONDS.toNanos(10));
        probe.recordValue(MICROSECONDS.toNanos(1000));
        probe.recordValue(MICROSECONDS.toNanos(4));

        assertEquals(1000, probe.distribution.maxMicros());
    }

    @Test
    public void testCount() {
        LatencyProbeImpl probe = (LatencyProbeImpl) plugin.newProbe("foo", "queue", "somemethod");
        probe.recordValue(MICROSECONDS.toNanos(10));
        probe.recordValue(MICROSECONDS.toNanos(10));
        probe.recordValue(MICROSECONDS.toNanos(10));

        assertEquals(3, probe.distribution.count());
    }

    @Test
    public void testTotalMicros() {
        LatencyProbeImpl probe = (LatencyProbeImpl) plugin.newProbe("foo", "queue", "somemethod");
        probe.recordValue(MICROSECONDS.toNanos(10));
        probe.recordValue(MICROSECONDS.toNanos(20));
        probe.recordValue(MICROSECONDS.toNanos(30));

        assertEquals(60, probe.distribution.totalMicros());
    }

    @Test
    public void render() {
        LatencyProbeImpl probe = (LatencyProbeImpl) plugin.newProbe("foo", "queue", "somemethod");
        probe.recordValue(MICROSECONDS.toNanos(100));
        probe.recordValue(MICROSECONDS.toNanos(200));
        probe.recordValue(MICROSECONDS.toNanos(200));
        probe.recordValue(MICROSECONDS.toNanos(300));

        plugin.run(logWriter);

        assertContains("foo");
        assertContains("queue");
        assertContains("somemethod");
        assertContains("count=4");
        assertContains("totalTime(us)=800");
        assertContains("avg(us)=200");
        assertContains("max(us)=300");
        assertContains("64..127us=1");
        assertContains("128..255us=2");
        assertContains("256..511us=1");
    }

    @Test
    public void max_latency_goes_right_distribution_bucket() {
        LatencyProbeImpl probe = (LatencyProbeImpl) plugin.newProbe("foo", "queue", "somemethod");
        probe.recordValue(MICROSECONDS.toNanos(4));

        plugin.run(logWriter);

        assertContains("4..7us=1");
    }

    @Test
    public void render_jsonFormat_emitsStructuredOutput() {
        LatencyProbeImpl probe = (LatencyProbeImpl) plugin.newProbe("MapService", "employees", "load");
        probe.recordValue(MICROSECONDS.toNanos(100));
        probe.recordValue(MICROSECONDS.toNanos(200));

        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterJsonImpl jsonWriter = new DiagnosticsLogWriterJsonImpl(false, null);
        jsonWriter.init(new PrintWriter(out));

        plugin.run(jsonWriter);

        String output = out.toString();
        assertTrue("Expected service name section", output.contains("\"MapService\""));
        assertTrue("Expected data structure name section", output.contains("\"employees\""));
        assertTrue("Expected method name section", output.contains("\"load\""));
        assertTrue("Expected count key", output.contains("\"count\":2"));
        assertTrue("Expected latency-distribution section", output.contains("\"latency-distribution\""));
    }

    @Test
    public void render_jsonFormat_allStatsFieldsPresent() {
        LatencyProbeImpl probe = (LatencyProbeImpl) plugin.newProbe("Svc", "ds", "get");
        probe.recordValue(MICROSECONDS.toNanos(100));
        probe.recordValue(MICROSECONDS.toNanos(300));

        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterJsonImpl jsonWriter = new DiagnosticsLogWriterJsonImpl(false, null);
        jsonWriter.init(new PrintWriter(out));

        plugin.run(jsonWriter);

        String output = out.toString();
        assertTrue("Expected count", output.contains("\"count\":2"));
        assertTrue("Expected totalTime(us)", output.contains("\"totalTime(us)\""));
        assertTrue("Expected avg(us)", output.contains("\"avg(us)\""));
        assertTrue("Expected max(us)", output.contains("\"max(us)\""));
    }

    @Test
    public void render_zeroCount_probeNotEmitted() {
        // probe registered but no values recorded: the service/instance sections are written
        // but no latency stats (count/distribution) should appear
        plugin.newProbe("Svc", "ds", "get");

        plugin.run(logWriter);

        assertNotContains("count=");
        assertNotContains("latency-distribution");
    }

    @Test
    public void render_multipleProbesInSameService_allEmitted() {
        LatencyProbeImpl load = (LatencyProbeImpl) plugin.newProbe("MapService", "employees", "load");
        LatencyProbeImpl store = (LatencyProbeImpl) plugin.newProbe("MapService", "employees", "store");
        load.recordValue(MICROSECONDS.toNanos(50));
        store.recordValue(MICROSECONDS.toNanos(80));

        plugin.run(logWriter);

        assertContains("load");
        assertContains("store");
        assertContains("count=1");
    }
}
