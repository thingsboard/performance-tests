package org.thingsboard.tools.service.gateway.rpc;

import com.fasterxml.jackson.databind.JsonNode;

public final class JsonPaths {

    private JsonPaths() {}

    /** Resolve a dot-path (e.g. "data.params.sendTs") into a Jackson tree; null if any segment is absent. */
    public static JsonNode resolve(JsonNode root, String dotPath) {
        if (root == null || dotPath == null || dotPath.isEmpty()) {
            return null;
        }
        JsonNode node = root;
        for (String part : dotPath.split("\\.")) {
            if (node == null) {
                return null;
            }
            node = node.get(part);
            if (node == null || node.isMissingNode()) {
                return null;
            }
        }
        return node;
    }
}
