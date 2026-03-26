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

import com.hazelcast.cluster.Address;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.operationservice.impl.InvocationMonitor;
import com.hazelcast.spi.impl.operationservice.impl.OperationServiceImpl;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.CharArrayWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.hazelcast.test.Accessors.getNodeEngineImpl;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class OperationHeartbeatPluginTest extends AbstractDiagnosticsPluginTest {

    private OperationHeartbeatPlugin plugin;
    private ConcurrentMap<Address, AtomicLong> heartbeatPerMember;

    @Before
    public void setup() throws Exception {
        HazelcastInstance hz = createHazelcastInstance();
        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        OperationServiceImpl operationService = nodeEngine.getOperationService();
        InvocationMonitor invocationMonitor = operationService.getInvocationMonitor();

        plugin = new OperationHeartbeatPlugin(
                nodeEngine.getLogger(OperationHeartbeatPlugin.class),
                invocationMonitor,
                nodeEngine.getProperties());
        plugin.onStart();

        Field field = InvocationMonitor.class.getDeclaredField("heartbeatPerMember");
        field.setAccessible(true);
        heartbeatPerMember = (ConcurrentMap<Address, AtomicLong>) field.get(invocationMonitor);
    }

    @Test
    public void testGetPeriodMillis() {
        assertEquals(SECONDS.toMillis(10), plugin.getPeriodMillis());
    }

    @Test
    public void testGetMaxDeviationPercentage_default() {
        assertEquals(33, plugin.getMaxDeviationPercentage());
    }

    @Test
    public void testRun_noDeviations_producesNoOutput() {
        plugin.run(logWriter);
        assertNotContains("OperationHeartbeat");
    }

    @Test
    public void testRun_withExcessiveDeviation_standardFormat() throws Exception {
        Address member = new Address("127.0.0.1", 5701);
        // set last heartbeat to long ago (1 minute) so deviation is large
        heartbeatPerMember.put(member, new AtomicLong(System.currentTimeMillis() - SECONDS.toMillis(60)));

        plugin.run(logWriter);

        assertContains("OperationHeartbeat");
        assertContains("deviation(%)");
    }

    @Test
    public void testRun_withExcessiveDeviation_jsonFormat() throws Exception {
        Address member = new Address("127.0.0.1", 5701);
        heartbeatPerMember.put(member, new AtomicLong(System.currentTimeMillis() - SECONDS.toMillis(60)));

        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterJsonImpl jsonWriter = new DiagnosticsLogWriterJsonImpl(false, null);
        jsonWriter.init(new PrintWriter(out));

        plugin.run(jsonWriter);

        String output = out.toString();
        assertTrue("Expected OperationHeartbeat in JSON output", output.contains("\"OperationHeartbeat\""));
        assertTrue("Expected deviation key in JSON", output.contains("\"deviation(%)\""));
    }
}
