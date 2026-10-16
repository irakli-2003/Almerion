package com.dnd.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Serialises a whole campaign for a live session and rebuilds it on a player's machine.
 *
 * <p>The DM's copy stays the only permanent one. What a player receives is a cache that is
 * rewritten from scratch on every join, so a stale local edit can never masquerade as
 * campaign truth, and nothing a player does propagates back.</p>
 *
 * <p>Each catalog is carried as the same wrapper object the campaign files already use
 * ({@code {"players": [...]}} and so on), which means hydrating is a plain file write and
 * the existing {@link CampaignRepositories} can read the result without knowing it came
 * from a session rather than from disk.</p>
 */
public final class SessionSnapshot {

    /** Where a joined session is cached; wiped and rewritten on each join. */
    private static final Path JOINED_ROOT = AppHome.resolve("data", "joined-sessions");

    /**
     * Every catalog carried in a snapshot: the snapshot key, the file it lives in, and the
     * property name inside that file's wrapper object.
     */
    private static final Map<String, Catalog> CATALOGS = new LinkedHashMap<>();

    static {
        catalog("players", CampaignPaths::playersFile, "players");
        catalog("maps", CampaignPaths::mapsFile, "maps");
        catalog("npcs", CampaignPaths::npcsFile, "npcs");
        catalog("monsters", CampaignPaths::monstersFile, "monsters");
        catalog("beasts", CampaignPaths::beastsFile, "beasts");
        catalog("items", CampaignPaths::itemsFile, "items");
        catalog("spells", CampaignPaths::spellsFile, "spells");
        catalog("classes", CampaignPaths::classesFile, "classes");
        catalog("races", CampaignPaths::racesFile, "races");
        catalog("places", CampaignPaths::placesFile, "places");
        catalog("effects", CampaignPaths::effectsFile, "effects");
        catalog("damageTypes", CampaignPaths::damageTypesFile, "damageTypes");
        catalog("languages", CampaignPaths::languagesFile, "languages");
        catalog("alchemyIngredients", CampaignPaths::alchemyIngredientsFile, "ingredients");
        catalog("books", CampaignPaths::booksFile, "books");
        catalog("dice", CampaignPaths::diceFile, "dice");
    }

    private record Catalog(Function<CampaignPaths, Path> file, String property) {
    }

    private static void catalog(String key, Function<CampaignPaths, Path> file, String property) {
        CATALOGS.put(key, new Catalog(file, property));
    }

    private SessionSnapshot() {
    }

    /**
     * Reads a campaign off disk into the JSON the DM pushes to players.
     *
     * <p>Files are copied through verbatim rather than round-tripped through the model
     * classes, so a field this build doesn't know about still reaches the players.</p>
     */
    public static String capture(Path campaignRoot, String campaignName) {
        ObjectMapper mapper = JsonMappers.create();
        CampaignPaths paths = new CampaignPaths(campaignRoot);
        ObjectNode root = mapper.createObjectNode();
        root.put("campaign", campaignName);
        com.dnd.cloud.CampaignMeta.idOf(campaignRoot).ifPresent(id -> root.put("campaignId", id));

        for (Map.Entry<String, Catalog> entry : CATALOGS.entrySet()) {
            Catalog catalog = entry.getValue();
            Path file = catalog.file().apply(paths);
            if (!Files.exists(file)) continue;
            try {
                JsonNode wrapper = mapper.readTree(Files.readString(file));
                JsonNode array = wrapper.get(catalog.property());
                if (array != null && array.isArray()) root.set(entry.getKey(), stripSecrets(array));
            } catch (IOException e) {
                throw new DataAccessException("Failed to read " + file + " while building the session snapshot", e);
            }
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new DataAccessException("Failed to serialise the session snapshot", e);
        }
    }

    /**
     * Rebuilds a snapshot into a local campaign directory a player can browse.
     *
     * @return the directory the campaign was written to
     */
    public static Path hydrate(String json, String sessionId) {
        ObjectMapper mapper = JsonMappers.create();
        Path root = JOINED_ROOT.resolve(sanitize(sessionId));
        CampaignPaths paths = new CampaignPaths(root);

        JsonNode snapshot;
        try {
            snapshot = mapper.readTree(json);
        } catch (IOException e) {
            throw new DataAccessException("The session data from the DM could not be read", e);
        }

        try {
            deleteRecursively(root);
            Files.createDirectories(paths.worldDir());
            Files.createDirectories(paths.playersDir());

            for (Map.Entry<String, Catalog> entry : CATALOGS.entrySet()) {
                Catalog catalog = entry.getValue();
                JsonNode array = snapshot.get(entry.getKey());
                ObjectNode wrapper = mapper.createObjectNode();
                // An absent catalog is written as an empty one, so every file the campaign
                // repositories expect exists and a partial snapshot still opens.
                wrapper.set(catalog.property(), array != null && array.isArray() ? array : mapper.createArrayNode());
                Path file = catalog.file().apply(paths);
                Files.createDirectories(file.getParent());
                Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper));
            }
        } catch (IOException e) {
            throw new DataAccessException("Failed to write the joined session to " + root, e);
        }
        return root;
    }

    /** Campaign name carried in a snapshot, or a neutral fallback. */    public static String campaignNameOf(String json) {
        try {
            JsonNode node = JsonMappers.create().readTree(json).get("campaign");
            if (node != null && !node.asText().isBlank()) return node.asText();
        } catch (IOException ignored) {
            // A snapshot without a readable name is still perfectly playable.
        }
        return "Live Session";
    }

    /** Registered campaign id carried in a snapshot (see {@code CampaignMeta}), or null. */
    public static String campaignIdOf(String json) {
        try {
            JsonNode node = JsonMappers.create().readTree(json).get("campaignId");
            if (node != null && !node.asText().isBlank()) return node.asText();
        } catch (IOException ignored) {
            // Treated as an unregistered campaign.
        }
        return null;
    }

    /**
     * Removes credential material before a catalog leaves the DM's machine. Character
     * access passwords are the DM's business, and a snapshot goes to every player at the
     * table, so the hashes and salts are dropped rather than broadcast.
     */
    private static JsonNode stripSecrets(JsonNode array) {
        for (JsonNode element : array) {
            if (element instanceof ObjectNode object) {
                object.remove("passwordHash");
                object.remove("passwordSalt");
            }
        }
        return array;
    }

    private static void deleteRecursively(Path path) throws IOException {        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static String sanitize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "session";
        String cleaned = sessionId.replaceAll("[^A-Za-z0-9_-]+", "-");
        return cleaned.isBlank() ? "session" : cleaned;
    }
}
