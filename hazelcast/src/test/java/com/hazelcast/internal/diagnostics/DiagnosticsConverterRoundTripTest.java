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
import com.hazelcast.cluster.Cluster;
import com.hazelcast.cluster.Member;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.instance.impl.Node;
import com.hazelcast.instance.impl.NodeState;
import com.hazelcast.internal.cluster.impl.ClusterHeartbeatManager;
import com.hazelcast.internal.cluster.impl.ClusterServiceImpl;
import com.hazelcast.internal.cluster.fd.DeadlineClusterFailureDetector;
import com.hazelcast.internal.management.dto.SlowOperationDTO;
import com.hazelcast.internal.metrics.MetricDescriptor;
import com.hazelcast.internal.metrics.MetricsRegistry;
import com.hazelcast.internal.metrics.collectors.MetricsCollector;
import com.hazelcast.internal.networking.nio.NioNetworking;
import com.hazelcast.internal.networking.nio.NioThread;
import com.hazelcast.internal.nio.ConnectionListenable;
import com.hazelcast.internal.util.LatencyDistribution;
import com.hazelcast.internal.util.concurrent.ConcurrentItemCounter;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.operationservice.impl.InvocationMonitor;
import com.hazelcast.spi.impl.operationservice.impl.InvocationRegistry;
import com.hazelcast.spi.impl.operationservice.impl.OperationServiceImpl;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.CharArrayWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.hazelcast.test.Accessors.getNodeEngineImpl;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the STANDARD → parseStandard → toJson → parseJson → toStandard round-trip
 * is lossless for each diagnostics plugin.
 * <p>
 * Plugins that write all numeric values via the typed {@code long}/{@code double} writer API
 * are asserted byte-for-byte (the reconstructed STANDARD string must equal the original).
 * Plugins that write some numeric values as plain strings (e.g. {@link BuildInfoPlugin},
 * {@link SystemPropertiesPlugin}, {@link ConfigPropertiesPlugin}) are only asserted for
 * JSON idempotency: converting to JSON twice must yield the same JSON.
 */
@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class DiagnosticsConverterRoundTripTest extends HazelcastTestSupport {

    private DiagnosticsLogConverter converter;
    private ILogger logger;
    private HazelcastProperties hazelcastProperties;
    private HazelcastInstance hz;
    private NodeEngineImpl nodeEngine;

    @Before
    public void setup() {
        converter = new DiagnosticsLogConverter();
        logger = Logger.getLogger(DiagnosticsConverterRoundTripTest.class);
        hazelcastProperties = new HazelcastProperties(new Properties());
        hz = createHazelcastInstance();
        nodeEngine = getNodeEngineImpl(hz);
    }

    // -------------------------------------------------------------------------
    // Byte-for-byte round-trip tests
    // -------------------------------------------------------------------------

    @Test
    public void testStoreLatencyPlugin() {
        StoreLatencyPlugin plugin = new StoreLatencyPlugin(logger, hazelcastProperties);
        plugin.newProbe("MapService", "employees", "get").recordValue(MICROSECONDS.toNanos(100));
        plugin.newProbe("MapService", "employees", "put").recordValue(MICROSECONDS.toNanos(200));
        assertByteForByteRoundTrip(writeStandard(plugin));
    }

    @Test
    public void testOperationProfilerPlugin() {
        ConcurrentMap<Class, LatencyDistribution> opLatency = new ConcurrentHashMap<>();
        LatencyDistribution dist = new LatencyDistribution();
        dist.recordNanos(500_000L);
        opLatency.put(String.class, dist);
        OperationProfilerPlugin plugin = new OperationProfilerPlugin(logger, opLatency, hazelcastProperties);
        assertByteForByteRoundTrip(writeStandard(plugin));
    }

    @Test
    public void testMetricsPlugin() {
        MetricsRegistry metricsRegistry = mock(MetricsRegistry.class);
        doAnswer(invocation -> {
            MetricsCollector collector = invocation.getArgument(0);
            MetricDescriptor descriptor = mock(MetricDescriptor.class);
            when(descriptor.metricString()).thenReturn("test.metric");
            when(descriptor.isTargetIncluded(any())).thenReturn(true);
            collector.collectLong(descriptor, 123L);
            return null;
        }).when(metricsRegistry).collect(any());
        assertByteForByteRoundTrip(writeStandard(new MetricsPlugin(logger, metricsRegistry, hazelcastProperties)));
    }

    @Test
    public void testOperationHeartbeatPlugin() {
        InvocationMonitor invocationMonitor = mock(InvocationMonitor.class);
        ConcurrentMap<Address, AtomicLong> heartbeatPerMember = new ConcurrentHashMap<>();
        Address address = mock(Address.class);
        when(address.toString()).thenReturn("127.0.0.1:5701");
        heartbeatPerMember.put(address, new AtomicLong(System.currentTimeMillis() - SECONDS.toMillis(60)));
        when(invocationMonitor.getHeartbeatBroadcastPeriodMillis()).thenReturn(15_000L);
        when(invocationMonitor.getHeartbeatPerMember()).thenReturn(heartbeatPerMember);
        OperationHeartbeatPlugin plugin = new OperationHeartbeatPlugin(logger, invocationMonitor, hazelcastProperties);
        assertByteForByteRoundTrip(writeStandard(plugin));
    }

    @Test
    public void testNetworkingImbalancePlugin() {
        NioNetworking networking = mock(NioNetworking.class);
        NioThread thread = mock(NioThread.class);
        when(thread.getName()).thenReturn("nio-thread-0");
        when(thread.bytesTransceived()).thenReturn(1_000L);
        when(thread.framesTransceived()).thenReturn(100L);
        when(thread.priorityFramesTransceived()).thenReturn(10L);
        when(thread.eventCount()).thenReturn(50L);
        when(thread.handleCount()).thenReturn(20L);
        when(thread.completedTaskCount()).thenReturn(5L);
        when(networking.getInputThreads()).thenReturn(new NioThread[]{thread});
        when(networking.getOutputThreads()).thenReturn(new NioThread[]{thread});
        NetworkingImbalancePlugin plugin = new NetworkingImbalancePlugin(logger, hazelcastProperties, networking);
        assertByteForByteRoundTrip(writeStandard(plugin));
    }

    @Test
    public void testOperationThreadSamplerPlugin() throws Exception {
        OperationThreadSamplerPlugin plugin = new OperationThreadSamplerPlugin(nodeEngine);
        Field partitionField = OperationThreadSamplerPlugin.class.getDeclaredField("partitionSpecificSamples");
        partitionField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentItemCounter<String> samples = (ConcurrentItemCounter<String>) partitionField.get(plugin);
        samples.add("com.hazelcast.map.GetOperation", 3L);
        samples.add("com.hazelcast.map.PutOperation", 1L);
        assertByteForByteRoundTrip(writeStandard(plugin));
    }

    @Test
    public void testMemberHeartbeatPlugin() throws Exception {
        ClusterServiceImpl clusterService = (ClusterServiceImpl) nodeEngine.getClusterService();
        MemberHeartbeatPlugin plugin = new MemberHeartbeatPlugin(
                nodeEngine.getLogger(MemberHeartbeatPlugin.class), clusterService, nodeEngine.getProperties());

        Member member = clusterService.getLocalMember();
        ClusterHeartbeatManager heartbeatManager = clusterService.getClusterHeartbeatManager();
        Field detectorField = ClusterHeartbeatManager.class.getDeclaredField("heartbeatFailureDetector");
        detectorField.setAccessible(true);
        DeadlineClusterFailureDetector detector =
                (DeadlineClusterFailureDetector) detectorField.get(heartbeatManager);
        Field mapField = DeadlineClusterFailureDetector.class.getDeclaredField("heartbeatTimes");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentMap<Member, Long> heartbeatTimes = (ConcurrentMap<Member, Long>) mapField.get(detector);
        heartbeatTimes.put(member, System.currentTimeMillis() - SECONDS.toMillis(60));

        assertByteForByteRoundTrip(writeStandard(plugin));
    }

    @Test
    public void testSlowOperationPlugin() {
        OperationServiceImpl operationService = mock(OperationServiceImpl.class);
        SlowOperationDTO dto = new SlowOperationDTO();
        dto.operation = "com.hazelcast.map.GetOperation";
        dto.totalInvocations = 10;
        dto.stackTrace = "at com.hazelcast.map.GetOperation.run(GetOperation.java:42)";
        dto.invocations = Collections.emptyList();
        when(operationService.getSlowOperationDTOs()).thenReturn(Collections.singletonList(dto));
        SlowOperationPlugin plugin = new SlowOperationPlugin(logger, operationService, hazelcastProperties);
        assertByteForByteRoundTrip(writeStandard(plugin));
    }

    @Test
    public void testPendingInvocationsPlugin() {
        InvocationRegistry invocationRegistry = mock(InvocationRegistry.class);
        when(invocationRegistry.iterator()).thenReturn(Collections.emptyIterator());
        PendingInvocationsPlugin plugin = new PendingInvocationsPlugin(logger, invocationRegistry, hazelcastProperties);
        assertByteForByteRoundTrip(writeStandard(plugin));
    }

    @Test
    public void testSystemLogPlugin() {
        HazelcastInstance mockHz = mock(HazelcastInstance.class);
        Cluster cluster = mock(Cluster.class);
        Member localMember = mock(Member.class);
        Address address = mock(Address.class);
        LifecycleService lifecycleService = mock(LifecycleService.class);
        when(mockHz.getCluster()).thenReturn(cluster);
        when(mockHz.getLifecycleService()).thenReturn(lifecycleService);
        when(cluster.getLocalMember()).thenReturn(localMember);
        when(localMember.getAddress()).thenReturn(address);
        when(address.toString()).thenReturn("127.0.0.1:5701");
        SystemLogPlugin plugin = new SystemLogPlugin(
                hazelcastProperties, mock(ConnectionListenable.class), mockHz, logger);
        assertByteForByteRoundTrip(writeStandard(plugin));
    }

    @Test
    public void testMemberHazelcastInstanceInfoPlugin() {
        NodeEngineImpl mockNodeEngine = mock(NodeEngineImpl.class);
        Node node = mock(Node.class);
        ClusterServiceImpl clusterService = mock(ClusterServiceImpl.class);
        Diagnostics diagnostics = mock(Diagnostics.class);
        Address address = mock(Address.class);
        when(mockNodeEngine.getLogger(MemberHazelcastInstanceInfoPlugin.class)).thenReturn(logger);
        when(mockNodeEngine.getProperties()).thenReturn(hazelcastProperties);
        when(mockNodeEngine.getNode()).thenReturn(node);
        when(mockNodeEngine.getClusterService()).thenReturn(clusterService);
        when(mockNodeEngine.getDiagnostics()).thenReturn(diagnostics);
        when(diagnostics.getDiagnosticsConfig()).thenReturn(new DiagnosticsConfig());
        when(node.getThisAddress()).thenReturn(address);
        when(address.toString()).thenReturn("127.0.0.1:5701");
        when(node.getClusterService()).thenReturn(clusterService);
        when(node.getState()).thenReturn(NodeState.ACTIVE);
        MemberHazelcastInstanceInfoPlugin plugin = new MemberHazelcastInstanceInfoPlugin(mockNodeEngine);
        assertByteForByteRoundTrip(writeStandard(plugin));
    }

    // -------------------------------------------------------------------------
    // JSON-idempotent round-trip tests
    // These plugins write some numeric values as plain strings, so the STANDARD
    // representation may differ after a round-trip (e.g. "12345" becomes "12,345").
    // We verify that the JSON representation is stable across two parse-serialize cycles.
    // -------------------------------------------------------------------------

    @Test
    public void testBuildInfoPlugin() {
        assertJsonIdempotentRoundTrip(writeStandard(new BuildInfoPlugin(logger)));
    }

    @Test
    public void testConfigPropertiesPlugin() {
        Properties props = new Properties();
        props.setProperty("hazelcast.test.timeout.seconds", "30");
        assertJsonIdempotentRoundTrip(
                writeStandard(new ConfigPropertiesPlugin(logger, new HazelcastProperties(props))));
    }

    @Test
    public void testSystemPropertiesPlugin() {
        assertJsonIdempotentRoundTrip(writeStandard(new SystemPropertiesPlugin(logger)));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Runs {@code plugin} with a STANDARD writer that includes the epoch timestamp,
     * and returns the captured output string.
     */
    private String writeStandard(DiagnosticsPlugin plugin) {
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(true, logger);
        writer.init(new PrintWriter(out));
        plugin.onStart();
        plugin.run(writer);
        plugin.onShutdown();
        return out.toString();
    }

    /**
     * Asserts that the full STANDARD → parseStandard → toJson → parseJson → toStandard pipeline
     * reproduces the original STANDARD string byte-for-byte.
     * Trivially-empty output (empty string or empty section {@code "name[]"}) is skipped.
     */
    private void assertByteForByteRoundTrip(String standard) {
        if (isTrivialOutput(standard)) {
            return;
        }
        DiagnosticsLogConverter.DiagnosticEntry e1 = converter.parseStandard(standard);
        assertNotNull("parseStandard returned null for: " + standard, e1);
        String json = converter.toJson(e1);
        DiagnosticsLogConverter.DiagnosticEntry e2 = converter.parseJson(json);
        assertNotNull("parseJson returned null for: " + json, e2);
        String standard2 = converter.toStandard(e2);
        assertEquals(standard, standard2);
    }

    /**
     * Asserts that the JSON representation is stable across two parse-serialize cycles:
     * toJson(parseStandard(s)) == toJson(parseJson(toJson(parseStandard(s)))).
     */
    private void assertJsonIdempotentRoundTrip(String standard) {
        if (isTrivialOutput(standard)) {
            return;
        }
        DiagnosticsLogConverter.DiagnosticEntry e1 = converter.parseStandard(standard);
        assertNotNull("parseStandard returned null for: " + standard, e1);
        String json1 = converter.toJson(e1);
        DiagnosticsLogConverter.DiagnosticEntry e2 = converter.parseJson(json1);
        assertNotNull("parseJson returned null for: " + json1, e2);
        String json2 = converter.toJson(e2);
        assertEquals(json1, json2);
    }

    private static boolean isTrivialOutput(String standard) {
        if (standard == null) {
            return true;
        }
        String trimmed = standard.trim();
        return trimmed.isEmpty() || trimmed.endsWith("[]");
    }
}
