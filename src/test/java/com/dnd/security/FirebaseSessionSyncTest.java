package com.dnd.security;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import com.dnd.chat.SessionChat;
import com.dnd.chat.SessionChatRelay;
import com.dnd.cloud.LocalCloudStore;
import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.PlayerCharacter;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public class FirebaseSessionSyncTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test
    public void sharedTokensRoundTripButNeverGrantHostWrites() {
        String id = FirebaseSessionSync.generateSessionId();
        String secret = FirebaseSessionSync.generateSessionId();
        String token = FirebaseSessionSync.generateToken("https://database.example.test", id, secret);
        FirebaseSessionSync player = FirebaseSessionSync.fromToken(token);
        assertEquals(id, player.getSessionId());
        assertEquals(token, player.token());
        assertFalse(player.isHost());
        assertThrows(FirebaseSessionSync.SyncException.class, () -> player.push("secret"));
        assertThrows(FirebaseSessionSync.SyncException.class, player::delete);
    }

    @Test
    public void legacyTokensUnsafeUrlsAndWeakKeysAreRejected() {
        String id = FirebaseSessionSync.generateSessionId();
        String old = Base64.getEncoder().encodeToString(
            "https://database.example.test|legacy|password".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> FirebaseSessionSync.fromToken(old));
        assertThrows(IllegalArgumentException.class,
            () -> FirebaseSessionSync.generateToken("http://database.example.test", id, id));
        assertThrows(IllegalArgumentException.class,
            () -> FirebaseSessionSync.generateToken("https://database.example.test", "../other", id));
        assertThrows(IllegalArgumentException.class,
            () -> FirebaseSessionSync.generateToken("https://database.example.test", id, "weak"));
    }

    @Test
    public void projectorIsHostOnlyEncryptedBoundedAndSurvivesSnapshotPush() {
        var store = new LocalCloudStore();
        String id = FirebaseSessionSync.generateSessionId();
        String key = FirebaseSessionSync.generateSessionId();
        var host = new FirebaseSessionSync("https://database.example.test", id, key, store, "host", "campaign");
        var player = FirebaseSessionSync.fromToken(host.token());
        byte[] image = {1, 2, 3, 4};
        host.push("{\"campaign\":\"test\"}");
        host.publishProjector(image);
        String ciphertext = store.get(id + "/projector").asText();
        assertFalse(ciphertext.contains(Base64.getEncoder().encodeToString(image)));
        assertArrayEquals(image, FirebaseSessionSync.decodeProjector(SessionCipher.decrypt(ciphertext, key), System.currentTimeMillis()));
        host.push("{\"campaign\":\"updated\"}");
        assertEquals(ciphertext, store.get(id + "/projector").asText());
        assertThrows(FirebaseSessionSync.SyncException.class, () -> player.publishProjector(image));
        assertThrows(FirebaseSessionSync.SyncException.class, player::clearProjector);
        assertThrows(FirebaseSessionSync.SyncException.class, () -> host.publishProjector(new byte[1_000_001]));
        host.clearProjector();
        assertNull(store.get(id + "/projector"));
    }

    @Test
    public void projectorFramesExpireAndRejectMalformedPayloads() {
        String encoded = Base64.getEncoder().encodeToString(new byte[] {1, 2});
        assertArrayEquals(new byte[] {1, 2}, FirebaseSessionSync.decodeProjector("10000:" + encoded, 11000));
        assertNull(FirebaseSessionSync.decodeProjector("10000:" + encoded, 26001));
        assertNull(FirebaseSessionSync.decodeProjector("20000:" + encoded, 10000));
        assertThrows(FirebaseSessionSync.SyncException.class, () -> FirebaseSessionSync.decodeProjector("not-a-frame", 10000));
    }

    @Test
    public void projectorPublishesRenderedPngAndClearsOnClose() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(1);
        try {
            javafx.application.Platform.startup(() -> {
                javafx.application.Platform.setImplicitExit(false);
                ready.countDown();
            });
        } catch (IllegalStateException alreadyRunning) {
            javafx.application.Platform.runLater(ready::countDown);
        }
        assertTrue(ready.await(20, java.util.concurrent.TimeUnit.SECONDS));
        var store = new LocalCloudStore();
        String id = FirebaseSessionSync.generateSessionId(), key = FirebaseSessionSync.generateSessionId();
        var host = new FirebaseSessionSync("https://database.example.test", id, key, store, "host", "campaign");
        host.push("{}");
        var window = new com.dnd.ui.scenes.ProjectorWindow(temp.getRoot().toPath(), () -> host,
            message -> { throw new AssertionError(message); });
        var opened = new java.util.concurrent.CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            window.update(new com.dnd.model.world.map.GameMap("map", "Private name", 10, 10),
                new com.dnd.ui.MapRenderer.Decorations());
            window.show(null);
            opened.countDown();
        });
        assertTrue(opened.await(20, java.util.concurrent.TimeUnit.SECONDS));
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (store.get(id + "/projector") == null && System.nanoTime() < deadline) Thread.sleep(50);
            var frame = store.get(id + "/projector");
            assertNotNull(frame);
            String decrypted = SessionCipher.decrypt(frame.asText(), key);
            assertFalse(decrypted.contains("Private name"));
            byte[] png = FirebaseSessionSync.decodeProjector(decrypted, System.currentTimeMillis());
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            assertNotNull(image);
            assertTrue(image.getWidth() <= 640 && image.getHeight() <= 360);
        } finally {
            var closed = new java.util.concurrent.CountDownLatch(1);
            javafx.application.Platform.runLater(() -> { window.close(); closed.countDown(); });
            assertTrue(closed.await(20, java.util.concurrent.TimeUnit.SECONDS));
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (store.get(id + "/projector") != null && System.nanoTime() < deadline) Thread.sleep(50);
        assertNull(store.get(id + "/projector"));
    }

    @Test
    public void accountFreeTokenGuestsNeedCharacterPasswordAndShareTheHostsConversations() throws Exception {
        var campaign = temp.newFolder("guest-campaign").toPath();
        var repos = new CampaignRepositories(campaign);
        var pc = new PlayerCharacter();
        pc.setId("hero");
        pc.setName("Hero");
        pc.setOwnerUsername("registered-owner");
        pc.setPassword("character-secret");
        repos.players().save(pc);
        var sync = new FirebaseSessionSync("https://database.example.test",
            FirebaseSessionSync.generateSessionId(), FirebaseSessionSync.generateSessionId());
        var tokenGuest = FirebaseSessionSync.fromToken(sync.token());
        assertEquals(sync.guestChannel(), tokenGuest.guestChannel());
        var otherToken = new FirebaseSessionSync("https://database.example.test", sync.getSessionId(), FirebaseSessionSync.generateSessionId());
        assertNotEquals(sync.guestCapability(), otherToken.guestCapability());
        assertThrows(SecurityException.class, () -> sync.authorizeChat(campaign, "guest", null, "hero", null));
        assertThrows(SecurityException.class, () -> sync.authorizeChat(campaign, "guest", null, "hero", "wrong"));
        assertEquals("pc:hero", sync.authorizeChat(campaign, "guest", null, "hero", "character-secret"));
        var store = new LocalCloudStore();
        try (var chat = SessionChat.acquire(campaign);
             var relay = new SessionChatRelay(store, sync.guestChannel(), chat, (uid, request) ->
                 sync.authorizeChat(campaign, "guest", null, request.character(), request.password()));
             var guest = new SessionChatRelay.Client(store, tokenGuest.guestChannel(), "guest",
                 "forged-owner-name-is-ignored", "hero", "character-secret")) {
            relay.start();
            String thread = guest.execute(new SessionChat.Command(UUID.randomUUID().toString(), "create", null,
                List.of(SessionChat.HOST), "", "")).selected();
            guest.execute(new SessionChat.Command(UUID.randomUUID().toString(), "send", thread, List.of(), "", "Guest secret"));
            assertEquals("Guest secret", chat.execute(SessionChat.HOST, SessionChat.Command.poll(thread)).messages().getFirst().text());
            assertFalse(store.get(sync.guestChannel()).toString().contains("character-secret"));
            assertFalse(store.get(sync.guestChannel()).toString().contains("Guest secret"));
            pc.setPassword("rotated-password");
            repos.players().save(pc);
            assertThrows(IllegalStateException.class, () -> guest.execute(SessionChat.Command.poll(thread)));
        } finally {
            SessionChat hosted = SessionChat.hosted(campaign);
            if (hosted != null) SessionChat.release(campaign, hosted);
        }
        for (int i = 0; i < 5; i++) {
            assertThrows(SecurityException.class, () -> sync.authorizeChat(campaign, "guest", null, "hero", "wrong"));
        }
        var locked = assertThrows(SecurityException.class,
            () -> sync.authorizeChat(campaign, "guest", null, "hero", "rotated-password"));
        assertTrue(locked.getMessage().contains("Too many"));
    }
}
