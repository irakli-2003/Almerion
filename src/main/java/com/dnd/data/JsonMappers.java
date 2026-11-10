package com.dnd.data;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Central factory for the {@link ObjectMapper} used to read/write campaign
 * JSON files.
 *
 * <p><b>Design note on domain/DTO separation:</b> this project intentionally
 * serializes the domain model classes (e.g. {@code PlayerCharacter}, {@code Item})
 * directly as the JSON wire format, rather than maintaining a parallel set of
 * DTO classes with a mapping layer. For a project of this size, a full DTO
 * layer was judged not worth the added indexing/maintenance cost (see project
 * architecture notes). To keep this pragmatic choice from becoming brittle as
 * the domain model gains fields/behavior over time, every {@code ObjectMapper}
 * is configured here to:</p>
 * <ul>
 *   <li>ignore unknown JSON properties on read, so adding a field to a
 *       campaign file (or loading a file saved by a newer/older app version)
 *       does not hard-fail deserialization of the whole catalog;</li>
 *   <li>indent output for readable, diffable campaign files.</li>
 * </ul>
 *
 * <p>If the domain model ever needs to diverge meaningfully from the JSON
 * schema (e.g. adding derived/computed properties that must not be
 * persisted), introduce dedicated DTOs for just those entities rather than
 * reintroducing this mapper globally.</p>
 */
public final class JsonMappers {
    private JsonMappers() {
    }

    public static ObjectMapper create() {
        return new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public static ObjectMapper forCampaign(java.nio.file.Path root) {
        ObjectMapper mapper = create();
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addDeserializer(com.dnd.model.character.stats.CoreStats.class,
            new com.fasterxml.jackson.databind.JsonDeserializer<>() {
                @Override public com.dnd.model.character.stats.CoreStats deserialize(
                        com.fasterxml.jackson.core.JsonParser parser,
                        com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
                    var stats = create().treeToValue(parser.readValueAsTree(),
                        com.dnd.model.character.stats.CoreStats.class);
                    stats.configure(StatSchemas.load(root));
                    return stats;
                }
            });
        module.addDeserializer(com.dnd.model.character.stats.Skills.class,
            new com.fasterxml.jackson.databind.JsonDeserializer<>() {
                @Override public com.dnd.model.character.stats.Skills deserialize(
                        com.fasterxml.jackson.core.JsonParser parser,
                        com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
                    var skills = create().treeToValue(parser.readValueAsTree(),
                        com.dnd.model.character.stats.Skills.class);
                    skills.configure(StatSchemas.load(root));
                    return skills;
                }
            });
        return mapper.registerModule(module);
    }
}
