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
package org.thingsboard.tools.service.gateway.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RpcResponseTemplateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode req(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void rendersDeviceIdAndNow() throws Exception {
        RpcResponseTemplate t = new RpcResponseTemplate(
                "{\"device\":\"${device}\",\"id\":${data.id},\"data\":{\"status\":\"ACCEPTED\",\"receivedAt\":${now}}}");
        JsonNode request = req("{\"device\":\"GW1\",\"data\":{\"id\":42,\"method\":\"ping\",\"params\":{}}}");
        String out = t.render(request, 1745123400050L);
        assertThat(out).isEqualTo(
                "{\"device\":\"GW1\",\"id\":42,\"data\":{\"status\":\"ACCEPTED\",\"receivedAt\":1745123400050}}");
    }

    @Test
    void echoesNestedParamsField() throws Exception {
        RpcResponseTemplate t = new RpcResponseTemplate("{\"label\":\"${data.params.label}\"}");
        JsonNode request = req("{\"device\":\"GW1\",\"data\":{\"id\":1,\"params\":{\"label\":\"hello\"}}}");
        assertThat(t.render(request, 0L)).isEqualTo("{\"label\":\"hello\"}");
    }

    @Test
    void missingPathRendersEmpty() throws Exception {
        RpcResponseTemplate t = new RpcResponseTemplate("[${data.params.nope}]");
        JsonNode request = req("{\"device\":\"GW1\",\"data\":{\"id\":1,\"params\":{}}}");
        assertThat(t.render(request, 0L)).isEqualTo("[]");
    }

    @Test
    void loadFallsBackToClasspathDefault() {
        RpcResponseTemplate t = RpcResponseTemplate.load("");
        assertThat(t).isNotNull();
    }

    @Test
    void defaultTemplateRendersNeutralAck() throws Exception {
        RpcResponseTemplate t = RpcResponseTemplate.load("");
        JsonNode request = req("{\"device\":\"GW9\",\"data\":{\"id\":7,\"params\":{}}}");
        String out = t.render(request, 123L);
        assertThat(out).contains("\"device\":\"GW9\"").contains("\"id\":7")
                .contains("\"status\":\"ACCEPTED\"").contains("\"receivedAt\":123");
    }
}
