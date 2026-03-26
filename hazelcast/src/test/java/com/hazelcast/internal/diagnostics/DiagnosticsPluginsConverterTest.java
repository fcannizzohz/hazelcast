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

import com.hazelcast.instance.impl.Node;
import com.hazelcast.internal.cluster.impl.ClusterServiceImpl;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.spi.impl.NodeEngineImpl;
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
import java.util.Collections;
import java.util.Properties;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class DiagnosticsPluginsConverterTest extends HazelcastTestSupport {

    private DiagnosticsLogConverter converter;
    private ILogger logger;
    private HazelcastProperties hazelcastProperties;

    @Before
    public void setup() {
        converter = new DiagnosticsLogConverter();
        logger = Logger.getLogger(DiagnosticsPluginsConverterTest.class);
        hazelcastProperties = new HazelcastProperties(new Properties());
    }

    private String runPlugin(DiagnosticsPlugin plugin) {
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(true, logger);
        writer.init(new PrintWriter(out));
        plugin.onStart();
        plugin.run(writer);
        plugin.onShutdown();
        return out.toString();
    }

    private void verifyPlugin(DiagnosticsPlugin plugin) {
        String standardOutput = runPlugin(plugin);
        System.out.println("[DEBUG_LOG] Standard Output for " + plugin.getClass().getSimpleName() + ":\n" + standardOutput);

        String trimmed = standardOutput.trim();
        if (trimmed.isEmpty() || trimmed.endsWith("[]")) {
            System.out.println("[DEBUG_LOG] Skipping parsing for " + plugin.getClass().getSimpleName() + " because output is empty or contains no data.");
            return;
        }

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(standardOutput);
        assertNotNull("Failed to parse standard output for " + plugin.getClass().getSimpleName(), entry);

        String json = converter.toJson(entry);
        System.out.println("[DEBUG_LOG] JSON Output for " + plugin.getClass().getSimpleName() + ":\n" + json);
        assertNotNull(json);
    }

    @Test
    public void testBuildInfoPlugin() {
        verifyPlugin(new BuildInfoPlugin(logger));
    }

    @Test
    public void testSystemPropertiesPlugin() {
        verifyPlugin(new SystemPropertiesPlugin(logger));
    }

    @Test
    public void testConfigPropertiesPlugin() {
        Properties props = new Properties();
        props.setProperty("test.prop", "test.value");
        verifyPlugin(new ConfigPropertiesPlugin(logger, new HazelcastProperties(props)));
    }

    @Test
    public void testSlowOperationPlugin() {
        OperationServiceImpl operationService = mock(OperationServiceImpl.class);
        com.hazelcast.internal.management.dto.SlowOperationDTO dto = new com.hazelcast.internal.management.dto.SlowOperationDTO();
        dto.operation = "mockOp";
        dto.totalInvocations = 10;
        dto.stackTrace = "line1\nline2";
        dto.invocations = Collections.emptyList();
        when(operationService.getSlowOperationDTOs()).thenReturn(Collections.singletonList(dto));
        verifyPlugin(new SlowOperationPlugin(logger, operationService, hazelcastProperties));
    }

    @Test
    public void testMemberHazelcastInstanceInfoPlugin() {
        NodeEngineImpl nodeEngine = mock(NodeEngineImpl.class);
        Node node = mock(Node.class);
        ClusterServiceImpl clusterService = mock(ClusterServiceImpl.class);
        Diagnostics diagnostics = mock(Diagnostics.class);
        DiagnosticsConfig diagnosticsConfig = new DiagnosticsConfig();

        when(nodeEngine.getLogger(MemberHazelcastInstanceInfoPlugin.class)).thenReturn(logger);
        when(nodeEngine.getProperties()).thenReturn(hazelcastProperties);
        when(nodeEngine.getNode()).thenReturn(node);
        when(nodeEngine.getClusterService()).thenReturn(clusterService);
        when(nodeEngine.getDiagnostics()).thenReturn(diagnostics);
        when(diagnostics.getDiagnosticsConfig()).thenReturn(diagnosticsConfig);
        when(node.getThisAddress()).thenReturn(mock(com.hazelcast.cluster.Address.class));
        when(node.getClusterService()).thenReturn(clusterService);
        when(node.getState()).thenReturn(com.hazelcast.instance.impl.NodeState.ACTIVE);

        verifyPlugin(new MemberHazelcastInstanceInfoPlugin(nodeEngine));
    }

    @Test
    public void testSystemLogPlugin() {
        com.hazelcast.core.HazelcastInstance hazelcastInstance = mock(com.hazelcast.core.HazelcastInstance.class);
        com.hazelcast.cluster.Cluster cluster = mock(com.hazelcast.cluster.Cluster.class);
        com.hazelcast.cluster.Member localMember = mock(com.hazelcast.cluster.Member.class);
        com.hazelcast.cluster.Address address = mock(com.hazelcast.cluster.Address.class);
        com.hazelcast.core.LifecycleService lifecycleService = mock(com.hazelcast.core.LifecycleService.class);

        when(hazelcastInstance.getCluster()).thenReturn(cluster);
        when(hazelcastInstance.getLifecycleService()).thenReturn(lifecycleService);
        when(cluster.getLocalMember()).thenReturn(localMember);
        when(localMember.getAddress()).thenReturn(address);
        when(address.toString()).thenReturn("127.0.0.1:5701");

        verifyPlugin(new SystemLogPlugin(hazelcastProperties, mock(com.hazelcast.internal.nio.ConnectionListenable.class), hazelcastInstance, logger));
    }

    @Test
    public void testOperationHeartbeatPlugin() {
        com.hazelcast.spi.impl.operationservice.impl.InvocationMonitor invocationMonitor = mock(com.hazelcast.spi.impl.operationservice.impl.InvocationMonitor.class);
        java.util.concurrent.ConcurrentMap<com.hazelcast.cluster.Address, java.util.concurrent.atomic.AtomicLong> heartbeatPerMember = new java.util.concurrent.ConcurrentHashMap<>();
        com.hazelcast.cluster.Address address = mock(com.hazelcast.cluster.Address.class);
        when(address.toString()).thenReturn("127.0.0.1:5701");
        heartbeatPerMember.put(address, new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() - 60000));

        when(invocationMonitor.getHeartbeatBroadcastPeriodMillis()).thenReturn(15000L);
        when(invocationMonitor.getHeartbeatPerMember()).thenReturn(heartbeatPerMember);

        verifyPlugin(new OperationHeartbeatPlugin(logger, invocationMonitor, hazelcastProperties));
    }

    @Test
    public void testPendingInvocationsPlugin() {
        com.hazelcast.spi.impl.operationservice.impl.InvocationRegistry invocationRegistry = mock(com.hazelcast.spi.impl.operationservice.impl.InvocationRegistry.class);
        when(invocationRegistry.iterator()).thenReturn(Collections.emptyIterator());
        verifyPlugin(new PendingInvocationsPlugin(logger, invocationRegistry, hazelcastProperties));
    }

    @Test
    public void testMetricsPlugin() {
        com.hazelcast.internal.metrics.MetricsRegistry metricsRegistry = mock(com.hazelcast.internal.metrics.MetricsRegistry.class);
        org.mockito.stubbing.Answer answer = invocation -> {
            com.hazelcast.internal.metrics.collectors.MetricsCollector collector = invocation.getArgument(0);
            com.hazelcast.internal.metrics.MetricDescriptor descriptor = mock(com.hazelcast.internal.metrics.MetricDescriptor.class);
            when(descriptor.metricString()).thenReturn("test.metric");
            when(descriptor.isTargetIncluded(org.mockito.ArgumentMatchers.any())).thenReturn(true);
            collector.collectLong(descriptor, 123L);
            return null;
        };
        org.mockito.Mockito.doAnswer(answer).when(metricsRegistry).collect(org.mockito.ArgumentMatchers.any());
        verifyPlugin(new MetricsPlugin(logger, metricsRegistry, hazelcastProperties));
    }

    @Test
    public void testNetworkingImbalancePlugin() {
        com.hazelcast.internal.networking.nio.NioNetworking networking = mock(com.hazelcast.internal.networking.nio.NioNetworking.class);
        com.hazelcast.internal.networking.nio.NioThread thread = mock(com.hazelcast.internal.networking.nio.NioThread.class);
        when(thread.getName()).thenReturn("nio-thread-0");
        when(thread.bytesTransceived()).thenReturn(1000L);
        when(thread.framesTransceived()).thenReturn(100L);
        when(thread.priorityFramesTransceived()).thenReturn(10L);
        when(thread.eventCount()).thenReturn(50L);
        when(thread.handleCount()).thenReturn(20L);
        when(thread.completedTaskCount()).thenReturn(5L);

        when(networking.getInputThreads()).thenReturn(new com.hazelcast.internal.networking.nio.NioThread[]{thread});
        when(networking.getOutputThreads()).thenReturn(new com.hazelcast.internal.networking.nio.NioThread[]{thread});

        verifyPlugin(new NetworkingImbalancePlugin(logger, hazelcastProperties, networking));
    }

    @Test
    public void testStoreLatencyPlugin() {
        StoreLatencyPlugin plugin = new StoreLatencyPlugin(logger, hazelcastProperties);
        plugin.newProbe("service", "map", "put").recordValue(1000000L);
        verifyPlugin(plugin);
    }

    @Test
    public void testOverloadedConnectionsPlugin() {
        NodeEngineImpl nodeEngine = mock(NodeEngineImpl.class);
        Node node = mock(Node.class);
        when(nodeEngine.getNode()).thenReturn(node);
        when(nodeEngine.getLogger(OverloadedConnectionsPlugin.class)).thenReturn(logger);
        when(nodeEngine.getProperties()).thenReturn(hazelcastProperties);
        Diagnostics diagnostics = mock(Diagnostics.class);
        when(nodeEngine.getDiagnostics()).thenReturn(diagnostics);
        when(diagnostics.getDiagnosticsConfig()).thenReturn(new DiagnosticsConfig());

        com.hazelcast.internal.server.Server server = mock(com.hazelcast.internal.server.Server.class);
        when(node.getServer()).thenReturn(server);
        when(server.getConnections()).thenReturn(Collections.emptySet());

        verifyPlugin(new OverloadedConnectionsPlugin(nodeEngine));
    }
}
