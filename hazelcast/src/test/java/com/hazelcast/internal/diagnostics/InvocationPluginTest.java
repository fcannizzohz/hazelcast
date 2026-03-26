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

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.internal.util.ItemCounter;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.impl.operation.EntryOperation;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.CharArrayWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.Map;

import static com.hazelcast.test.Accessors.getNodeEngineImpl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class InvocationPluginTest extends AbstractDiagnosticsPluginTest {

    private InvocationSamplePlugin plugin;
    private HazelcastInstance hz;

    @Before
    public void setup() {
        Config config = new Config()
                .setProperty(InvocationSamplePlugin.SAMPLE_PERIOD_SECONDS.getName(), "1")
                .setProperty(InvocationSamplePlugin.SLOW_THRESHOLD_SECONDS.getName(), "5");

        hz = createHazelcastInstance(config);

        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        plugin = new InvocationSamplePlugin(nodeEngine.getLogger(InvocationSamplePlugin.class),
                nodeEngine.getOperationService().getInvocationRegistry(), nodeEngine.getProperties());
        plugin.onStart();
    }

    @Test
    public void testGetPeriodMillis() {
        assertEquals(1000, plugin.getPeriodMillis());
    }

    @Test
    public void testRun() {
        spawn(() -> {
            hz.getMap("foo").executeOnKey(randomString(), new SlowEntryProcessor());
        });

        assertTrueEventually(() -> {
            plugin.run(logWriter);

            assertContains(EntryOperation.class.getName());
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testHistory_jsonFormat_renderOccurrencesProducesStructuredEntries() throws Exception {
        // Populate occurrences directly to avoid needing a slow pending invocation
        Field occurrencesField = InvocationSamplePlugin.class.getDeclaredField("occurrences");
        occurrencesField.setAccessible(true);
        ItemCounter<String> occurrences = (ItemCounter<String>) occurrencesField.get(plugin);
        occurrences.add("com.hazelcast.map.impl.operation.GetOperation", 3);
        occurrences.add("com.hazelcast.map.impl.operation.PutOperation", 5);

        CharArrayWriter jsonOut = new CharArrayWriter();
        DiagnosticsLogWriterJsonImpl jsonWriter = new DiagnosticsLogWriterJsonImpl(false, null);
        jsonWriter.init(new PrintWriter(jsonOut));
        plugin.run(jsonWriter);

        String json = jsonOut.toString();
        assertTrue("operation key expected in JSON History section",
                json.contains("\"operation\":\"com.hazelcast.map.impl.operation.GetOperation\""));
        assertTrue("samples key expected in JSON History section",
                json.contains("\"samples\":3") || json.contains("\"samples\":5"));
    }

    static class SlowEntryProcessor implements EntryProcessor {
        @Override
        public Object process(Map.Entry entry) {
            try {
                Thread.sleep(100000);
            } catch (InterruptedException ignored) {
            }
            return null;
        }

        @Override
        public EntryProcessor getBackupProcessor() {
            return null;
        }
    }
}
