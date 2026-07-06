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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RpcMessageProcessorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private RpcMessageProcessor processor(boolean respond, RpcLatencyStats stats) {
        RpcResponseTemplate template = new RpcResponseTemplate(
                "{\"device\":\"${device}\",\"id\":${data.id},\"data\":{\"status\":\"ACCEPTED\",\"receivedAt\":${now}}}");
        return new RpcMessageProcessor(mapper, "data.params.sendTs", respond, template, stats);
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void recordsLatencyAndRendersResponse() {
        RpcLatencyStats stats = new RpcLatencyStats();
        RpcMessageProcessor p = processor(true, stats);
        byte[] in = bytes("{\"device\":\"GW1\",\"data\":{\"id\":5,\"params\":{\"sendTs\":1000}}}");
        byte[] out = p.process(in, 1150L);
        assertThat(stats.getCount()).isEqualTo(1);
        assertThat(stats.getMean()).isCloseTo(150.0, within(0.001));
        assertThat(new String(out, StandardCharsets.UTF_8))
                .isEqualTo("{\"device\":\"GW1\",\"id\":5,\"data\":{\"status\":\"ACCEPTED\",\"receivedAt\":1150}}");
    }

    @Test
    void missingSendTsStillResponds() {
        RpcLatencyStats stats = new RpcLatencyStats();
        RpcMessageProcessor p = processor(true, stats);
        byte[] in = bytes("{\"device\":\"GW1\",\"data\":{\"id\":5,\"params\":{}}}");
        byte[] out = p.process(in, 1150L);
        assertThat(stats.getCount()).isEqualTo(0); // no readable sendTs -> no latency sample
        assertThat(out).isNotNull();
    }

    @Test
    void malformedJsonReturnsNull() {
        RpcLatencyStats stats = new RpcLatencyStats();
        RpcMessageProcessor p = processor(true, stats);
        byte[] out = p.process(bytes("not json"), 1150L);
        assertThat(out).isNull();
        assertThat(stats.getCount()).isEqualTo(0);
    }

    @Test
    void noResponseWhenRespondDisabled() {
        RpcLatencyStats stats = new RpcLatencyStats();
        RpcMessageProcessor p = processor(false, stats);
        byte[] in = bytes("{\"device\":\"GW1\",\"data\":{\"id\":5,\"params\":{\"sendTs\":1000}}}");
        byte[] out = p.process(in, 1150L);
        assertThat(out).isNull();
        assertThat(stats.getCount()).isEqualTo(1); // latency still measured
    }

    @Test
    void acceptsStringSendTs() {
        RpcLatencyStats stats = new RpcLatencyStats();
        RpcMessageProcessor p = processor(true, stats);
        byte[] in = bytes("{\"device\":\"GW1\",\"data\":{\"id\":5,\"params\":{\"sendTs\":\"1000\"}}}");
        byte[] out = p.process(in, 1150L);
        assertThat(out).isNotNull();
        assertThat(stats.getCount()).isEqualTo(1);
    }

    @Test
    void nullTemplateReturnsNull() {
        RpcLatencyStats stats = new RpcLatencyStats();
        RpcMessageProcessor p = new RpcMessageProcessor(mapper, "data.params.sendTs", true, null, stats);
        byte[] in = bytes("{\"device\":\"GW1\",\"data\":{\"id\":5,\"params\":{\"sendTs\":1000}}}");
        byte[] out = p.process(in, 1150L);
        assertThat(out).isNull();
        assertThat(stats.getCount()).isEqualTo(1);
    }

    @Test
    void processCountsEveryInboundAndStampsLastInbound() {
        RpcLatencyStats stats = new RpcLatencyStats();
        RpcResponseTemplate template = new RpcResponseTemplate("{\"ok\":true}");
        RpcMessageProcessor p = new RpcMessageProcessor(
                mapper, "data.params.sendTs", true, template, stats);

        // valid message with sendTs
        p.process("{\"device\":\"GW1\",\"data\":{\"params\":{\"sendTs\":1000}}}".getBytes(StandardCharsets.UTF_8), 9000L);
        // malformed payload still counts as received
        p.process("not json".getBytes(StandardCharsets.UTF_8), 9500L);

        assertThat(stats.getReceivedTotal()).isEqualTo(2);
        assertThat(stats.getLastInboundMs()).isEqualTo(9500L);
    }
}
