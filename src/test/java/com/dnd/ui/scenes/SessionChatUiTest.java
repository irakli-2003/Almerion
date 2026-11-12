package com.dnd.ui.scenes;

import com.dnd.chat.SessionChat;
import com.dnd.cli.core.CampaignContext;
import com.dnd.cli.core.CliSession;
import com.dnd.cli.storage.CampaignStorage;
import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.ui.UiSession;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.*;

public class SessionChatUiTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Runnable start = () -> { Platform.setImplicitExit(false); ready.countDown(); };
        try { Platform.startup(start); } catch (IllegalStateException running) { Platform.runLater(start); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test public void hostCanMonitorButOnlySendAsMemberAndRoleChangeClosesChat() throws Exception {
        Path campaign = temp.newFolder("chat-campaign").toPath();
        var repos = new CampaignRepositories(campaign);
        for (String id : List.of("alice", "bob")) {
            PlayerCharacter pc = new PlayerCharacter();
            pc.setId(id);
            pc.setName(id);
            repos.players().save(pc);
        }
        SessionChat chat = SessionChat.acquire(campaign);
        String thread = chat.execute("pc:alice", command("create", null, List.of("pc:bob"), "")).selected();
        chat.execute("pc:alice", command("send", thread, List.of(), "Player-only secret"));
        try {
            onFx(() -> {
                Stage primary = new Stage();
                var storage = new CampaignStorage(temp.getRoot().toPath());
                var cli = new CliSession(storage, null);
                cli.setCampaignContext(new CampaignContext("Chat test", campaign));
                UiSession session = new UiSession(cli, storage, primary);
                session.setDm(true);
                BaseScene owner = new StorylineScene(session) {
                    @Override protected <T> void background(Supplier<T> work, Consumer<T> success, Consumer<Throwable> failure) {
                        T result;
                        try { result = work.get(); }
                        catch (RuntimeException error) { failure.accept(error); return; }
                        success.accept(result);
                    }
                };
                primary.setScene(new Scene(new StackPane()));
                primary.show();
                SessionChatUi.show(owner);
                Stage window = chatWindow();
                Parent root = window.getScene().getRoot();
                root.applyCss();
                root.layout();
                @SuppressWarnings("unchecked")
                var conversations = (ListView<SessionChat.Conversation>) root.lookup(".list-view");
                conversations.getSelectionModel().selectFirst();
                assertTrue(root.lookupAll(".text-area").stream().anyMatch(n -> ((TextArea) n).getText().contains("Player-only secret")));
                assertTrue(button(root, "Send").isDisabled());
                assertNotNull(button(root, "New conversation"));
                ((TitledPane) root.lookup(".titled-pane")).setExpanded(true);
                root.applyCss();
                root.layout();
                root.lookupAll(".check-box").stream().map(n -> (CheckBox) n)
                    .filter(box -> "pc:alice".equals(box.getUserData())).forEach(box -> box.setSelected(true));
                button(root, "New conversation").fire();
                assertFalse(button(root, "Send").isDisabled());
                TextArea compose = root.lookupAll(".text-area").stream().map(n -> (TextArea) n)
                    .filter(TextArea::isEditable).findFirst().orElseThrow();
                compose.setText("Secret from the GM");
                button(root, "Send").fire();
                var alice = chat.execute("pc:alice", SessionChat.Command.poll(null));
                String direct = alice.conversations().stream().filter(c -> c.members().contains(SessionChat.HOST))
                    .findFirst().orElseThrow().id();
                assertEquals("Secret from the GM", chat.execute("pc:alice", SessionChat.Command.poll(direct)).messages().getFirst().text());
                assertTrue(chat.execute("pc:bob", SessionChat.Command.poll(null)).conversations().stream().noneMatch(c -> c.id().equals(direct)));
                assertEquals("", compose.getText());
                session.setDm(false);
                primary.setScene(new Scene(new StackPane()));
                assertFalse(window.isShowing());
                assertTrue(root.lookupAll(".text-area").stream().allMatch(n -> ((TextArea) n).getText().isEmpty()));
                primary.close();
            });
        } finally {
            SessionChat.release(campaign, chat);
            onFx(() -> { for (Window w : List.copyOf(Window.getWindows())) w.hide(); });
        }
    }

    private static SessionChat.Command command(String operation, String thread, List<String> recipients, String text) {
        return new SessionChat.Command(UUID.randomUUID().toString(), operation, thread, recipients, "", text);
    }

    @Test public void accountFreeTokenPlayerGetsCharacterPasswordPromptEvenForLocallyOwnedCharacter() throws Exception {
        var oldConfig = com.dnd.cloud.Cloud.config();
        com.dnd.cloud.Cloud.override(new com.dnd.cloud.LocalCloudStore(), oldConfig);
        Path campaign = temp.newFolder("token-guest").toPath();
        var pc = new PlayerCharacter();
        pc.setId("hero");
        pc.setOwnerUsername("local-user");
        new CampaignRepositories(campaign).players().save(pc);
        AtomicReference<Throwable> dialogFailure = new AtomicReference<>();
        try {
            onFx(() -> {
                Stage primary = new Stage();
                var storage = new CampaignStorage(temp.getRoot().toPath());
                var cli = new CliSession(storage, null);
                cli.setCampaignContext(new CampaignContext("Token guest", campaign));
                cli.setActivePlayerCharacterId("hero");
                cli.setFirebaseSync(new com.dnd.security.FirebaseSessionSync("https://database.example.test",
                    com.dnd.security.FirebaseSessionSync.generateSessionId(), com.dnd.security.FirebaseSessionSync.generateSessionId()));
                UiSession session = new UiSession(cli, storage, primary);
                var user = new com.dnd.auth.User();
                user.setUsername("local-user");
                session.setCurrentUser(user);
                session.setDm(false);
                primary.setScene(new Scene(new StackPane()));
                primary.show();
                Platform.runLater(() -> {
                    try {
                        var dialog = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                            .filter(root -> root instanceof DialogPane).map(root -> (DialogPane) root).findFirst().orElseThrow();
                        assertTrue(dialog.getContent() instanceof PasswordField);
                        ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire();
                    } catch (Throwable failure) {
                        dialogFailure.set(failure);
                        for (Window w : List.copyOf(Window.getWindows())) if (w != primary) w.hide();
                    }
                });
                SessionChatUi.show(new StorylineScene(session));
                assertFalse(Window.getWindows().stream().anyMatch(w -> w instanceof Stage s && "Session chat".equals(s.getTitle())));
                primary.close();
            });
            if (dialogFailure.get() != null) throw new AssertionError(dialogFailure.get());
        } finally {
            onFx(() -> { for (Window w : List.copyOf(Window.getWindows())) w.hide(); });
            com.dnd.cloud.Cloud.override(null, oldConfig);
        }
    }

    @Test public void guestConnectionKeepsUnreadBadgeAliveAfterClosingChatAndClosesOnNavigation() throws Exception {
        Path campaign = temp.newFolder("guest-badge").toPath();
        var pc = new PlayerCharacter();
        pc.setId("hero");
        new CampaignRepositories(campaign).players().save(pc);
        SessionChat chat = SessionChat.acquire(campaign);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        SessionChat.Connection client = new SessionChat.Connection() {
            @Override public SessionChat.View execute(SessionChat.Command command) { return chat.execute("pc:hero", command); }
            @Override public void close() { closed.set(true); }
        };
        String thread = chat.execute("pc:hero", command("create", null, List.of(SessionChat.HOST), "")).selected();
        AtomicReference<Stage> primary = new AtomicReference<>();
        AtomicReference<Button> badge = new AtomicReference<>();
        try {
            onFx(() -> {
                primary.set(new Stage());
                var storage = new CampaignStorage(temp.getRoot().toPath());
                var cli = new CliSession(storage, null);
                cli.setCampaignContext(new CampaignContext("Badge test", campaign));
                cli.setActivePlayerCharacterId("hero");
                cli.setFirebaseSync(new com.dnd.security.FirebaseSessionSync("https://database.example.test",
                    com.dnd.security.FirebaseSessionSync.generateSessionId(), com.dnd.security.FirebaseSessionSync.generateSessionId()));
                UiSession session = new UiSession(cli, storage, primary.get());
                session.setDm(false);
                BaseScene owner = new StorylineScene(session) {
                    @Override protected <T> void background(Supplier<T> work, Consumer<T> success, Consumer<Throwable> failure) {
                        success.accept(work.get());
                    }
                };
                badge.set(SessionChatUi.button(owner));
                primary.get().setScene(new Scene(new StackPane(badge.get())));
                primary.get().show();
                var constructor = SessionChatUi.class.getDeclaredConstructor(BaseScene.class);
                constructor.setAccessible(true);
                var window = constructor.newInstance(owner);
                var open = SessionChatUi.class.getDeclaredMethod("open", Supplier.class);
                open.setAccessible(true);
                open.invoke(window, (Supplier<SessionChat.Connection>) () -> client);
                chatWindow().hide();
                assertFalse(closed.get());
                SessionChatUi.show(owner);
                assertTrue(chatWindow().isShowing());
                chatWindow().hide();
                assertFalse(closed.get());
            });
            chat.execute(SessionChat.HOST, command("send", thread, List.of(), "Guest notification"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            var unread = new java.util.concurrent.atomic.AtomicBoolean();
            while (!unread.get() && System.nanoTime() < deadline) {
                onFx(() -> unread.set(badge.get().getText().contains("(1)")));
                if (!unread.get()) Thread.sleep(25);
            }
            assertTrue("Guest unread badge did not receive the message", unread.get());
            onFx(() -> primary.get().setScene(new Scene(new StackPane())));
            assertTrue(closed.get());
        } finally {
            onFx(() -> {
                if (primary.get() != null) primary.get().setScene(new Scene(new StackPane()));
                for (Window w : List.copyOf(Window.getWindows())) w.hide();
            });
            SessionChat.release(campaign, chat);
        }
    }

    private static Stage chatWindow() {
        return Window.getWindows().stream().filter(w -> w instanceof Stage s && "Session chat".equals(s.getTitle()))
            .map(w -> (Stage) w).findFirst().orElseThrow();
    }

    private static Button button(Parent root, String text) {
        return root.lookupAll(".button").stream().filter(n -> n instanceof Button b && text.equals(b.getText()))
            .map(n -> (Button) n).findFirst().orElseThrow();
    }

    private interface Work { void run() throws Exception; }
    private static void onFx(Work work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { work.run(); } catch (Throwable error) { failure.set(error); } finally { done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
