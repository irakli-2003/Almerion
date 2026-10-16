package com.dnd.auth;

import com.dnd.cloud.CloudKeys;
import com.dnd.cloud.CloudStore;
import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Accounts kept in the shared {@link CloudStore}, so a player who registers on their own
 * laptop is the same person the DM sees on theirs.
 *
 * <p>Layout: {@code users/<username-key>} holds the account, {@code emails/<email-key>} points
 * back at the username key. Accounts that only exist in an older, device-local
 * {@code users.json} are uploaded the first time they are looked up.</p>
 */
public class CloudUserDirectory implements UserDirectory {
    private final CloudStore store;
    private final UserDirectory legacy;
    private final ObjectMapper mapper = JsonMappers.create()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CloudUserDirectory(CloudStore store, UserDirectory legacy) {
        this.store = store;
        this.legacy = legacy;
    }

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        Optional<User> found = read("users/" + CloudKeys.of(username));
        if (found.isPresent()) return found;
        return migrate(legacy == null ? Optional.empty() : legacy.findByUsername(username));
    }

    @Override
    public Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        JsonNode pointer = store.get("emails/" + CloudKeys.of(email));
        if (pointer != null && pointer.isTextual()) {
            Optional<User> found = read("users/" + pointer.asText());
            if (found.isPresent()) return found;
        }
        return migrate(legacy == null ? Optional.empty() : legacy.findByEmail(email));
    }

    @Override
    public void save(User user) {
        String key = CloudKeys.of(user.getUsername());
        store.put("users/" + key, user);
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            store.put("emails/" + CloudKeys.of(user.getEmail()), key);
        }
    }

    private Optional<User> read(String path) {
        JsonNode node = store.get(path);
        if (node == null || !node.isObject()) return Optional.empty();
        try {
            return Optional.of(mapper.treeToValue(node, User.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Uploads a device-local account, unless someone already claimed that username or email online. */
    private Optional<User> migrate(Optional<User> local) {
        if (local.isEmpty()) return local;
        User user = local.get();
        if (read("users/" + CloudKeys.of(user.getUsername())).isPresent()) return Optional.empty();
        JsonNode emailOwner = user.getEmail() == null ? null : store.get("emails/" + CloudKeys.of(user.getEmail()));
        if (emailOwner != null) return Optional.empty();
        try {
            save(user);
        } catch (CloudStore.CloudException ignored) {
            // Offline: still let them in on this machine; the upload retries next time.
        }
        return local;
    }
}
