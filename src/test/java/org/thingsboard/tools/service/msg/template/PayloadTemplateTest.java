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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadTemplateTest {

    static final ObjectMapper mapper = new ObjectMapper();

    PayloadTemplate parse(String json) throws Exception {
        return PayloadTemplate.parse(mapper.readTree(json));
    }

    @Test
    void copiesStaticFieldsVerbatimEveryMessage() throws Exception {
        PayloadTemplate t = parse("""
            {"static": {"deviceModel": "model-x", "zone": "zone-1", "mode": 1},
             "random": {}}""");

        ObjectNode values = mapper.createObjectNode();
        t.populate(values, new Random(1));

        assertThat(values.get("deviceModel").asText()).isEqualTo("model-x");
        assertThat(values.get("zone").asText()).isEqualTo("zone-1");
        assertThat(values.get("mode").asInt()).isEqualTo(1);
        assertThat(values.size()).isEqualTo(3);
    }

    @Test
    void integerBoundsProduceIntsWithinInclusiveRange() throws Exception {
        PayloadTemplate t = parse("""
            {"static": {}, "random": {"humidity": [0, 1]}}""");

        Random rnd = new Random(7);
        for (int i = 0; i < 200; i++) {
            ObjectNode values = mapper.createObjectNode();
            t.populate(values, rnd);
            assertThat(values.get("humidity").isInt()).isTrue();
            assertThat(values.get("humidity").asInt()).isBetween(0, 1);
        }
    }

    @Test
    void decimalBoundsProduceDoublesWithinRange() throws Exception {
        PayloadTemplate t = parse("""
            {"static": {}, "random": {"temperature": [-6.0, 6.0]}}""");

        Random rnd = new Random(7);
        boolean sawFraction = false;
        for (int i = 0; i < 200; i++) {
            ObjectNode values = mapper.createObjectNode();
            t.populate(values, rnd);
            assertThat(values.get("temperature").isDouble()).isTrue();
            double v = values.get("temperature").asDouble();
            assertThat(v).isBetween(-6.0, 6.0);
            sawFraction |= v != Math.floor(v);
        }
        assertThat(sawFraction).isTrue();
    }

    @Test
    void valuesChangeBetweenMessages() throws Exception {
        PayloadTemplate t = parse("""
            {"static": {}, "random": {"v": [0.0, 1000000.0]}}""");
        Random rnd = new Random(7);
        ObjectNode a = mapper.createObjectNode();
        ObjectNode b = mapper.createObjectNode();
        t.populate(a, rnd);
        t.populate(b, rnd);
        assertThat(a.get("v").asDouble()).isNotEqualTo(b.get("v").asDouble());
    }

    @Test
    void rejectsMalformedRandomEntry() throws Exception {
        assertThatThrownBy(() -> parse("""
            {"static": {}, "random": {"x": [1]}}"""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReversedBounds() throws Exception {
        assertThatThrownBy(() -> parse("""
            {"static": {}, "random": {"x": [6, 0]}}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be <= max");
    }
}
