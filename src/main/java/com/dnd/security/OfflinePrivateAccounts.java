package com.dnd.security;

import com.dnd.cloud.LocalCloudStore;
import com.dnd.cloud.CloudStore;
import com.dnd.data.JsonMappers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * A device-local copy of the password-encrypted private-key vault and public identities.
 * The vault never contains the password or an unencrypted private key.
 */
public final class OfflinePrivateAccounts {
    public record Cache(PrivateAccount.Vault vault, List<PrivateAccount.Identity> identities) { }

    private OfflinePrivateAccounts() { }

    public static void save(Path directory, PrivateAccount.Vault vault, List<PrivateAccount.Identity> identities) {
        if (vault == null || vault.identity() == null) throw new IllegalArgumentException("A verified vault is required.");
        String uid = vault.identity().uid();
        FirebasePrivateStore.validateKey(uid);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".private-vault-", ".tmp");
            Files.writeString(temporary, JsonMappers.create().writeValueAsString(new Cache(vault, List.copyOf(identities))),
                StandardCharsets.UTF_8);
            Files.move(temporary, directory.resolve(uid + ".json"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the encrypted offline private-account cache.", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { throw new UncheckedIOException("Could not remove the temporary private-account cache.", e); }
            }
        }
    }

    public static PrivateAccount unlock(Path directory, String uid, String email, String password) {
        FirebasePrivateStore.validateKey(uid);
        Cache cache;
        try {
            cache = JsonMappers.create().readValue(directory.resolve(uid + ".json").toFile(), Cache.class);
        } catch (IOException e) {
            throw new UncheckedIOException("No readable offline private-account cache. Sign in online and unlock first.", e);
        }
        if (cache.vault() == null || cache.vault().identity() == null || cache.identities() == null
            || !uid.equals(cache.vault().identity().uid()) || email == null
            || !email.equalsIgnoreCase(cache.vault().identity().email())) {
            throw new SecurityException("Offline private-account identity mismatch.");
        }
        LocalCloudStore identities = new LocalCloudStore();
        for (PrivateAccount.Identity identity : cache.identities()) {
            if (identity == null) throw new SecurityException("Invalid offline private identity.");
            FirebasePrivateStore.validateKey(identity.uid());
            identities.put("identities/" + identity.uid(), identity);
        }
        CloudStore readOnly = new CloudStore() {
            @Override public com.fasterxml.jackson.databind.JsonNode get(String path) {
                if (!path.equals("identities") && !path.startsWith("identities/")) {
                    throw new CloudException("This operation needs an online connection.", true);
                }
                return identities.get(path);
            }
            @Override public void put(String path, Object value) {
                throw new CloudException("This operation needs an online connection.", true);
            }
            @Override public void delete(String path) {
                throw new CloudException("This operation needs an online connection.", true);
            }
        };
        return PrivateAccount.unlockWithoutPublishing(readOnly, cache.vault(), password);
    }
}
