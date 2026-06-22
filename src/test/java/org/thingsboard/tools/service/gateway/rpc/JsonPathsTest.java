package org.thingsboard.tools.service.gateway.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPathsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode tree(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void resolvesNestedPath() throws Exception {
        JsonNode root = tree("{\"a\":{\"b\":{\"c\":7}}}");
        assertThat(JsonPaths.resolve(root, "a.b.c").asInt()).isEqualTo(7);
    }

    @Test
    void returnsNullForMissingTerminalSegment() throws Exception {
        JsonNode root = tree("{\"a\":{\"b\":{}}}");
        assertThat(JsonPaths.resolve(root, "a.b.c")).isNull();
    }

    @Test
    void returnsNullForMissingMiddleSegment() throws Exception {
        JsonNode root = tree("{\"a\":{}}");
        assertThat(JsonPaths.resolve(root, "a.x.c")).isNull();
    }

    @Test
    void returnsNullForNullOrEmptyPath() throws Exception {
        JsonNode root = tree("{\"a\":1}");
        assertThat(JsonPaths.resolve(root, "")).isNull();
        assertThat(JsonPaths.resolve(null, "a")).isNull();
    }
}
