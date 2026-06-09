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
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A payload "values" definition loaded from a JSON document with two sections:
 *   "static": fields copied verbatim into every message (strings, fixed numbers);
 *   "random": key -> [min, max]; a fresh value each message. Integer bounds -> random int
 *             in [min, max] inclusive; any fractional bound -> random double in [min, max).
 */
public class PayloadTemplate {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record RandomField(String key, double min, double max, boolean integral) {
    }

    private final ObjectNode staticFields;
    private final List<RandomField> randomFields;

    private PayloadTemplate(ObjectNode staticFields, List<RandomField> randomFields) {
        this.staticFields = staticFields;
        this.randomFields = randomFields;
    }

    public static PayloadTemplate load(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("test.payloadTemplate is required when test.payloadType=CUSTOM");
        }
        return parse(MAPPER.readTree(new File(path)));
    }

    public static PayloadTemplate parse(JsonNode root) {
        JsonNode staticNode = root.path("static");
        JsonNode randomNode = root.path("random");
        ObjectNode staticFields = staticNode.isObject() ? (ObjectNode) staticNode : MAPPER.createObjectNode();

        List<RandomField> fields = new ArrayList<>();
        if (randomNode.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = randomNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode bounds = e.getValue();
                if (!bounds.isArray() || bounds.size() != 2 || !bounds.get(0).isNumber() || !bounds.get(1).isNumber()) {
                    throw new IllegalArgumentException("random field '" + e.getKey() + "' must be a [min, max] numeric array");
                }
                double min = bounds.get(0).asDouble();
                double max = bounds.get(1).asDouble();
                if (min > max) {
                    throw new IllegalArgumentException("random field '" + e.getKey() + "': min (" + min + ") must be <= max (" + max + ")");
                }
                boolean integral = bounds.get(0).isIntegralNumber() && bounds.get(1).isIntegralNumber();
                if (integral && (min < Integer.MIN_VALUE || max > Integer.MAX_VALUE)) {
                    throw new IllegalArgumentException("random field '" + e.getKey() + "': integer bounds must be within 32-bit int range");
                }
                fields.add(new RandomField(e.getKey(), min, max, integral));
            }
        }
        return new PayloadTemplate(staticFields, fields);
    }

    public void populate(ObjectNode values, Random random) {
        for (Iterator<Map.Entry<String, JsonNode>> it = staticFields.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            values.set(e.getKey(), e.getValue().deepCopy());
        }
        for (RandomField f : randomFields) {
            if (f.integral()) {
                int min = (int) f.min();
                int max = (int) f.max();
                values.put(f.key(), min + random.nextInt(max - min + 1));
            } else {
                values.put(f.key(), f.min() + random.nextDouble() * (f.max() - f.min()));
            }
        }
    }
}
