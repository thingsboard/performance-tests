package org.thingsboard.tools.service.gateway.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
public class RpcMessageProcessor {

    private final ObjectMapper mapper;
    private final String sendTsPath;
    private final boolean respond;
    private final RpcResponseTemplate template;
    private final RpcLatencyStats stats;

    public RpcMessageProcessor(ObjectMapper mapper, String sendTsPath, boolean respond,
                               RpcResponseTemplate template, RpcLatencyStats stats) {
        this.mapper = mapper;
        this.sendTsPath = sendTsPath;
        this.respond = respond;
        this.template = template;
        this.stats = stats;
    }

    public byte[] process(ByteBuf payload, long nowMs) {
        return process(ByteBufUtil.getBytes(payload), nowMs);
    }

    public byte[] process(byte[] payload, long nowMs) {
        JsonNode request;
        try {
            request = mapper.readTree(payload);
        } catch (IOException e) {
            log.debug("Failed to parse inbound RPC payload", e);
            return null;
        }
        Long sendTs = asLong(JsonPaths.resolve(request, sendTsPath));
        if (sendTs != null) {
            stats.recordLatency(nowMs - sendTs);
        }
        if (respond && template != null) {
            return template.render(request, nowMs).getBytes(StandardCharsets.UTF_8);
        }
        return null;
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
