package com.dnd.cloud;

import com.dnd.auth.User;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;

/** Credential-free profiles. Firebase rules bind names to verified provider UIDs. */
public final class CloudProfiles {
    private final CloudStore store;

    public CloudProfiles(CloudStore store) {
        this.store = store;
    }

    public Optional<User> find(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        return fromNode(store.get("profiles/" + CloudKeys.of(username)));
    }

    public User enroll(String uid, String email, User local) {
        JsonNode key = store.get("accountKeys/" + uid);
        if (key != null) {
            User profile = fromNode(store.get("profiles/" + key.asText()))
                .orElseThrow(() -> new CloudStore.CloudException("Your cloud profile is missing. Contact the administrator.", false));
            if (!uid.equals(profile.getProviderUid()) || !email.equalsIgnoreCase(profile.getEmail())) {
                throw new SecurityException("Cloud profile identity mismatch.");
            }
            return profile;
        }
        JsonNode claim = store.get("claimsByEmail/" + CloudKeys.of(email));
        String username = claim == null ? null : claim.path("username").asText(null);
        if (username == null && local != null && email.equalsIgnoreCase(local.getEmail())) {
            username = local.getUsername();
        }
        if (username == null || username.isBlank()) {
            throw new CloudStore.CloudException(
                "No migrated profile was found. Register with your existing email and a username, then log in again.", false);
        }
        String nameKey = CloudKeys.of(username);
        Map<String, String> profile = Map.of("uid", uid, "email", email, "username", username);
        store.update(Map.of("profiles/" + nameKey, profile, "accountKeys/" + uid, nameKey));
        return fromNode(com.dnd.data.JsonMappers.create().valueToTree(profile)).orElseThrow();
    }

    private Optional<User> fromNode(JsonNode node) {
        if (node == null) return Optional.empty();
        if (!node.isObject() || node.size() != 3 || !node.path("uid").isTextual() || node.path("uid").asText().isBlank()
            || !node.path("email").isTextual() || !com.dnd.auth.AuthService.isValidEmail(node.path("email").asText())
            || !node.path("username").isTextual() || node.path("username").asText().isBlank()) {
            throw new CloudStore.CloudException("The cloud account profile is malformed. Contact the administrator.", false);
        }
        User user = new User();
        user.setId(node.path("uid").asText());
        user.setUsername(node.path("username").asText());
        user.setEmail(node.path("email").asText());
        user.setProviderUid(node.path("uid").asText());
        user.setEmailVerified(true);
        return Optional.of(user);
    }
}
