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

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.internal.util.concurrent.ConcurrentItemCounter;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.CharArrayWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;

import static com.hazelcast.test.Accessors.getNodeEngineImpl;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class OperationThreadSamplerPluginTest extends AbstractDiagnosticsPluginTest {

    private OperationThreadSamplerPlugin plugin;

    @Before
    public void setup() {
        HazelcastInstance hz = createHazelcastInstance();
        plugin = new OperationThreadSamplerPlugin(getNodeEngineImpl(hz));
        plugin.onStart();
    }

    @Test
    public void testGetPeriodMillis() {
        assertEquals(0, plugin.getPeriodMillis());
    }

    @Test
    public void testGetIncludeName_defaultFalse() {
        assertFalse(plugin.getIncludeName());
    }

    @Test
    public void testGetSamplerPeriodMillis_default() {
        assertEquals(SECONDS.toMillis(0) + 100, plugin.getSamplerPeriodMillis());
    }

    @Test
    public void testRun_standardFormat_emitsMixedValueString() throws Exception {
        populateSamples(plugin, "com.hazelcast.map.GetOperation", 3);
        populateSamples(plugin, "com.hazelcast.map.PutOperation", 1);

        plugin.run(logWriter);

        String output = getContent();
        assertTrue("Expected operation name as key in STANDARD output", output.contains("com.hazelcast.map.GetOperation="));
        assertTrue("Expected sample count in STANDARD output", output.contains("3 "));
        assertTrue("Expected percentage sign in STANDARD output", output.contains("%"));
    }

    @Test
    public void testRun_jsonFormat_emitsStructuredEntries() throws Exception {
        populateSamples(plugin, "com.hazelcast.map.GetOperation", 3);
        populateSamples(plugin, "com.hazelcast.map.PutOperation", 1);

        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterJsonImpl jsonWriter = new DiagnosticsLogWriterJsonImpl(false, null);
        jsonWriter.init(new PrintWriter(out));

        plugin.run(jsonWriter);

        String output = out.toString();
        assertTrue("Expected \"operation\" key in JSON output", output.contains("\"operation\":"));
        assertTrue("Expected \"samples\" key in JSON output", output.contains("\"samples\":"));
        assertTrue("Expected \"percentage\" key in JSON output", output.contains("\"percentage\":"));
        assertFalse("Operation class name must not be a top-level JSON key", output.contains("\"com.hazelcast.map.GetOperation\":"));
    }

    @Test
    public void testRun_jsonFormat_zeroTotal_percentageIsZero() throws Exception {
        // samples with total == 0 means no entries, plugin runs with empty counters
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterJsonImpl jsonWriter = new DiagnosticsLogWriterJsonImpl(false, null);
        jsonWriter.init(new PrintWriter(out));

        plugin.run(jsonWriter);

        // with no samples, no structured entries, but section still emitted
        String output = out.toString();
        assertTrue("Expected OperationThreadSamples section", output.contains("OperationThreadSamples"));
    }

    @SuppressWarnings("unchecked")
    private static void populateSamples(OperationThreadSamplerPlugin plugin, String opName, long count)
            throws Exception {
        Field partitionField = OperationThreadSamplerPlugin.class.getDeclaredField("partitionSpecificSamples");
        partitionField.setAccessible(true);
        ConcurrentItemCounter<String> samples = (ConcurrentItemCounter<String>) partitionField.get(plugin);
        samples.add(opName, count);
    }
}
