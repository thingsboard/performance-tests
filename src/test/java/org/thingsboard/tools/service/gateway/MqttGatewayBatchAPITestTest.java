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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.NodeMsg;
import org.thingsboard.tools.service.shared.BaseMqttAPITest.PublishTask;

import java.util.HashSet;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// The class under test exposes its decision logic via protected nextPublishTask and relies on
// protected inherited fields (testMessagesPerSecond, deviceClients, sequentialTest, tsMsgGenerator,
// random). Those fields live in superclasses in a different package, so per JLS 6.6.2 they are only
// reachable here through "this" of a subclass. This test therefore IS the SUT instance: it extends
// MqttGatewayBatchAPITest and exercises its own inherited members. init()/@PostConstruct is never
// invoked in a unit test, so random is set manually to avoid an NPE on the random-selection path.
class MqttGatewayBatchAPITestTest extends MqttGatewayBatchAPITest {

    static final ObjectMapper testMapper = new ObjectMapper();

    MqttClient gw0 = mock(MqttClient.class);
    MqttClient gw1 = mock(MqttClient.class);

    @BeforeEach
    void setUp() {
        random = new Random(0); // init() is not called in this unit test
        testMessagesPerSecond = 2;
        telemetryTest = true;
        sequentialTest = true;
        // round-robin mapping: d0->gw0, d1->gw1, d2->gw0, d3->gw1
        for (int d = 0; d < 4; d++) {
            DeviceClient c = new DeviceClient();
            c.setDeviceName("DW0000000" + d);
            c.setGatewayName(d % 2 == 0 ? "GW00000000" : "GW00000001");
            c.setMqttClient(d % 2 == 0 ? gw0 : gw1);
            deviceClients.add(c);
        }
        MessageGenerator gen = mock(MessageGenerator.class);
        when(gen.getNextNodeMessage(anyString(), anyBoolean())).thenAnswer(inv -> {
            ObjectNode node = testMapper.createObjectNode();
            node.putArray(inv.getArgument(0)).addObject().put("ts", 1L);
            return new NodeMsg(node, false);
        });
        tsMsgGenerator = gen;
    }

    @Test
    void batchTaskMergesAllDevicesOfTheSelectedGateway() throws Exception {
        PublishTask task = nextPublishTask(0, 0, false, new HashSet<>());

        assertThat(task.client().getMqttClient()).isSameAs(gw0);
        JsonNode payload = testMapper.readTree(task.data());
        assertThat(payload.size()).isEqualTo(2);
        assertThat(payload.has("DW00000000")).isTrue();
        assertThat(payload.has("DW00000002")).isTrue();
        assertThat(payload.get("DW00000000").isArray()).isTrue(); // server requires array shape

        PublishTask second = nextPublishTask(0, 1, false, new HashSet<>());
        assertThat(second.client().getMqttClient()).isSameAs(gw1);
        JsonNode payload1 = testMapper.readTree(second.data());
        assertThat(payload1.has("DW00000001")).isTrue();
        assertThat(payload1.has("DW00000003")).isTrue();
    }

    @Test
    void sequentialSelectionWrapsAroundGateways() throws Exception {
        PublishTask task = nextPublishTask(1, 0, false, new HashSet<>());
        assertThat(task.client().getMqttClient()).isSameAs(gw0);
    }

    @Test
    void randomSelectionNeverSpinsWhenMpsExceedsGatewayCount() throws Exception {
        sequentialTest = false;
        HashSet<Object> targets = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            assertThat(nextPublishTask(0, i, false, targets)).isNotNull();
        }
    }
}
