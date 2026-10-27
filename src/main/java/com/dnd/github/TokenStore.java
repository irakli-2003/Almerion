package com.dnd.github;

import com.dnd.data.AppHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/**
 * Keeps the linked GitHub account for this OS user in {@code <data folder>/data/github/account.json}
 * - never in a campaign folder, so it can't be committed or synced. On Windows the token is
 * encrypted with DPAPI for the current Windows user ({@link WindowsDpapi}). Elsewhere, or if DPAPI
 * isn't usable, the token is stored as plain text: that file sits in the user's own profile
 * folder and is restricted to the owner where the file system allows it, the same protection
 * Git credential stores and most CLI tools rely on.
 */
public final class TokenStore {

    /** Encrypts the token at rest; {@link #NONE} stores it as is. */
    public interface Protector {
        String name();

        String protect(String secret) throws IOException;

        String unprotect(String stored) throws IOException;
    }

    public static final Protector NONE = new Protector() {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public String protect(String secret) {
            return secret;
        }

        @Override
        public String unprotect(String stored) {
            return stored;
        }
    };

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private final Protector protector;

    public TokenStore(Path file, Protector protector) {
        this.file = file;
        this.protector = protector;
    }

    public static TokenStore forCurrentUser() {
        Protector protector = WindowsDpapi.isWindows() ? new WindowsDpapi() : NONE;
        return new TokenStore(AppHome.resolve("data", "github", "account.json"), protector);
    }

    public Optional<GitHubAccount> load() {
        if (!Files.exists(file)) return Optional.empty();
        try {
            JsonNode json = JSON.readTree(file.toFile());
            String stored = json.path("token").asText("");
            if (stored.isBlank()) return Optional.empty();
            String token = protector.name().equals(json.path("protection").asText()) ? protector.unprotect(stored)
                : NONE.name().equals(json.path("protection").asText()) ? stored : null;
            if (token == null || token.isBlank()) return Optional.empty();
            return Optional.of(new GitHubAccount(json.path("id").asLong(), json.path("login").asText(), token));
        } catch (IOException | RuntimeException e) {
            // Unreadable (e.g. encrypted for another Windows user): the GM simply links again.
            return Optional.empty();
        }
    }

    public void save(GitHubAccount account) throws IOException {
        String protection = protector.name();
        String stored;
        try {
            stored = protector.protect(account.token());
        } catch (IOException | RuntimeException e) {
            protection = NONE.name();
            stored = account.token();
        }
        ObjectNode json = JSON.createObjectNode();
        json.put("login", account.login());
        json.put("id", account.id());
        json.put("protection", protection);
        json.put("token", stored);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json), StandardCharsets.UTF_8);
        ownerOnly(temp);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    private static void ownerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: the profile folder's ACL already limits it to the current user.
        }
    }
}
