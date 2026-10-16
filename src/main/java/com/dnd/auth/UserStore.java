package com.dnd.auth;

import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JSON-backed repository for {@link User} accounts.
 *
 * <p>Accounts are global to the application install (not per-campaign),
 * since a person logs in once and can then act as DM/Player across any
 * campaign. Stored at {@code data/auth/users.json}, next to the
 * {@code data/} campaigns root used by {@code CampaignStorage}.</p>
 */
public class UserStore {
    private final Path file;

    public UserStore() {
        this(com.dnd.data.AppHome.resolve("data", "auth", "users.json"));
    }

    public UserStore(Path file) {
        this.file = file;
    }

    public synchronized List<User> list() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            ObjectMapper mapper = JsonMappers.create();
            User[] users = mapper.readValue(file.toFile(), User[].class);
            List<User> list = new ArrayList<>();
            for (User u : users) list.add(u);
            return list;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file, e);
        }
    }

    public synchronized Optional<User> findByEmail(String email) {
        if (email == null) return Optional.empty();
        return list().stream()
            .filter(u -> email.equalsIgnoreCase(u.getEmail()))
            .findFirst();
    }

    public synchronized Optional<User> findByUsername(String username) {
        if (username == null) return Optional.empty();
        return list().stream()
            .filter(u -> username.equalsIgnoreCase(u.getUsername()))
            .findFirst();
    }

    /** Finds by email OR username - used at login time since either may be entered. */
    public synchronized Optional<User> findByEmailOrUsername(String identifier) {
        if (identifier == null) return Optional.empty();
        return list().stream()
            .filter(u -> identifier.equalsIgnoreCase(u.getEmail()) || identifier.equalsIgnoreCase(u.getUsername()))
            .findFirst();
    }

    /** Inserts a new user or replaces the existing one with the same id. */
    public synchronized void save(User user) {
        List<User> all = list();
        all.removeIf(u -> u.getId().equals(user.getId()));
        all.add(user);
        write(all);
    }

    private void write(List<User> users) {
        try {
            Files.createDirectories(file.getParent());
            ObjectMapper mapper = JsonMappers.create();
            mapper.writeValue(file.toFile(), users);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write " + file, e);
        }
    }
}
