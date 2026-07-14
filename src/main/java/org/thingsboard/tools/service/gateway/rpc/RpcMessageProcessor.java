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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Pure parser for an inbound gateway RPC ({@code {"device":..,"data":{"id":..,..}}}): extracts the
 * dedup key parts ({@code device}, {@code data.id}), the one-way delivery latency (if the rule chain
 * stamped a send-timestamp), and renders the reply. No stats/side effects — the receiver drives all
 * accounting so it can dedup by {@code (device, requestId)} before counting.
 */
@Slf4j
public class RpcMessageProcessor {

    /** Parsed result. {@code reply} is null when responding is disabled or there is no template. */
    public record ProcessedRpc(String deviceName, String requestId, Long latencyMs, byte[] reply) {
    }

    private final ObjectMapper mapper;
    private final String sendTsPath;
    private final boolean respond;
    private final RpcResponseTemplate template;

    public RpcMessageProcessor(ObjectMapper mapper, String sendTsPath, boolean respond,
                               RpcResponseTemplate template) {
        this.mapper = mapper;
        this.sendTsPath = sendTsPath;
        this.respond = respond;
        this.template = template;
    }

    public ProcessedRpc process(ByteBuf payload, long nowMs) {
        return process(ByteBufUtil.getBytes(payload), nowMs);
    }

    /** Returns null only for a malformed (unparseable) payload. */
    public ProcessedRpc process(byte[] payload, long nowMs) {
        JsonNode request;
        try {
            request = mapper.readTree(payload);
        } catch (IOException e) {
            log.debug("Failed to parse inbound RPC payload", e);
            return null;
        }
        String deviceName = text(request.get("device"));
        String requestId = text(JsonPaths.resolve(request, "data.id"));
        Long latencyMs = null;
        Long sendTs = asLong(JsonPaths.resolve(request, sendTsPath));
        if (sendTs != null) {
            latencyMs = nowMs - sendTs;
        }
        byte[] reply = (respond && template != null)
                ? template.render(request, nowMs).getBytes(StandardCharsets.UTF_8)
                : null;
        return new ProcessedRpc(deviceName, requestId, latencyMs, reply);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asText();
    }

    private static Long asLong(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.canConvertToLong()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
