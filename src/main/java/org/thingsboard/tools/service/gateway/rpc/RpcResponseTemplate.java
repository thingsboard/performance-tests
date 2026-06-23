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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RpcResponseTemplate {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^}]+)}");
    private static final String DEFAULT_CLASSPATH = "rpc/response-default.json";

    private final String template;

    public RpcResponseTemplate(String template) {
        this.template = template;
    }

    public static RpcResponseTemplate load(String pathOrEmpty) {
        try {
            String text;
            if (pathOrEmpty == null || pathOrEmpty.isBlank()) {
                try (InputStream in = RpcResponseTemplate.class.getClassLoader().getResourceAsStream(DEFAULT_CLASSPATH)) {
                    if (in == null) {
                        throw new IllegalStateException("Default RPC response template not found on classpath: " + DEFAULT_CLASSPATH);
                    }
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                text = Files.readString(Path.of(pathOrEmpty), StandardCharsets.UTF_8);
            }
            return new RpcResponseTemplate(text.trim());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load RPC response template: " + pathOrEmpty, e);
        }
    }

    /** Substitute ${now} with nowMs and every other ${dot.path} with the request value's text ("" if missing). */
    public String render(JsonNode request, long nowMs) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group(1).trim();
            String value;
            if ("now".equals(token)) {
                value = Long.toString(nowMs);
            } else {
                JsonNode node = JsonPaths.resolve(request, token);
                value = (node == null || node.isMissingNode() || node.isNull()) ? "" : node.asText();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
