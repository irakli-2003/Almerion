package com.dnd.cloud;

import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@link CloudStore} that lives on this machine only: in memory, optionally persisted to a
 * JSON file. Used when the shared store is switched off, in tests, and as the offline cache
 * behind {@link CachingCloudStore}.
 */
public class LocalCloudStore implements CloudStore {
    private final ObjectMapper mapper = JsonMappers.create();
    private final Path file;
    private final JsonTree tree;

    /** In-memory only. */
    public LocalCloudStore() {
        this(null);
    }

    public LocalCloudStore(Path file) {
        this.file = file;
        this.tree = new JsonTree(load());
    }

    private ObjectNode load() {
        if (file == null || !Files.exists(file)) return null;
        try {
            JsonNode node = mapper.readTree(file.toFile());
            return node instanceof ObjectNode o ? o : null;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public synchronized JsonNode get(String path) {
        return tree.get(path);
    }

    @Override
    public synchronized void put(String path, Object value) {
        tree.set(path, value == null ? null : mapper.valueToTree(value));
        save();
    }

    @Override
    public synchronized void delete(String path) {
        tree.remove(path);
        save();
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), tree.root());
        } catch (IOException e) {
            throw new CloudException("Could not write " + file + ": " + e.getMessage(), false, e);
        }
    }
}
