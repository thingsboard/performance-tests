/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.service.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.Test;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.NodeMsg;

import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The test IS the SUT instance (extends it), so inherited protected fields/methods are reachable
 * through {@code this} without reflection — same approach as {@code MqttGatewayBatchAPITestTest}.
 * The connect/publish I/O seams are overridden to inject outcomes via already-completed Netty
 * futures, so the async cycle runs synchronously with no broker. {@code @PostConstruct} init() is
 * never invoked in a unit test, so any state the cycle needs is set explicitly per test.
 */
class MqttGatewayEphemeralAPITestTest extends MqttGatewayEphemeralAPITest {

    static final ObjectMapper testMapper = new ObjectMapper();

    // --- injectable seam state ---
    Future<MqttConnectResult> connectResult;
    boolean publishSucceeds = true;
    MqttClient lastClient;
    int rescheduleCalls;

    @Override
    protected MqttClient createClient(String token) {
        MqttClient c = mock(MqttClient.class);
        when(c.publish(anyString(), any(), any())).thenReturn(
                publishSucceeds
                        ? ImmediateEventExecutor.INSTANCE.newSucceededFuture(null)
                        : ImmediateEventExecutor.INSTANCE.newFailedFuture(new RuntimeException("pub fail")));
        lastClient = c;
        return c;
    }

    @Override
    protected Future<MqttConnectResult> connectAsync(MqttClient client) {
        return connectResult;
    }

    @Override
    protected void scheduleNext(GatewayTarget target) {
        rescheduleCalls++; // no real timer in a unit test
    }

    private void configure(int gwStart, int gwEnd, int devStart, int devEnd) {
        gatewayStartIdx = gwStart;
        gatewayEndIdx = gwEnd;
        deviceStartIdx = devStart;
        deviceEndIdx = devEnd;
        telemetryTest = true;
        MessageGenerator gen = mock(MessageGenerator.class);
        when(gen.getNextNodeMessage(anyString(), anyBoolean())).thenAnswer(inv -> {
            ObjectNode node = testMapper.createObjectNode();
            node.putArray(inv.getArgument(0)).addObject().put("ts", 1L);
            return new NodeMsg(node, false);
        });
        tsMsgGenerator = gen;
    }

    private static Future<MqttConnectResult> succeededConnect() {
        MqttConnectResult ok = mock(MqttConnectResult.class);
        when(ok.isSuccess()).thenReturn(true);
        return ImmediateEventExecutor.INSTANCE.newSucceededFuture(ok);
    }

    private static Future<MqttConnectResult> failedConnect() {
        return ImmediateEventExecutor.INSTANCE.newFailedFuture(new RuntimeException("connect refused"));
    }

    @Test
    void buildsNameBasedTargetsWithRoundRobinDeviceAssignment() {
        configure(0, 2, 0, 4); // d0->gw0, d1->gw1, d2->gw0, d3->gw1
        List<GatewayTarget> targets = buildTargets();
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).gatewayName()).isEqualTo("GW00000000");
        assertThat(targets.get(0).deviceNames()).containsExactly("DW00000000", "DW00000002");
        assertThat(targets.get(1).gatewayName()).isEqualTo("GW00000001");
        assertThat(targets.get(1).deviceNames()).containsExactly("DW00000001", "DW00000003");
    }

    @Test
    void buildBatchMergesAllDeviceEntriesInArrayShape() throws Exception {
        configure(0, 2, 0, 4);
        byte[] payload = buildBatch(buildTargets().get(0).deviceNames(), false);
        JsonNode parsed = testMapper.readTree(payload);
        assertThat(parsed.size()).isEqualTo(2);
        assertThat(parsed.has("DW00000000")).isTrue();
        assertThat(parsed.has("DW00000002")).isTrue();
        assertThat(parsed.get("DW00000000").isArray()).isTrue(); // server requires the array shape
    }

    @Test
    void successfulCyclePublishesThenDisconnectsReschedulesAndLeavesNoSharedClients() {
        configure(0, 1, 0, 2);
        connectPermits = new Semaphore(8);
        connectResult = succeededConnect();

        runCycle(buildTargets().get(0));

        assertThat(connectSuccess.get()).isEqualTo(1);
        assertThat(connectFailed.get()).isEqualTo(0);
        assertThat(publishSuccess.get()).isEqualTo(1);
        verify(lastClient, times(1)).publish(eq("v1/gateway/telemetry"), any(), any());
        verify(lastClient, times(1)).disconnect();
        assertThat(rescheduleCalls).isEqualTo(1);
        assertThat(connectPermits.availablePermits()).isEqualTo(8); // permit released
        assertThat(cyclesCompleted.get()).isEqualTo(1);
        // inherited protected collections — never populated by the ephemeral path
        assertThat(mqttClients).isEmpty();
        assertThat(clientNames).isEmpty();
    }

    @Test
    void connectFailureIsCountedDisconnectedAndRescheduledWithoutPublish() {
        configure(0, 1, 0, 2);
        connectPermits = new Semaphore(8);
        connectResult = failedConnect();

        runCycle(buildTargets().get(0));

        assertThat(connectFailed.get()).isEqualTo(1);
        assertThat(connectSuccess.get()).isEqualTo(0);
        verify(lastClient, never()).publish(anyString(), any(), any());
        verify(lastClient, times(1)).disconnect();
        assertThat(rescheduleCalls).isEqualTo(1);
        assertThat(connectPermits.availablePermits()).isEqualTo(8); // permit released even on failure
    }

    @Test
    void resolveMaxConcurrentConnectsAutoUsesFormulaAndHonoursOverride() {
        cycleLengthSec = 900;
        maxConcurrentConnectsConfig = "auto";
        // 10000 / 900 = 11.111/s * CONNECT_TIMEOUT(5) * headroom(2) = 111.11 -> 112
        assertThat(resolveMaxConcurrentConnects(10_000)).isEqualTo(112);
        maxConcurrentConnectsConfig = "500";
        assertThat(resolveMaxConcurrentConnects(10)).isEqualTo(500);
    }

    @Test
    void cycleAndJitterMillisComeFromConfig() {
        cycleLengthSec = 600;
        jitterSec = 120;
        assertThat(cycleLengthMillis()).isEqualTo(600_000L);
        assertThat(jitterMillis()).isEqualTo(120_000L);
    }
}
