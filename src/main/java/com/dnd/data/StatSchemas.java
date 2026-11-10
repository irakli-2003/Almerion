package com.dnd.data;

import com.dnd.model.character.stats.StatSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StatSchemas {
    private StatSchemas() { }
    public static StatSchema load(Path root) {
        Path file = root.resolve("world").resolve("stats.json");
        if (!Files.exists(file)) return new StatSchema();
        try {
            StatSchema schema = JsonMappers.create().readValue(file.toFile(), StatSchema.class);
            schema.validate();
            return schema;
        } catch (IOException | IllegalArgumentException ex) {
            throw new DataAccessException("Cannot load campaign stat definitions: " + file, ex);
        }
    }
    public static void save(Path root, StatSchema schema) {
        schema.validate();
        Path file = root.resolve("world").resolve("stats.json");
        try {
            CampaignPaths paths = new CampaignPaths(root);
            for (Path source : java.util.List.of(paths.spellsFile(), paths.monstersFile(), paths.beastsFile(),
                    paths.npcsFile(), paths.playersFile(), paths.racesFile(), paths.classesFile(), paths.mapsFile())) {
                if (Files.exists(source)) validateReferences(JsonMappers.create().readTree(source.toFile()), schema, source);
            }
            Files.createDirectories(file.getParent());
            Path pending = Files.createTempFile(file.getParent(), "stats-", ".tmp");
            try {
                JsonMappers.create().writeValue(pending.toFile(), schema);
                Files.move(pending, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(pending); }
        } catch (IOException ex) {
            throw new DataAccessException("Cannot save campaign stat definitions: " + file, ex);
        }
    }

    private static void validateReferences(com.fasterxml.jackson.databind.JsonNode node, StatSchema schema, Path source) {
        if (node.isArray()) {
            node.forEach(child -> validateReferences(child, schema, source));
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                var value = entry.getValue();
                if (java.util.Set.of("spellcastingAbility", "savingThrow").contains(key) && value.isTextual()) {
                    validateReference(value.asText(), schema, source);
                } else if (java.util.Set.of("primaryAbilities", "savingThrowProficiencies").contains(key) && value.isArray()) {
                    value.forEach(v -> validateReference(v.asText(), schema, source));
                } else if (java.util.Set.of("abilityBonuses", "savingThrowBonuses").contains(key) && value.isObject()) {
                    value.fieldNames().forEachRemaining(id -> validateReference(id, schema, source));
                }
                validateReferences(value, schema, source);
            });
        }
    }

    private static void validateReference(String id, StatSchema schema, Path source) {
        if (id.isBlank() || id.equalsIgnoreCase("none")) return;
        try { schema.resolveAbility(id); }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Remap the referenced ability '" + id + "' before removing it ("
                + source.getFileName() + "). Add its replacement to ability bindings.", ex);
        }
    }
}
