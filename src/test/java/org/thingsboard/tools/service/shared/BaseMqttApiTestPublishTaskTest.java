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
package org.thingsboard.tools.service.shared;

import io.netty.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.Msg;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BaseMqttApiTestPublishTaskTest {

    static class TestApi extends BaseMqttAPITest {
        @Override protected String getWarmUpTopic() { return "t"; }
        @Override protected byte[] getData(String deviceName) { return new byte[0]; }
        @Override protected void runApiTestIteration(int i, AtomicInteger s, AtomicInteger f, CountDownLatch l) { }
        @Override protected String getTestTopic() { return "t"; }
        @Override protected void logSuccessTestMessage(int iteration, DeviceClient client) { }
        @Override protected void logFailureTestMessage(int iteration, DeviceClient client, Future<?> future) { }
    }

    @Test
    void defaultHookWalksDevicesSequentiallyAcrossIterations() throws Exception {
        TestApi api = new TestApi();
        api.testMessagesPerSecond = 2;
        api.telemetryTest = true;
        for (int d = 0; d < 4; d++) {
            DeviceClient c = new DeviceClient();
            c.setDeviceName("DW0000000" + d);
            api.deviceClients.add(c);
        }
        MessageGenerator gen = mock(MessageGenerator.class);
        when(gen.getNextMessage(anyString(), anyBoolean()))
                .thenAnswer(inv -> new Msg(((String) inv.getArgument(0)).getBytes(StandardCharsets.UTF_8)));
        api.tsMsgGenerator = gen;

        assertThat(new String(api.nextPublishTask(0, 0, false, new HashSet<>()).data())).isEqualTo("DW00000000");
        assertThat(new String(api.nextPublishTask(0, 1, false, new HashSet<>()).data())).isEqualTo("DW00000001");
        assertThat(new String(api.nextPublishTask(1, 0, false, new HashSet<>()).data())).isEqualTo("DW00000002");
        assertThat(new String(api.nextPublishTask(1, 1, false, new HashSet<>()).data())).isEqualTo("DW00000003");
        assertThat(api.nextPublishTask(2, 0, false, new HashSet<>()).client().getDeviceName()).isEqualTo("DW00000000"); // wraps
    }

    @Test
    void taskCarriesAlarmCountFromGenerator() throws Exception {
        TestApi api = new TestApi();
        api.testMessagesPerSecond = 1;
        api.telemetryTest = true;
        DeviceClient c = new DeviceClient();
        c.setDeviceName("DW00000000");
        api.deviceClients.add(c);
        MessageGenerator gen = mock(MessageGenerator.class);
        when(gen.getNextMessage(anyString(), anyBoolean()))
                .thenAnswer(inv -> new Msg("x".getBytes(StandardCharsets.UTF_8), inv.getArgument(1)));
        api.tsMsgGenerator = gen;

        assertThat(api.nextPublishTask(0, 0, true, new HashSet<>()).alarmsTriggered()).isEqualTo(1);
        assertThat(api.nextPublishTask(0, 0, false, new HashSet<>()).alarmsTriggered()).isEqualTo(0);
    }

    @Test
    void statsSummaryCountsConnectedClientsAndDeltas() {
        TestApi api = new TestApi();
        org.thingsboard.mqtt.MqttClient connected = mock(org.thingsboard.mqtt.MqttClient.class);
        org.thingsboard.mqtt.MqttClient dropped = mock(org.thingsboard.mqtt.MqttClient.class);
        when(connected.isConnected()).thenReturn(true);
        when(dropped.isConnected()).thenReturn(false);
        api.mqttClients.add(connected);
        api.mqttClients.add(dropped);
        api.totalSuccessPublishedCount.set(10);
        api.totalFailedPublishedCount.set(1);

        assertThat(api.gatewayStatsSummary())
                .isEqualTo("Gateway stats: connected 1/2, published since last report: success=10, failed=1");
        api.totalSuccessPublishedCount.set(25);
        assertThat(api.gatewayStatsSummary())
                .isEqualTo("Gateway stats: connected 1/2, published since last report: success=15, failed=0");
    }
}
