package com.dnd.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Encrypted player snapshots in the authenticated v2 namespace. Only the verified host
 * can write; player tokens contain a random read/decryption capability, never host credentials.
 */
public final class FirebaseSessionSync {

    private static final Logger LOGGER = Logger.getLogger(FirebaseSessionSync.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final String databaseUrl;   // e.g. https://my-campaign-default-rtdb.firebaseio.com
    private final String sessionId;     // node name under /sessions/
    private final String passphrase;    // shared secret used by SessionCipher
    private final com.dnd.cloud.CloudStore writer;
    private final String hostUid;
    private final String campaignId;
    private com.dnd.chat.SessionChatRelay chatRelay;
    private com.dnd.chat.SessionChatRelay guestChatRelay;
    private com.dnd.chat.SessionChat chat;
    private java.nio.file.Path chatCampaign;
    private final java.util.Map<String, Integer> chatFailures = new java.util.HashMap<>();
    private final java.util.Map<String, Long> chatLocks = new java.util.HashMap<>();

    /** Latest decrypted snapshot received from the SSE stream. {@code null} until first event. */
    private final AtomicReference<String> latestSnapshot = new AtomicReference<>(null);
    /** Background SSE listener thread; {@code null} when not listening. */
    private volatile Thread listenerThread;

    public FirebaseSessionSync(String databaseUrl, String sessionId, String passphrase) {
        this(databaseUrl, sessionId, passphrase, null, null, null);
    }

    private FirebaseSessionSync(String databaseUrl, String sessionId, String passphrase,
                               com.dnd.cloud.CloudStore writer, String hostUid, String campaignId) {
        URI uri = URI.create(databaseUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null || !sessionId.matches("[a-f0-9]{64}")
            || !passphrase.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid v2 online session token.");
        }
        this.databaseUrl = databaseUrl.replaceAll("/$", "");
        this.sessionId = sessionId;
        this.passphrase = passphrase;
        this.writer = writer;
        this.hostUid = hostUid;
        this.campaignId = campaignId;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    public static FirebaseSessionSync host(String campaignId) {
        FirebasePrivateStore.validateKey(campaignId);
        FirebasePrivateStore account = com.dnd.cloud.Cloud.account();
        return new FirebaseSessionSync(com.dnd.cloud.Cloud.config().databaseUrl(), generateSessionId(),
            generateSessionId(), account.scope("almerion-live"), account.uid(), campaignId);
    }

    public String token() {
        return generateToken(databaseUrl, sessionId, passphrase);
    }

    public boolean isHost() {
        return writer != null;
    }

    public void startChat(java.nio.file.Path campaign) {
        requireHost();
        if (chatRelay != null) return;
        var acquired = com.dnd.chat.SessionChat.acquire(campaign);
        com.dnd.chat.SessionChatRelay relay = null;
        com.dnd.chat.SessionChatRelay guestRelay = null;
        try {
            var account = com.dnd.cloud.Cloud.account();
            var directory = account.scope("almerion-cloud");
            var mailboxes = account.scope("almerion-chat");
            relay = new com.dnd.chat.SessionChatRelay(mailboxes, sessionId, acquired, (uid, request) -> {
                var profile = new com.dnd.cloud.CloudProfiles(directory).find(request.username()).orElse(null);
                if (profile == null || !uid.equals(profile.getProviderUid())) {
                    throw new SecurityException("Your cloud profile is missing.");
                }
                return authorizeChat(campaign, "account:" + uid, profile.getUsername(), request.character(), request.password());
            });
            mailboxes.put(sessionId + "/guestKey", guestCapability());
            guestRelay = new com.dnd.chat.SessionChatRelay(mailboxes, guestChannel(), acquired, (uid, request) ->
                authorizeChat(campaign, "guest", null, request.character(), request.password()));
            chatCampaign = campaign;
            chat = acquired;
            chatRelay = relay;
            guestChatRelay = guestRelay;
            relay.start();
            guestRelay.start();
        } catch (RuntimeException error) {
            if (relay != null) relay.close();
            if (guestRelay != null) guestRelay.close();
            chatRelay = null;
            guestChatRelay = null;
            chat = null;
            chatCampaign = null;
            com.dnd.chat.SessionChat.release(campaign, acquired);
            throw error;
        }
    }

    synchronized String authorizeChat(java.nio.file.Path campaign, String identity, String username, String character, String password) {
        var pc = character == null ? null : new com.dnd.data.CampaignRepositories(campaign).players().getById(character);
        if (username == null) identity += ":" + (pc == null ? "unknown" : pc.getId());
        if (chatLocks.getOrDefault(identity, 0L) > System.currentTimeMillis()) {
            throw new SecurityException("Too many chat sign-in attempts. Wait a minute and try again.");
        }
        if (pc == null || !(username != null && pc.isOwnedBy(username))
            && (!pc.hasPassword() || password == null || !pc.checkPassword(password))) {
            if (chatFailures.merge(identity, 1, Integer::sum) >= 5) {
                chatLocks.put(identity, System.currentTimeMillis() + 60_000);
                chatFailures.remove(identity);
            }
            throw new SecurityException("Choose your own character, or enter its character password.");
        }
        chatFailures.remove(identity);
        return com.dnd.chat.SessionChat.peerId(pc);
    }

    String guestCapability() {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(("ALMERION-GUEST-CHAT/1:" + sessionId + ":" + passphrase).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    String guestChannel() { return sessionId + "/guests/" + guestCapability(); }

    public String chatFailure() {
        String failure = chatRelay == null ? null : chatRelay.failure();
        return failure != null || guestChatRelay == null ? failure : guestChatRelay.failure();
    }

    public com.dnd.chat.SessionChat.Connection chatClient(String character, String password) {
        if (isHost()) throw new IllegalStateException("Use the host chat panel.");
        if (password != null) {
            if (password.isBlank()) throw new SecurityException("Enter the character password to use guest chat.");
            return new com.dnd.chat.SessionChatRelay.Client(new com.dnd.cloud.FirebaseCloudStore(databaseUrl, "almerion-chat"),
                guestChannel(), "guest", "", character, password);
        }
        if (!databaseUrl.equals(com.dnd.cloud.Cloud.config().databaseUrl().replaceAll("/$", ""))) {
            throw new SecurityException("The session uses a different cloud project.");
        }
        var account = com.dnd.cloud.Cloud.account();
        var directory = account.scope("almerion-cloud");
        var key = directory.get("accountKeys/" + account.uid());
        var profile = key == null ? null : directory.get("profiles/" + key.asText());
        if (profile == null || !account.uid().equals(profile.path("uid").asText())) {
            throw new SecurityException("Your cloud profile is missing.");
        }
        return new com.dnd.chat.SessionChatRelay.Client(account.scope("almerion-chat"), sessionId, account.uid(),
            profile.path("username").asText(), character, password);
    }

    public void closeChat() {
        if (chatRelay != null) chatRelay.close();
        if (guestChatRelay != null) guestChatRelay.close();
        chatRelay = null;
        guestChatRelay = null;
        if (chat != null) com.dnd.chat.SessionChat.release(chatCampaign, chat);
        chat = null;
        chatCampaign = null;
        chatFailures.clear();
        chatLocks.clear();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Encrypts {@code plainJson} and writes it to Firebase.
     *
     * @param plainJson raw (unencrypted) JSON to persist
     * @throws SyncException on network or HTTP errors
     */
    public void push(String plainJson) {
        requireHost();
        String ciphertext = SessionCipher.encrypt(plainJson, passphrase);
        try {
            writer.put(sessionId, java.util.Map.of("hostUid", hostUid, "campaignId", campaignId,
                "expiresAt", System.currentTimeMillis() + 43_200_000L, "snapshot", ciphertext));
        } catch (com.dnd.cloud.CloudStore.CloudException | IllegalStateException e) {
            throw new SyncException("Could not publish the session. Check your login, GM membership, and cloud rules.", e);
        }
        LOGGER.fine("Session pushed to Firebase [" + sessionId + "]");
    }

    /**
     * Fetches the session snapshot from Firebase and decrypts it.
     *
     * @return the original plaintext JSON, or {@code null} if no data exists yet
     * @throws SyncException on network or HTTP errors
     * @throws SessionCipher.CipherException if decryption fails (wrong passphrase / corrupt data)
     */
    public String pull() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(nodeUrl()))
                .timeout(TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SyncException("Firebase GET failed with HTTP " + response.statusCode()
                    + ". The host may have ended the session or its access expired.");
        }

        String body = response.body().trim();
        if ("null".equals(body) || body.isEmpty()) {
            return null;   // session not yet started
        }

        // Firebase wraps string values in JSON quotes — strip them
        try {
            var node = com.dnd.data.JsonMappers.create().readTree(body);
            if (node == null || !node.isTextual()) throw new IOException("Not a snapshot");
            return SessionCipher.decrypt(node.asText(), passphrase);
        } catch (IOException e) {
            throw new SyncException("The online snapshot is malformed.", e);
        }
    }

    /**
     * Deletes the session node from Firebase (call when the session ends).
     *
     * @throws SyncException on network or HTTP errors
     */
    public void delete() {
        requireHost();
        try {
            if (chatRelay != null) chatRelay.close();
            if (guestChatRelay != null) guestChatRelay.close();
            com.dnd.cloud.Cloud.account().scope("almerion-chat").delete(sessionId);
            closeChat();
            writer.delete(sessionId);
        } catch (com.dnd.cloud.CloudStore.CloudException | IllegalStateException e) {
            throw new SyncException("Could not end the online session. Check your connection and account permissions.", e);
        }
        LOGGER.fine("Session deleted from Firebase [" + sessionId + "]");
    }

    private void requireHost() {
        if (!isHost()) throw new SyncException("Player tokens cannot write or delete hosted sessions.");
    }

    // -------------------------------------------------------------------------
    // SSE streaming — push-based, zero-poll live updates
    // -------------------------------------------------------------------------

    /**
     * Opens a persistent Server-Sent Events connection to Firebase and fires
     * {@code onUpdate} with the decrypted plaintext JSON every time the DM
     * pushes new state.  The connection runs in a background daemon thread so
     * it never blocks the CLI.
     *
     * <p>Firebase SSE wire format (one logical event is two lines):</p>
     * <pre>
     * event: put
     * data: {"path":"/","data":"&lt;ciphertext&gt;"}
     * </pre>
     *
     * <p>Only {@code put} events that carry real data are forwarded; keep-alive
     * comments ({@code :}) and {@code cancel} events are silently ignored.</p>
     *
     * <p>If the connection drops, the thread attempts a reconnect after 5 s and
     * keeps retrying until {@link #stopListening()} is called.</p>
     *
     * @param onUpdate called on the listener thread with each new decrypted snapshot
     */
    public void startListening(Consumer<String> onUpdate) {
        stopListening();   // ensure no stale thread

        listenerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    connectAndStream(onUpdate);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "SSE connection lost, reconnecting in 5 s", e);
                }
                // Back-off before reconnect — skip if interrupted
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            LOGGER.fine("SSE listener stopped [" + sessionId + "]");
        }, "firebase-sse-" + sessionId);

        listenerThread.setDaemon(true);
        listenerThread.start();
        LOGGER.fine("SSE listener started [" + sessionId + "]");
    }

    /** Stops the background SSE listener thread if one is running. */
    public void stopListening() {
        Thread t = listenerThread;
        if (t != null) {
            t.interrupt();
            listenerThread = null;
        }
    }

    /**
     * Returns the last decrypted snapshot received via the SSE stream, or
     * {@code null} if no event has arrived yet.  Never makes a network call.
     */
    public String getLatestSnapshot() {
        return latestSnapshot.get();
    }

    /** Node name this session lives under, used to name the player's local cache of it. */
    public String getSessionId() {
        return sessionId;
    }

    /** Blocks until the SSE stream closes or the thread is interrupted. */
    private void connectAndStream(Consumer<String> onUpdate) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(nodeUrl()))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        // Use an InputStream body handler so we can read line-by-line without buffering the whole response
        HttpResponse<InputStream> response = http.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new SyncException("SSE connect failed with HTTP " + response.statusCode());
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

            String eventType = null;
            String dataLine = null;

            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("SSE listener interrupted");
                }

                if (line.startsWith("event:")) {
                    eventType = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    dataLine = line.substring("data:".length()).trim();
                } else if (line.isEmpty()) {
                    // blank line = end of one SSE event, dispatch it
                    if ("put".equals(eventType) && dataLine != null) {
                        handlePutEvent(dataLine, onUpdate);
                    }
                    eventType = null;
                    dataLine = null;
                }
                // lines starting with ':' are keep-alive comments — ignore
            }
        }
    }

    /**
     * Parses a Firebase {@code put} event data payload and fires {@code onUpdate}
     * if the payload contains real (non-null) data.
     *
     * <p>Firebase wraps the stored value in {@code {"path":"/","data":"<value>"}}.
     * We extract the {@code data} field and decrypt it.</p>
     */
    private void handlePutEvent(String eventData, Consumer<String> onUpdate) {
        try {
            var event = com.dnd.data.JsonMappers.create().readTree(eventData);
            if (!"/".equals(event.path("path").asText()) || !event.path("data").isTextual()) return;
            String ciphertext = event.path("data").asText();

            String plaintext = SessionCipher.decrypt(ciphertext, passphrase);
            latestSnapshot.set(plaintext);
            onUpdate.accept(plaintext);
        } catch (SessionCipher.CipherException e) {
            LOGGER.log(Level.WARNING, "Could not decrypt SSE payload — passphrase mismatch?", e);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not parse SSE event data", e);
        }
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------

    /**
     * Generates a shareable session token encoding the database URL, session ID,
     * and shared passphrase.  The DM shares this string with all players.
     *
     * <p>Token format: {@code Base64(databaseUrl + "|2|" + sessionId + "|" + passphrase)}
     */
    public static String generateToken(String databaseUrl, String sessionId, String passphrase) {
        new FirebaseSessionSync(databaseUrl, sessionId, passphrase);
        String raw = databaseUrl + "|2|" + sessionId + "|" + passphrase;
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a token produced by {@link #generateToken} into a ready-to-use
     * {@link FirebaseSessionSync} instance.
     *
     * @throws IllegalArgumentException if the token is malformed
     */
    public static FirebaseSessionSync fromToken(String token) {
        try {
            String raw = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 4 || !"2".equals(parts[1])) {
                throw new IllegalArgumentException("This token is not a v2 session. Ask the host to update Almerion and share a new token.");
            }
            return new FirebaseSessionSync(parts[0], parts[2], parts[3]);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not decode session token", e);
        }
    }

    /**
     * Generates a random 256-bit capability (64 hexadecimal characters).
     * Suitable for use as the {@code sessionId} parameter.
     */
    public static String generateSessionId() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        // hex-encode to get a URL-safe alphanumeric string
        return HexFormat.of().formatHex(bytes);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String nodeUrl() {
        return databaseUrl + "/almerion-live/" + sessionId + "/snapshot.json";
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SyncException("Network error communicating with Firebase", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SyncException("Firebase request interrupted", e);
        }
    }

    /** Unchecked wrapper for network / HTTP errors. */
    public static final class SyncException extends RuntimeException {
        public SyncException(String message) {
            super(message);
        }
        public SyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
