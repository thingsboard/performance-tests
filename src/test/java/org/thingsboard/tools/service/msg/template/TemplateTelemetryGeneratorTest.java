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
package org.thingsboard.tools.service.msg.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thingsboard.tools.service.msg.BaseMessageGenerator;
import org.thingsboard.tools.service.msg.NodeMsg;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateTelemetryGeneratorTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final String DEVICE = "c1000000-0000-4000-8000-000000000007";

    TemplateTelemetryGenerator generator(String api) throws Exception {
        TemplateTelemetryGenerator gen = new TemplateTelemetryGenerator();
        Field f = BaseMessageGenerator.class.getDeclaredField("testApi");
        f.setAccessible(true);
        f.set(gen, api);
        Field t = TemplateTelemetryGenerator.class.getDeclaredField("template");
        t.setAccessible(true);
        t.set(gen, PayloadTemplate.parse(mapper.readTree("""
            {"static": {"deviceModel": "model-x", "mode": 1},
             "random": {"temperature": [-6.0, 6.0], "humidity": [0, 1]}}""")));
        return gen;
    }

    @Test
    void gatewayModeWrapsValuesAsDeviceKeyedArrayWithTs() throws Exception {
        NodeMsg msg = generator("gateway").getNextNodeMessage(DEVICE, false);

        JsonNode entry = msg.getNode().get(DEVICE);
        assertThat(entry.isArray()).isTrue();
        assertThat(entry.size()).isEqualTo(1);
        JsonNode point = entry.get(0);
        assertThat(point.get("ts").isIntegralNumber()).isTrue();
        JsonNode values = point.get("values");
        assertThat(values.get("deviceModel").asText()).isEqualTo("model-x");
        assertThat(values.get("mode").asInt()).isEqualTo(1);
        assertThat(values.has("temperature")).isTrue();
        assertThat(values.has("humidity")).isTrue();
        assertThat(values.size()).isEqualTo(4); // deviceModel, mode, temperature, humidity
        assertThat(msg.isTriggersAlarm()).isFalse();
    }

    @Test
    void deviceModeOmitsGatewayWrapper() throws Exception {
        JsonNode node = generator("device").getNextNodeMessage(DEVICE, false).getNode();
        assertThat(node.has("ts")).isTrue();
        assertThat(node.get("values").get("mode").asInt()).isEqualTo(1);
    }
}
