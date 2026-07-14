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
import org.junit.jupiter.api.Test;
import org.thingsboard.tools.service.gateway.rpc.RpcMessageProcessor.ProcessedRpc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RpcMessageProcessorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private RpcMessageProcessor processor(boolean respond) {
        RpcResponseTemplate template = new RpcResponseTemplate(
                "{\"device\":\"${device}\",\"id\":${data.id},\"data\":{\"status\":\"ACCEPTED\",\"receivedAt\":${now}}}");
        return new RpcMessageProcessor(mapper, "data.params.sendTs", respond, template);
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void extractsKeyLatencyAndRendersResponse() {
        RpcMessageProcessor p = processor(true);
        ProcessedRpc r = p.process(bytes("{\"device\":\"GW1\",\"data\":{\"id\":5,\"params\":{\"sendTs\":1000}}}"), 1150L);
        assertThat(r.deviceName()).isEqualTo("GW1");
        assertThat(r.requestId()).isEqualTo("5");
        assertThat(r.latencyMs()).isEqualTo(150L);
        assertThat(new String(r.reply(), StandardCharsets.UTF_8))
                .isEqualTo("{\"device\":\"GW1\",\"id\":5,\"data\":{\"status\":\"ACCEPTED\",\"receivedAt\":1150}}");
    }

    @Test
    void missingSendTsStillRespondsWithNullLatency() {
        RpcMessageProcessor p = processor(true);
        ProcessedRpc r = p.process(bytes("{\"device\":\"GW1\",\"data\":{\"id\":5,\"params\":{}}}"), 1150L);
        assertThat(r.latencyMs()).isNull();
        assertThat(r.reply()).isNotNull();
        assertThat(r.requestId()).isEqualTo("5");
    }

    @Test
    void malformedJsonReturnsNull() {
        assertThat(processor(true).process(bytes("not json"), 1150L)).isNull();
    }

    @Test
    void noReplyWhenRespondDisabledButKeyAndLatencyStillParsed() {
        RpcMessageProcessor p = processor(false);
        ProcessedRpc r = p.process(bytes("{\"device\":\"GW1\",\"data\":{\"id\":5,\"params\":{\"sendTs\":1000}}}"), 1150L);
        assertThat(r.reply()).isNull();
        assertThat(r.latencyMs()).isEqualTo(150L);
        assertThat(r.deviceName()).isEqualTo("GW1");
    }

    @Test
    void acceptsStringSendTs() {
        ProcessedRpc r = processor(true)
                .process(bytes("{\"device\":\"GW1\",\"data\":{\"id\":5,\"params\":{\"sendTs\":\"1000\"}}}"), 1150L);
        assertThat(r.reply()).isNotNull();
        assertThat(r.latencyMs()).isEqualTo(150L);
    }

    @Test
    void nullTemplateReturnsNullReply() {
        RpcMessageProcessor p = new RpcMessageProcessor(mapper, "data.params.sendTs", true, null);
        ProcessedRpc r = p.process(bytes("{\"device\":\"GW1\",\"data\":{\"id\":5,\"params\":{\"sendTs\":1000}}}"), 1150L);
        assertThat(r.reply()).isNull();
        assertThat(r.latencyMs()).isEqualTo(150L);
    }

    @Test
    void missingDeviceOrIdYieldsNullKeyParts() {
        ProcessedRpc r = processor(true).process(bytes("{\"data\":{\"params\":{}}}"), 9000L);
        assertThat(r.deviceName()).isNull();
        assertThat(r.requestId()).isNull();
    }
}
