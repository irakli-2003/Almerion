package com.dnd.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Path-addressed access to a Jackson object tree, mirroring how Firebase addresses its data. */
final class JsonTree {
    private ObjectNode root;

    JsonTree(ObjectNode root) {
        this.root = root == null ? JsonNodeFactory.instance.objectNode() : root;
    }

    ObjectNode root() {
        return root;
    }

    JsonNode get(String path) {
        JsonNode node = root;
        for (String part : split(path)) {
            if (node == null || !node.isObject()) return null;
            node = node.get(part);
        }
        return node == null || node.isNull() ? null : node.deepCopy();
    }

    void set(String path, JsonNode value) {
        String[] parts = split(path);
        if (value == null || value.isNull()) {
            remove(path);
            return;
        }
        if (parts.length == 0) {
            root = value.isObject() ? (ObjectNode) value.deepCopy() : JsonNodeFactory.instance.objectNode();
            return;
        }
        ObjectNode parent = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = parent.get(parts[i]);
            if (!(child instanceof ObjectNode)) {
                child = parent.putObject(parts[i]);
            }
            parent = (ObjectNode) child;
        }
        parent.set(parts[parts.length - 1], value.deepCopy());
    }

    void remove(String path) {
        String[] parts = split(path);
        if (parts.length == 0) {
            root = JsonNodeFactory.instance.objectNode();
            return;
        }
        JsonNode parent = root;
        for (int i = 0; i < parts.length - 1; i++) {
            parent = parent.get(parts[i]);
            if (!(parent instanceof ObjectNode)) return;
        }
        ((ObjectNode) parent).remove(parts[parts.length - 1]);
    }

    static String[] split(String path) {
        if (path == null) return new String[0];
        String trimmed = path.replaceAll("^/+|/+$", "");
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/+");
    }
}
