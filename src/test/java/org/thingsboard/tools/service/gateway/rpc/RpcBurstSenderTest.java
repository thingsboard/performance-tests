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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RpcBurstSenderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void chunkSplitsWithShortFinalChunk() {
        List<String> names = List.of("a", "b", "c", "d", "e");
        List<List<String>> chunks = RpcBurstSender.chunk(names, 2);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).containsExactly("a", "b");
        assertThat(chunks.get(1)).containsExactly("c", "d");
        assertThat(chunks.get(2)).containsExactly("e");
    }

    @Test
    void chunkHandlesEmptyAndSingle() {
        assertThat(RpcBurstSender.chunk(List.of(), 4)).isEmpty();
        assertThat(RpcBurstSender.chunk(List.of("x"), 4)).hasSize(1);
        assertThat(RpcBurstSender.chunk(List.of("x"), 4).get(0)).containsExactly("x");
    }

    @Test
    void nextBoundaryAlignsToInterval() {
        // now not on a boundary -> next 60s boundary
        assertThat(RpcBurstSender.nextBoundaryMillis(1_000L, 60_000L, 0L)).isEqualTo(60_000L);
        // now exactly on a boundary -> fire now
        assertThat(RpcBurstSender.nextBoundaryMillis(60_000L, 60_000L, 0L)).isEqualTo(60_000L);
        // minStart pushes past now to a later boundary
        assertThat(RpcBurstSender.nextBoundaryMillis(1_000L, 60_000L, 65_000L)).isEqualTo(120_000L);
    }

    @Test
    void buildBodyClonesTemplateAndAddsDevicesAsObjectsWithRpcId() throws Exception {
        JsonNode template = mapper.readTree("{\"method\":\"doCmd\",\"params\":{\"x\":1}}");
        ObjectNode body = RpcBurstSender.buildBody(mapper, template, List.of("d1", "d2"));
        assertThat(body.get("method").asText()).isEqualTo("doCmd");
        assertThat(body.get("params").get("x").asInt()).isEqualTo(1);
        assertThat(body.get("devices").size()).isEqualTo(2);
        // devices are objects: { "name": ..., "rpcId": "<uuid>" }
        JsonNode d0 = body.get("devices").get(0);
        assertThat(d0.get("name").asText()).isEqualTo("d1");
        assertThat(UUID.fromString(d0.get("rpcId").asText())).isNotNull();
        JsonNode d1 = body.get("devices").get(1);
        assertThat(d1.get("name").asText()).isEqualTo("d2");
        assertThat(UUID.fromString(d1.get("rpcId").asText())).isNotNull();
        // template must not be mutated
        assertThat(template.has("devices")).isFalse();
    }

    @Test
    void buildBodyGeneratesADistinctRpcIdPerDeviceInTheChunk() {
        ObjectNode body = RpcBurstSender.buildBody(mapper, mapper.createObjectNode(),
                List.of("d1", "d2", "d3"));
        JsonNode devices = body.get("devices");
        Set<String> ids = new HashSet<>();
        for (JsonNode d : devices) {
            ids.add(d.get("rpcId").asText());
        }
        assertThat(ids).hasSize(3);
    }

    @Test
    void loadCommandTemplateFallsBackToBuiltInDefault() {
        JsonNode template = RpcBurstSender.loadCommandTemplate("");
        assertThat(template.isObject()).isTrue();
        assertThat(template.has("method")).isTrue();
        assertThat(template.has("params")).isTrue();
    }

    @Test
    void dispatchSummaryReportsCumulativeBurstsAndDevices() {
        RpcBurstSender sender = new RpcBurstSender(
                null, null, List.of("d1", "d2"), mapper.createObjectNode(),
                "RpcCalls", 10000, 500, 60, 0, RpcBurstSender.Mode.BURST, 48, 512);
        sender.recordBurstFired();
        sender.recordDispatched(500);
        sender.recordBurstFired();
        sender.recordDispatched(500);
        assertThat(sender.dispatchSummary())
                .contains("2 bursts fired")
                .contains("1000 device-RPCs dispatched");
    }

    @Test
    void modeFromConfigDefaultsToBurstAndParsesCaseInsensitively() {
        assertThat(RpcBurstSender.Mode.fromConfig(null)).isEqualTo(RpcBurstSender.Mode.BURST);
        assertThat(RpcBurstSender.Mode.fromConfig("")).isEqualTo(RpcBurstSender.Mode.BURST);
        assertThat(RpcBurstSender.Mode.fromConfig("burst")).isEqualTo(RpcBurstSender.Mode.BURST);
        assertThat(RpcBurstSender.Mode.fromConfig("SPREAD")).isEqualTo(RpcBurstSender.Mode.SPREAD);
        assertThat(RpcBurstSender.Mode.fromConfig("Spread")).isEqualTo(RpcBurstSender.Mode.SPREAD);
        // an unknown value is a misconfiguration — fail fast rather than silently pick a mode
        assertThatThrownBy(() -> RpcBurstSender.Mode.fromConfig("sometimes"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spreadTickMillisSpacesChunksEvenlyAcrossTheInterval() {
        assertThat(RpcBurstSender.spreadTickMillis(60_000L, 4)).isEqualTo(15_000L);   // 4 chunks over 60s
        assertThat(RpcBurstSender.spreadTickMillis(60_000L, 200)).isEqualTo(300L);    // representative scale
        assertThat(RpcBurstSender.spreadTickMillis(60_000L, 1)).isEqualTo(60_000L);   // single chunk = whole interval
        assertThat(RpcBurstSender.spreadTickMillis(1_000L, 3)).isEqualTo(333L);       // floor of uneven division
        assertThat(RpcBurstSender.spreadTickMillis(100L, 1000)).isEqualTo(1L);        // never below 1ms
    }

    @Test
    void chunkIndexForTickRotatesCoveringEveryChunkOncePerSweep() {
        int numChunks = 4;
        // one full sweep hits every chunk exactly once, in order
        assertThat(RpcBurstSender.chunkIndexForTick(0, numChunks)).isEqualTo(0);
        assertThat(RpcBurstSender.chunkIndexForTick(1, numChunks)).isEqualTo(1);
        assertThat(RpcBurstSender.chunkIndexForTick(3, numChunks)).isEqualTo(3);
        // the next tick wraps back to the first chunk (start of the next interval's sweep)
        assertThat(RpcBurstSender.chunkIndexForTick(4, numChunks)).isEqualTo(0);
        assertThat(RpcBurstSender.chunkIndexForTick(5, numChunks)).isEqualTo(1);
    }
}
