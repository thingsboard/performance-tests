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
