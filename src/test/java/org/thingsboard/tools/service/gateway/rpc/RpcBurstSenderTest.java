package org.thingsboard.tools.service.gateway.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    void buildBodyClonesTemplateAndAddsDevices() throws Exception {
        JsonNode template = mapper.readTree("{\"method\":\"doCmd\",\"params\":{\"x\":1}}");
        ObjectNode body = RpcBurstSender.buildBody(mapper, template, List.of("d1", "d2"));
        assertThat(body.get("method").asText()).isEqualTo("doCmd");
        assertThat(body.get("params").get("x").asInt()).isEqualTo(1);
        assertThat(body.get("devices").size()).isEqualTo(2);
        assertThat(body.get("devices").get(0).asText()).isEqualTo("d1");
        // template must not be mutated
        assertThat(template.has("devices")).isFalse();
    }

    @Test
    void loadCommandTemplateFallsBackToBuiltInDefault() {
        JsonNode template = RpcBurstSender.loadCommandTemplate("");
        assertThat(template.isObject()).isTrue();
        assertThat(template.has("method")).isTrue();
        assertThat(template.has("params")).isTrue();
    }
}
