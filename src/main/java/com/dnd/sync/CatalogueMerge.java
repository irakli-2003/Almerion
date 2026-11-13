package com.dnd.sync;

import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Three-way catalogue merge by stable entity ID, then object field; ordered lists stay atomic. */
final class CatalogueMerge {
    static final Map<String, String> CATALOGUES = Map.ofEntries(
        Map.entry("players/players.json", "players"), Map.entry("world/classes.json", "classes"),
        Map.entry("world/races.json", "races"), Map.entry("world/items.json", "items"),
        Map.entry("world/spells.json", "spells"), Map.entry("world/places.json", "places"),
        Map.entry("world/effects.json", "effects"), Map.entry("world/damage-types.json", "damageTypes"),
        Map.entry("world/npcs.json", "npcs"), Map.entry("world/monsters.json", "monsters"),
        Map.entry("world/beasts.json", "beasts"), Map.entry("world/languages.json", "languages"),
        Map.entry("world/alchemy-ingredients.json", "ingredients"), Map.entry("world/books.json", "books"),
        Map.entry("world/dice.json", "dice"), Map.entry("world/maps.json", "maps"),
        Map.entry("world/id-registry.json", "entries"));

    record Field(String key, String entity, String pointer, String before, String mine, String github) { }
    record Result(byte[] bytes, List<Field> conflicts) {
        Result { conflicts = List.copyOf(conflicts); }
    }

    private final ObjectMapper mapper = JsonMappers.create()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Map<String, CampaignRepo.Choice> choices;
    private final String revision;
    private final List<Field> conflicts = new ArrayList<>();

    private CatalogueMerge(String path, byte[] base, byte[] mine, byte[] remote, Map<String, CampaignRepo.Choice> choices) {
        this.choices = choices;
        revision = path + "#json:" + revision(base, mine, remote) + ":";
    }

    /** Unsupported legacy shapes use the existing explicit whole-file review instead. */
    static Optional<Result> merge(String path, byte[] base, byte[] mine, byte[] remote,
                                  Map<String, CampaignRepo.Choice> choices) throws IOException {
        String collection = CATALOGUES.get(path);
        if (collection == null || mine == null || remote == null) return Optional.empty();
        var merge = new CatalogueMerge(path, base, mine, remote, choices);
        JsonNode ours = merge.mapper.readTree(mine), theirs = merge.mapper.readTree(remote);
        JsonNode before = base == null ? merge.mapper.createObjectNode().set(collection, merge.mapper.createArrayNode())
            : merge.mapper.readTree(base);
        if (!supported(before, collection) || !supported(ours, collection) || !supported(theirs, collection)) {
            return Optional.empty();
        }
        ObjectNode result = merge.mapper.createObjectNode();
        for (String field : names(before, ours, theirs)) {
            if (field.equals(collection)) {
                Map<String, JsonNode> a = byId(before.get(field)), b = byId(ours.get(field)), c = byId(theirs.get(field));
                var entities = merge.mapper.createArrayNode();
                Set<String> ids = new LinkedHashSet<>(b.keySet());
                ids.addAll(c.keySet());
                ids.addAll(a.keySet());
                for (String id : ids) {
                    JsonNode local = b.get(id), incoming = c.get(id), original = a.get(id);
                    JsonNode label = local != null ? local : incoming != null ? incoming : original;
                    String name = label.path("name").asText(id);
                    JsonNode next = merge.value(original, local, incoming,
                        "/" + escape(field) + "/" + escape(id), name + " [" + id + "]");
                    if (next != null) entities.add(next);
                }
                result.set(field, entities);
            } else {
                JsonNode next = merge.value(before.get(field), ours.get(field), theirs.get(field), "/" + escape(field), path);
                if (next != null) result.set(field, next);
            }
        }
        return Optional.of(new Result(merge.mapper.writeValueAsBytes(result), merge.conflicts));
    }

    private JsonNode value(JsonNode base, JsonNode mine, JsonNode remote, String pointer, String entity) throws IOException {
        if (Objects.equals(mine, remote)) return mine;
        if (Objects.equals(base, mine)) return remote;
        if (Objects.equals(base, remote)) return mine;
        if (base != null && base.isObject() && mine != null && mine.isObject() && remote != null && remote.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            for (String field : names(base, mine, remote)) {
                JsonNode next = value(base.get(field), mine.get(field), remote.get(field), pointer + "/" + escape(field), entity);
                if (next != null) result.set(field, next);
            }
            return result;
        }
        String key = revision + pointer;
        CampaignRepo.Choice choice = choices.get(key);
        if (choice == CampaignRepo.Choice.MINE) return mine;
        if (choice == CampaignRepo.Choice.GITHUB) return remote;
        conflicts.add(new Field(key, entity, pointer, text(base), text(mine), text(remote)));
        return mine;
    }

    private String text(JsonNode value) throws IOException {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    private static boolean supported(JsonNode root, String field) {
        if (root == null || !root.isObject() || !root.path(field).isArray()) return false;
        Set<String> ids = new HashSet<>();
        for (JsonNode entity : root.get(field)) {
            JsonNode id = entity.get("id");
            if (!entity.isObject() || id == null || !id.isTextual() || id.asText().isBlank() || !ids.add(id.asText())) return false;
        }
        return true;
    }

    private static Map<String, JsonNode> byId(JsonNode list) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode item : list) result.put(item.get("id").asText(), item);
        return result;
    }

    private static Set<String> names(JsonNode... nodes) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode node : nodes) node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static String escape(String text) { return text.replace("~", "~0").replace("/", "~1"); }

    private static String revision(byte[]... versions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] bytes : versions) {
                digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
