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
