package com.dnd.security;

import com.dnd.cloud.CloudStore;
import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.*;

/** Password-unlocked account keys. Only encrypted private-key bundles are stored online. */
public final class PrivateAccount implements AutoCloseable {
    private static final ObjectMapper MAPPER = JsonMappers.create()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public record Identity(String uid, String email, String name, String encryptionKey, String signingKey) {
        @Override public String toString() { return name + " (" + email + ")"; }
    }
    public record Vault(int version, Identity identity, String passwordBox, String recoveryBox) { }
    private record Keys(String encryptionPrivateKey, String signingPrivateKey) { }
    public record Setup(Vault vault, String recoveryKey) { }
    public record AccessRequest(String fileId, String campaignId, String fileName, String requesterUid,
                                long requestedAt, String status) { }

    private final CloudStore store;
    private final Identity identity;
    private PrivateKey encryptionKey;
    private PrivateKey signingKey;
    private final Map<String, Identity> trustedIdentities = new java.util.concurrent.ConcurrentHashMap<>();

    private PrivateAccount(CloudStore store, Vault vault, Keys keys) {
        this.store = Objects.requireNonNull(store);
        identity = vault.identity();
        trustedIdentities.put(identity.uid(), identity);
        encryptionKey = PrivateFileCrypto.privateKey(keys.encryptionPrivateKey());
        signingKey = PrivateFileCrypto.privateKey(keys.signingPrivateKey());
        PrivateFileCrypto.verify("account-key-check", PrivateFileCrypto.sign("account-key-check", signingKey),
            PrivateFileCrypto.publicKey(identity.signingKey()));
        byte[] check = PrivateFileCrypto.dataKey();
        byte[] unwrapped = PrivateFileCrypto.unwrap(PrivateFileCrypto.wrap(check,
            PrivateFileCrypto.publicKey(identity.encryptionKey())), encryptionKey);
        if (!Arrays.equals(check, unwrapped)) throw new SecurityException("Account encryption keys do not match.");
        Arrays.fill(check, (byte) 0);
        Arrays.fill(unwrapped, (byte) 0);
    }

    public static Setup prepare(String uid, String email, String name, String password) {
        FirebasePrivateStore.validateKey(uid);
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Account password is required.");
        KeyPair encryption = PrivateFileCrypto.keyPair();
        KeyPair signing = PrivateFileCrypto.keyPair();
        Identity identity = new Identity(uid, email, name, PrivateFileCrypto.encode(encryption.getPublic()),
            PrivateFileCrypto.encode(signing.getPublic()));
        String keys = json(new Keys(PrivateFileCrypto.encode(encryption.getPrivate()), PrivateFileCrypto.encode(signing.getPrivate())));
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String recovery = HexFormat.of().formatHex(random);
        Arrays.fill(random, (byte) 0);
        return new Setup(new Vault(1, identity, SessionCipher.encrypt(keys, password),
            SessionCipher.encrypt(keys, recovery)), recovery);
    }

    public static Vault load(CloudStore store, String uid) {
        FirebasePrivateStore.validateKey(uid);
        JsonNode value = store.get("accounts/" + uid);
        Vault vault = value == null ? null : read(value, Vault.class);
        if (vault != null && (vault.identity() == null || !uid.equals(vault.identity().uid()))) {
            throw new SecurityException("Private-file account identity mismatch.");
        }
        return vault;
    }

    public static PrivateAccount finishSetup(CloudStore store, Setup setup, String password) {
        Vault existing = load(store, setup.vault().identity().uid());
        if (existing != null) throw new IllegalStateException("Keys were created on another device. Unlock those keys instead.");
        store.put("accounts/" + setup.vault().identity().uid(), setup.vault());
        return unlock(store, setup.vault(), password);
    }

    public static PrivateAccount unlock(CloudStore store, Vault vault, String password) {
        validate(vault);
        if (store instanceof FirebasePrivateStore firebase && !firebase.uid().equals(vault.identity().uid())) {
            throw new SecurityException("Private-file account identity mismatch.");
        }
        PrivateAccount account = new PrivateAccount(store, vault, read(SessionCipher.decrypt(vault.passwordBox(), password), Keys.class));
        account.publishIdentity();
        return account;
    }

    public static PrivateAccount recover(CloudStore store, Vault vault, String recovery, String newPassword) {
        validate(vault);
        if (store instanceof FirebasePrivateStore firebase && !firebase.uid().equals(vault.identity().uid())) {
            throw new SecurityException("Private-file account identity mismatch.");
        }
        if (newPassword == null || newPassword.isBlank()) throw new IllegalArgumentException("Account password is required.");
        String normalized = recovery == null ? "" : recovery.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Enter the complete 64-character recovery key.");
        String keys = SessionCipher.decrypt(vault.recoveryBox(), normalized);
        PrivateAccount account = new PrivateAccount(store, vault, read(keys, Keys.class));
        Vault updated = new Vault(1, vault.identity(), SessionCipher.encrypt(keys, newPassword), vault.recoveryBox());
        store.put("accounts/" + account.uid(), updated);
        account.publishIdentity();
        return account;
    }

    private static void validate(Vault vault) {
        if (vault == null || vault.version() != 1 || vault.identity() == null) {
            throw new SecurityException("Unsupported or missing private-file account.");
        }
        FirebasePrivateStore.validateKey(vault.identity().uid());
    }

    private void publishIdentity() { store.put("identities/" + uid(), identity); }
    public Identity identity() { return identity; }
    public String uid() { return identity.uid(); }
    public boolean isUnlocked() { return signingKey != null && encryptionKey != null; }
    PrivateKey encryptionKey() { requireUnlocked(); return encryptionKey; }
    PrivateKey signingKey() { requireUnlocked(); return signingKey; }
    private void requireUnlocked() {
        if (!isUnlocked()) throw new SecurityException("Private-file keys are locked. Sign in again.");
    }

    public Identity identity(String uid) {
        requireUnlocked();
        FirebasePrivateStore.validateKey(uid);
        Identity cached = trustedIdentities.get(uid);
        if (cached != null) return cached;
        JsonNode value = store.get("identities/" + uid);
        if (value == null) throw new SecurityException("The file's encryption identity is unavailable. Connect to private cloud.");
        Identity found = read(value, Identity.class);
        if (!uid.equals(found.uid())) throw new SecurityException("Encryption identity mismatch.");
        trustedIdentities.put(uid, found);
        return found;
    }

    public List<Identity> identities() {
        requireUnlocked();
        JsonNode values = store.get("identities");
        if (values == null) return List.of();
        List<Identity> result = new ArrayList<>();
        values.fields().forEachRemaining(entry -> {
            Identity found = read(entry.getValue(), Identity.class);
            if (!entry.getKey().equals(found.uid())) throw new SecurityException("Encryption identity mismatch.");
            trustedIdentities.put(found.uid(), found);
            result.add(found);
        });
        return List.copyOf(result);
    }

    public void requestAccess(String ownerUid, String fileId, String campaignId, String fileName) {
        requireUnlocked();
        FirebasePrivateStore.validateKey(ownerUid);
        FirebasePrivateStore.validateKey(fileId);
        FirebasePrivateStore.validateKey(campaignId);
        if (fileName == null || fileName.length() > 256) throw new IllegalArgumentException("Invalid requested file name.");
        AccessRequest request = new AccessRequest(fileId, campaignId, fileName, uid(), System.currentTimeMillis(), "pending");
        String path = "requests/" + ownerUid + "/" + uid() + "/" + fileId;
        JsonNode previous = store.get(path);
        if (previous == null || !"pending".equals(previous.path("status").asText())) store.put(path, request);
        store.put("outgoing/" + uid() + "/" + fileId, Map.of("ownerUid", ownerUid));
    }

    public List<AccessRequest> requests() {
        requireUnlocked();
        JsonNode values = store.get("requests/" + uid());
        if (values == null) return List.of();
        List<AccessRequest> result = new ArrayList<>();
        values.fields().forEachRemaining(sender -> sender.getValue().fields().forEachRemaining(entry -> {
            AccessRequest request = read(entry.getValue(), AccessRequest.class);
            if (!sender.getKey().equals(request.requesterUid()) || !entry.getKey().equals(request.fileId())) {
                throw new SecurityException("Invalid access request identity.");
            }
            result.add(request);
        }));
        return result.stream().sorted(Comparator.comparingLong(AccessRequest::requestedAt).reversed()).toList();
    }

    public Map<AccessRequest, String> outgoing() {
        requireUnlocked();
        JsonNode values = store.get("outgoing/" + uid());
        if (values == null) return Map.of();
        Map<AccessRequest, String> result = new LinkedHashMap<>();
        values.fields().forEachRemaining(entry -> {
            String ownerUid = entry.getValue().path("ownerUid").asText();
            FirebasePrivateStore.validateKey(ownerUid);
            FirebasePrivateStore.validateKey(entry.getKey());
            JsonNode request = store.get("requests/" + ownerUid + "/" + uid() + "/" + entry.getKey());
            if (request != null) result.put(read(request, AccessRequest.class), ownerUid);
        });
        return result;
    }

    public void answer(AccessRequest request, boolean approved) {
        requireUnlocked();
        FirebasePrivateStore.validateKey(request.requesterUid());
        FirebasePrivateStore.validateKey(request.fileId());
        String path = "requests/" + uid() + "/" + request.requesterUid() + "/" + request.fileId();
        store.put(path, new AccessRequest(request.fileId(), request.campaignId(), request.fileName(),
            request.requesterUid(), request.requestedAt(), approved ? "approved" : "denied"));
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot encode private-file data.");
        }
    }
    static <T> T read(String value, Class<T> type) {
        try {
            return MAPPER.readValue(value, type);
        } catch (IOException e) {
            throw new SecurityException("Unreadable private-file data.");
        }
    }
    private static <T> T read(JsonNode value, Class<T> type) { return read(value.toString(), type); }

    @Override
    public void close() {
        encryptionKey = null;
        signingKey = null;
        trustedIdentities.clear();
        if (store instanceof FirebasePrivateStore firebase) firebase.close();
    }
}
