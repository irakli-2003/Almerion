package com.dnd.chat;

import com.dnd.cloud.CloudStore;
import com.dnd.data.JsonMappers;
import com.dnd.security.SessionChatCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.logging.*;

/** Encrypted fixed-slot mailboxes. Only the current host decrypts and authorizes requests. */
public final class SessionChatRelay implements AutoCloseable {
    public record Wire(String connection, String id, String key, String data) { }
    public record Request(String username, String character, String password, String replyKey, SessionChat.Command command) { }
    public record Reply(String error, SessionChat.View view) { }
    private static final Logger LOG = Logger.getLogger(SessionChatRelay.class.getName());
    private final CloudStore store;
    private final String session;
    private final SessionChat chat;
    private final BiFunction<String, Request, String> authorize;
    private final SessionChatCrypto crypto = new SessionChatCrypto();
    private final Map<String, String> handled = new HashMap<>();
    private ScheduledExecutorService worker;
    private volatile String failure;

    public SessionChatRelay(CloudStore store, String session, SessionChat chat, BiFunction<String, Request, String> authorize) {
        this.store = store;
        this.session = session;
        this.chat = chat;
        this.authorize = authorize;
        store.put(session + "/publicKey", crypto.publicKey());
    }

    public void start() {
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "almerion-chat-host");
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(() -> {
            try { tick(); failure = null; }
            catch (RuntimeException error) {
                failure = "Internet chat is temporarily unavailable. Check your connection and cloud permissions.";
                LOG.log(Level.WARNING, failure, error);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public String failure() { return failure; }

    public synchronized void tick() {
        JsonNode inbox = store.get(session + "/inbox");
        if (inbox == null || inbox.isNull()) return;
        inbox.fields().forEachRemaining(account -> account.getValue().fields().forEachRemaining(connection ->
            process(account.getKey(), connection.getKey(), connection.getValue())));
    }

    private void process(String uid, String connection, JsonNode node) {
        String channel = uid + "/" + connection;
        Wire wire;
        Request request;
        try {
            wire = JsonMappers.create().convertValue(node, Wire.class);
            if (wire == null || wire.connection() == null || !wire.connection().matches("[a-f0-9-]{36}")
                || wire.id() == null || !wire.id().matches("[a-f0-9-]{36}")
                || wire.data() == null || wire.data().length() > 32768 || wire.key() == null || wire.key().length() > 1024) {
                throw new SecurityException("Invalid encrypted chat request.");
            }
            String fingerprint = wire.connection() + ":" + wire.id();
            if ((fingerprint + ":invalid-key").equals(handled.get(channel))) return;
            if (fingerprint.equals(handled.get(channel))) {
                JsonNode reply = store.get(session + "/outbox/" + channel);
                if (reply != null && wire.connection().equals(reply.path("connection").asText())
                    && wire.id().equals(reply.path("id").asText())) return;
            }
            request = JsonMappers.create().readValue(crypto.open(new SessionChatCrypto.Sealed(wire.key(), wire.data()),
                context(session, uid + "/" + wire.connection(), wire.id(), "request")), Request.class);
            if (request.replyKey() == null || request.replyKey().length() > 1024 || request.command() == null
                || !wire.id().equals(request.command().id())) throw new SecurityException("Invalid encrypted chat request.");
        } catch (java.io.IOException | RuntimeException error) {
            LOG.log(Level.WARNING, "Rejected malformed encrypted chat request", error);
            return;
        }
        Reply reply;
        try {
            reply = new Reply(null, chat.execute(authorize.apply(uid, request), request.command()));
        } catch (IllegalArgumentException | IllegalStateException | SecurityException error) {
            reply = new Reply(error.getMessage(), null);
        } catch (RuntimeException error) {
            LOG.log(Level.WARNING, "Could not process session chat", error);
            reply = new Reply("The host could not process chat. Ask the GM to check the session.", null);
        }
        try {
            var sealed = SessionChatCrypto.seal(JsonMappers.create().writeValueAsString(reply), request.replyKey(),
                context(session, uid + "/" + wire.connection(), wire.id(), "reply"));
            JsonNode current = store.get(session + "/inbox/" + channel);
            if (current == null || !wire.id().equals(current.path("id").asText())
                || !wire.connection().equals(current.path("connection").asText())) return;
            store.put(session + "/outbox/" + channel, new Wire(wire.connection(), wire.id(), sealed.key(), sealed.data()));
            handled.put(channel, wire.connection() + ":" + wire.id());
        } catch (SecurityException error) {
            LOG.log(Level.WARNING, "Rejected invalid chat reply key", error);
            handled.put(channel, wire.connection() + ":" + wire.id() + ":invalid-key");
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Could not encode chat reply.", error);
        }
    }

    private static String context(String session, String uid, String id, String direction) {
        return "ALMERION-CHAT/1:" + session + ":" + uid + ":" + id + ":" + direction;
    }

    @Override public void close() {
        if (worker != null) worker.shutdownNow();
        synchronized (this) { handled.clear(); }
    }

    public static final class Client implements SessionChat.Connection {
        private final CloudStore store;
        private final String session;
        private final String uid;
        private final String connection = UUID.randomUUID().toString();
        private volatile int slot = -1;
        private final java.util.concurrent.atomic.AtomicBoolean cleanupStarted = new java.util.concurrent.atomic.AtomicBoolean();
        private final String character;
        private final String username;
        private volatile String password;
        private final String hostKey;
        private final SessionChatCrypto crypto = new SessionChatCrypto();
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

        public Client(CloudStore store, String session, String uid, String character, String password) {
            this(store, session, uid, uid, character, password);
        }

        public Client(CloudStore store, String session, String uid, String username, String character, String password) {
            this.store = store;
            this.session = session;
            this.uid = uid;
            this.character = character;
            this.username = username;
            this.password = password;
            JsonNode key = store.get(session + "/publicKey");
            if (key == null || !key.isTextual()) throw new IllegalStateException("The host has not started session chat.");
            hostKey = key.asText();
        }

        @Override public synchronized SessionChat.View execute(SessionChat.Command command) {
            if (closed.get()) throw new IllegalStateException("The chat connection is closed.");
            try {
                var sealed = SessionChatCrypto.seal(JsonMappers.create().writeValueAsString(
                    new Request(username, character, password, crypto.publicKey(), command)), hostKey,
                    context(session, uid + "/" + connection, command.id(), "request"));
                Wire outgoing = new Wire(connection, command.id(), sealed.key(), sealed.data());
                if (sealed.data().length() > 32768) throw new IllegalArgumentException("The chat request is too large.");
                if (slot < 0) {
                    for (int candidate = 0; candidate < 32; candidate++) {
                        JsonNode occupied = store.get(session + "/inbox/" + uid + "/" + candidate);
                        if (occupied != null && !connection.equals(occupied.path("connection").asText())) continue;
                        try {
                            store.put(session + "/inbox/" + uid + "/" + candidate, outgoing);
                            slot = candidate;
                            break;
                        } catch (CloudStore.CloudException collision) {
                            if (collision.isOffline()) throw collision;
                            store.get(session + "/publicKey");
                            if (candidate == 31) throw new IllegalStateException("Close another chat connection before opening a new one.", collision);
                        }
                    }
                    if (slot < 0) throw new IllegalStateException("Close another chat connection before opening a new one.");
                } else store.put(session + "/inbox/" + uid + "/" + slot, outgoing);
                long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
                while (System.nanoTime() < deadline) {
                    if (closed.get()) throw new IllegalStateException("The chat connection is closed.");
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    JsonNode node = store.get(session + "/outbox/" + uid + "/" + slot);
                    if (node != null && connection.equals(node.path("connection").asText()) && command.id().equals(node.path("id").asText())) {
                        Wire wire = JsonMappers.create().treeToValue(node, Wire.class);
                        Reply reply = JsonMappers.create().readValue(crypto.open(new SessionChatCrypto.Sealed(wire.key(), wire.data()),
                            context(session, uid + "/" + connection, command.id(), "reply")), Reply.class);
                        if (reply.error() != null) throw new IllegalStateException(reply.error());
                        if (reply.view() == null) throw new IllegalStateException("The chat reply is incomplete.");
                        return reply.view();
                    }
                    Thread.sleep(250);
                }
                throw new IllegalStateException("Chat delivery was not confirmed. Retry the pending action.");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Chat delivery was interrupted. Retry the pending action.", error);
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Could not read the encrypted chat reply.", error);
            } finally {
                if (closed.get()) cleanup();
            }
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            password = null;
            cleanup();
        }

        private void cleanup() {
            if (!cleanupStarted.compareAndSet(false, true)) return;
            Thread cleanup = new Thread(() -> {
                synchronized (Client.this) {
                    if (slot < 0) return;
                    try {
                        store.delete(session + "/inbox/" + uid + "/" + slot);
                        store.delete(session + "/outbox/" + uid + "/" + slot);
                    } catch (RuntimeException error) {
                        LOG.log(Level.WARNING, "Chat closed locally, but encrypted mailbox cleanup failed", error);
                    }
                }
            }, "almerion-chat-cleanup");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }
}
