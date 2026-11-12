package com.dnd.chat;

import com.dnd.cloud.LocalCloudStore;
import com.dnd.cloud.CloudStore;
import com.dnd.security.SessionChatCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class SessionChatRelayTest {
    @Test public void encryptedMailboxesAuthenticateActorsAndSupportMultipleDevicesWithoutSnapshotDisclosure() {
        var store = new LocalCloudStore();
        try (var chat = new SessionChat(() -> List.of(new SessionChat.Peer("a", "Alice"), new SessionChat.Peer("b", "Bob")));
             var relay = new SessionChatRelay(store, "session", chat, (uid, request) -> {
                 if (!uid.equals(request.character())) throw new SecurityException("Wrong character.");
                 return uid;
             })) {
            relay.start();
            try (var alice = new SessionChatRelay.Client(store, "session", "a", "a", "private-password");
                 var bob = new SessionChatRelay.Client(store, "session", "b", "b", null);
                 var secondDevice = new SessionChatRelay.Client(store, "session", "a", "a", "private-password")) {
                var create = new SessionChat.Command(UUID.randomUUID().toString(), "create", null, List.of("b"), "Secret conversation", "");
                String thread = alice.execute(create).selected();
                var send = new SessionChat.Command(UUID.randomUUID().toString(), "send", thread, List.of(), "", "Secret message");
                alice.execute(send);
                alice.execute(send);
                assertEquals(1, bob.execute(SessionChat.Command.poll(thread)).messages().size());
                assertEquals("Secret message", secondDevice.execute(SessionChat.Command.poll(thread)).messages().getFirst().text());
                String stored = store.get("session").toString();
                assertFalse(stored.contains("Secret message"));
                assertFalse(stored.contains("Secret conversation"));
                assertFalse(stored.contains("private-password"));
                assertFalse(stored.contains("\"character\""));
            }
            try (var impersonator = new SessionChatRelay.Client(store, "session", "a", "b", null)) {
                assertThrows(IllegalStateException.class, () -> impersonator.execute(SessionChat.Command.poll(null)));
            }
        }
    }

    @Test public void encryptedRequestsAreBoundToRecipientSessionAndDirection() {
        var receiver = new SessionChatCrypto();
        var outsider = new SessionChatCrypto();
        var sealed = SessionChatCrypto.seal("Secret", receiver.publicKey(), "session:alice:request");
        assertEquals("Secret", receiver.open(sealed, "session:alice:request"));
        assertThrows(SecurityException.class, () -> outsider.open(sealed, "session:alice:request"));
        assertThrows(SecurityException.class, () -> receiver.open(sealed, "session:bob:request"));
        assertThrows(SecurityException.class, () -> receiver.open(sealed, "session:alice:reply"));
    }

    @Test public void closingDuringAnInFlightWriteRemovesTheFinalLeasedMailbox() throws Exception {
        var backing = new LocalCloudStore();
        var block = new AtomicBoolean();
        var writing = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var deleted = new CountDownLatch(1);
        CloudStore store = new CloudStore() {
            @Override public JsonNode get(String path) { return backing.get(path); }
            @Override public void put(String path, Object value) {
                if (block.get() && path.contains("/inbox/")) {
                    writing.countDown();
                    try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                }
                backing.put(path, value);
            }
            @Override public void delete(String path) {
                backing.delete(path);
                if (path.contains("/outbox/")) deleted.countDown();
            }
        };
        var executor = Executors.newSingleThreadExecutor();
        try (var chat = new SessionChat(() -> List.of(new SessionChat.Peer("a", "Alice")));
             var relay = new SessionChatRelay(store, "session", chat, (uid, request) -> uid);
             var client = new SessionChatRelay.Client(store, "session", "a", "a", null)) {
            relay.start();
            client.execute(SessionChat.Command.poll(null));
            block.set(true);
            Future<?> inFlight = executor.submit(() -> client.execute(SessionChat.Command.poll(null)));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            client.close();
            assertFalse("Cleanup must wait until the in-flight write has ended", deleted.await(100, TimeUnit.MILLISECONDS));
            resume.countDown();
            assertThrows(ExecutionException.class, () -> inFlight.get(5, TimeUnit.SECONDS));
            assertTrue(deleted.await(5, TimeUnit.SECONDS));
            assertNull(backing.get("session/inbox/a/0"));
            assertNull(backing.get("session/outbox/a/0"));
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }
}
