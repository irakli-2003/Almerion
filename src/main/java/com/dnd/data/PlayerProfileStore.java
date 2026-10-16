package com.dnd.data;

import com.dnd.model.character.PlayerCharacter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A player's own copy of how their character looks and reads.
 *
 * <p>Campaign data belongs to the DM, and a joined session is only ever a cache that gets
 * overwritten on the next join. So the things a player is allowed to change - their
 * portrait, the name they go by, and their description - are kept here instead, on the
 * player's machine, keyed by account and character. Nothing mechanical is stored: levels,
 * ability scores, items and spells stay whatever the DM says they are.</p>
 */
public class PlayerProfileStore {

    private static final Path ROOT = AppHome.resolve("data", "player-profiles");

    /** The cosmetic fields a player owns. A null field means "leave the DM's value alone". */
    public static class Profile {
        private String name;
        private String description;
        private String imagePath;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getImagePath() { return imagePath; }
        public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    }

    private final Path profilesFile;
    private final Path imagesDir;
    private final ObjectMapper mapper = JsonMappers.create();

    public PlayerProfileStore(String username) {
        Path userDir = ROOT.resolve(sanitize(username));
        this.profilesFile = userDir.resolve("profiles.json");
        this.imagesDir = userDir.resolve("images");
    }

    /** Directory portraits are copied into; it doubles as the image root for loading them. */
    public Path imagesRoot() {
        return imagesDir.getParent();
    }

    public Profile get(String characterId) {
        Profile profile = readAll().get(characterId);
        return profile != null ? profile : new Profile();
    }

    public void save(String characterId, Profile profile) {
        Map<String, Profile> all = readAll();
        all.put(characterId, profile);
        try {
            Files.createDirectories(profilesFile.getParent());
            Files.writeString(profilesFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(all));
        } catch (IOException e) {
            throw new DataAccessException("Failed to save your character profile to " + profilesFile, e);
        }
    }

    /**
     * Copies a portrait into the player's own storage and records it on the profile, so it
     * survives the joined-session cache being rebuilt.
     *
     * @return the path recorded on the profile, relative to {@link #imagesRoot()}
     */
    public String storePortrait(String characterId, Path source) {
        String fileName = sanitize(characterId) + extensionOf(source);
        Path target = imagesDir.resolve(fileName);
        try {
            Files.createDirectories(imagesDir);
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DataAccessException("Failed to store the portrait at " + target, e);
        }
        return imagesRoot().relativize(target).toString().replace('\\', '/');
    }

    /**
     * Returns the character as this player sees it: the DM's sheet with the player's own
     * cosmetic choices laid over the top. The stored character is never modified.
     */
    public void applyTo(PlayerCharacter character) {
        if (character == null) return;
        Profile profile = get(character.getId());
        if (isSet(profile.getName())) character.setName(profile.getName());
        if (isSet(profile.getDescription())) character.setDescription(profile.getDescription());
        if (isSet(profile.getImagePath())) character.setImagePath(profile.getImagePath());
    }

    /** True when the player has overridden the portrait, so it loads from their storage. */
    public boolean hasOwnPortrait(String characterId) {
        return isSet(get(characterId).getImagePath());
    }

    private Map<String, Profile> readAll() {
        if (!Files.exists(profilesFile)) return new LinkedHashMap<>();
        try {
            return mapper.readValue(Files.readString(profilesFile), new TypeReference<LinkedHashMap<String, Profile>>() {});
        } catch (IOException e) {
            throw new DataAccessException("Failed to read your character profiles from " + profilesFile, e);
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static String extensionOf(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot).toLowerCase(java.util.Locale.ROOT) : ".png";
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "anonymous";
        String cleaned = value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        return cleaned.isBlank() ? "anonymous" : cleaned;
    }
}
