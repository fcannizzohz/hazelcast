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

import com.hazelcast.cluster.Member;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.internal.cluster.fd.DeadlineClusterFailureDetector;
import com.hazelcast.internal.cluster.impl.ClusterHeartbeatManager;
import com.hazelcast.internal.cluster.impl.ClusterServiceImpl;
import com.hazelcast.spi.impl.NodeEngineImpl;
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

import static com.hazelcast.test.Accessors.getNodeEngineImpl;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class MemberHeartbeatPluginTest extends AbstractDiagnosticsPluginTest {

    private MemberHeartbeatPlugin plugin;
    private ClusterServiceImpl clusterService;

    @Before
    public void setup() {
        HazelcastInstance hz = createHazelcastInstance();
        NodeEngineImpl nodeEngine = getNodeEngineImpl(hz);
        clusterService = (ClusterServiceImpl) nodeEngine.getClusterService();
        plugin = new MemberHeartbeatPlugin(
                nodeEngine.getLogger(MemberHeartbeatPlugin.class),
                clusterService,
                nodeEngine.getProperties());
        plugin.onStart();
    }

    @Test
    public void testGetPeriodMillis() {
        assertEquals(SECONDS.toMillis(10), plugin.getPeriodMillis());
    }

    @Test
    public void testGetMaxDeviationPercentage_default() {
        assertEquals(100, plugin.getMaxDeviationPercentage());
    }

    @Test
    public void testRun_singleMember_noDeviations_producesNoOutput() {
        // single-member cluster: the local member has no heartbeat tracked so no output expected
        plugin.run(logWriter);
        assertNotContains("MemberHeartbeats");
    }

    @Test
    public void testRun_standardFormat_withDeviation_includesDateTimeEntries() throws Exception {
        Member member = clusterService.getLocalMember();
        injectOldHeartbeat(member, 60);

        plugin.run(logWriter);

        assertContains("MemberHeartbeats");
        assertContains("deviation(%)");
        assertContains("lastHeartbeat(date-time)");
        assertContains("now(date-time)");
    }

    @Test
    public void testRun_jsonFormat_withDeviation_omitsDateTimeEntries() throws Exception {
        Member member = clusterService.getLocalMember();
        injectOldHeartbeat(member, 60);

        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterJsonImpl jsonWriter = new DiagnosticsLogWriterJsonImpl(false, null);
        jsonWriter.init(new PrintWriter(out));

        plugin.run(jsonWriter);

        String output = out.toString();
        assertTrue("Expected MemberHeartbeats section", output.contains("\"MemberHeartbeats\""));
        assertTrue("Expected deviation key", output.contains("\"deviation(%)\""));
        assertTrue("Expected lastHeartbeat(ms) key", output.contains("\"lastHeartbeat(ms)\""));
        assertTrue("Expected now(ms) key", output.contains("\"now(ms)\""));
        // DateTime string entries must be absent in JSON — epoch ms values are sufficient
        assertFalse("lastHeartbeat(date-time) must not appear in JSON",
                output.contains("\"lastHeartbeat(date-time)\""));
        assertFalse("now(date-time) must not appear in JSON",
                output.contains("\"now(date-time)\""));
    }

    /**
     * Injects a fake heartbeat timestamp that is {@code secondsAgo} seconds in the past
     * for the given member, simulating a large deviation.
     */
    @SuppressWarnings("unchecked")
    private void injectOldHeartbeat(Member member, long secondsAgo) throws Exception {
        ClusterHeartbeatManager heartbeatManager = clusterService.getClusterHeartbeatManager();

        Field detectorField = ClusterHeartbeatManager.class.getDeclaredField("heartbeatFailureDetector");
        detectorField.setAccessible(true);
        DeadlineClusterFailureDetector detector =
                (DeadlineClusterFailureDetector) detectorField.get(heartbeatManager);

        Field mapField = DeadlineClusterFailureDetector.class.getDeclaredField("heartbeatTimes");
        mapField.setAccessible(true);
        ConcurrentMap<Member, Long> heartbeatTimes = (ConcurrentMap<Member, Long>) mapField.get(detector);
        heartbeatTimes.put(member, System.currentTimeMillis() - SECONDS.toMillis(secondsAgo));
    }
}
