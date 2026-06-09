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
package org.thingsboard.tools.service.msg.smartMeter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thingsboard.tools.service.msg.BaseMessageGenerator;
import org.thingsboard.tools.service.msg.Msg;
import org.thingsboard.tools.service.msg.NodeMsg;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class SmartMeterTelemetryGeneratorTest {

    static final ObjectMapper mapper = new ObjectMapper();

    static void setTestApi(Object gen, String api) throws Exception {
        Field f = BaseMessageGenerator.class.getDeclaredField("testApi");
        f.setAccessible(true);
        f.set(gen, api);
    }

    @Test
    void nodeMessageCarriesGatewayWrappedTelemetry() throws Exception {
        SmartMeterTelemetryGenerator gen = new SmartMeterTelemetryGenerator();
        setTestApi(gen, "gateway");

        NodeMsg nodeMsg = gen.getNextNodeMessage("DW00000001", false);

        JsonNode entry = nodeMsg.getNode().get("DW00000001");
        assertThat(entry).isNotNull();
        assertThat(entry.isArray()).isTrue();
        JsonNode values = entry.get(0).get("values");
        assertThat(entry.get(0).has("ts")).isTrue();
        assertThat(values.has("pulseCounter")).isTrue();
        assertThat(values.has("leakage")).isTrue();
        assertThat(values.has("batteryLevel")).isTrue();
        assertThat(nodeMsg.isTriggersAlarm()).isFalse();
    }

    @Test
    void byteMessageIsSerializationOfNodeMessage() throws Exception {
        SmartMeterTelemetryGenerator gen = new SmartMeterTelemetryGenerator();
        setTestApi(gen, "gateway");

        Msg msg = gen.getNextMessage("DW00000001", true);

        JsonNode parsed = mapper.readTree(msg.getData());
        assertThat(parsed.get("DW00000001")).isNotNull();
        assertThat(parsed.get("DW00000001").get(0).get("values").get("batteryLevel").asInt()).isEqualTo(10); // alarm value
        assertThat(msg.isTriggersAlarm()).isTrue();
    }
}
