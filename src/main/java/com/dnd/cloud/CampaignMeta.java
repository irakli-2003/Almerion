package com.dnd.cloud;

import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * The campaign's identity, kept in {@code campaign-meta.json} at the campaign root. The id is
 * what memberships are keyed by, so it has to travel with the campaign folder: copying a
 * campaign to another computer keeps who may see it.
 */
public final class CampaignMeta {
    public static final String FILE_NAME = "campaign-meta.json";

    private CampaignMeta() {
    }

    public static Optional<String> idOf(Path campaignRoot) {
        Path file = campaignRoot.resolve(FILE_NAME);
        if (!Files.exists(file)) return Optional.empty();
        try {
            JsonNode node = JsonMappers.create().readTree(file.toFile());
            String id = node.path("id").asText("");
            return id.isBlank() ? Optional.empty() : Optional.of(id);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Returns the campaign's id, creating one if the campaign doesn't have one yet. */
    public static String ensureId(Path campaignRoot) {
        Optional<String> existing = idOf(campaignRoot);
        if (existing.isPresent()) return existing.get();
        String id = UUID.randomUUID().toString();
        write(campaignRoot, id);
        return id;
    }

    /** Gives the campaign a brand-new id (e.g. a copy that should be a separate campaign). */
    public static String assignNewId(Path campaignRoot) {
        String id = UUID.randomUUID().toString();
        write(campaignRoot, id);
        return id;
    }

    private static void write(Path campaignRoot, String id) {
        ObjectMapper mapper = JsonMappers.create();
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id);
        try {
            Files.createDirectories(campaignRoot);
            mapper.writerWithDefaultPrettyPrinter().writeValue(campaignRoot.resolve(FILE_NAME).toFile(), node);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + campaignRoot.resolve(FILE_NAME), e);
        }
    }
}
