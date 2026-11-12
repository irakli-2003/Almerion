package com.dnd.ui.scenes;

import com.dnd.auth.User;
import com.dnd.cli.core.CampaignContext;
import com.dnd.cli.core.CliSession;
import com.dnd.cli.storage.CampaignStorage;
import com.dnd.cloud.Cloud;
import com.dnd.cloud.CloudConfig;
import com.dnd.cloud.LocalCloudStore;
import com.dnd.security.PrivateAccount;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class PlayerModeUiTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private CloudConfig oldConfig;

    @BeforeClass public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Runnable start = () -> { Platform.setImplicitExit(false); ready.countDown(); };
        try { Platform.startup(start); } catch (IllegalStateException running) { Platform.runLater(start); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Before public void localDirectory() {
        oldConfig = Cloud.config();
        Cloud.override(new LocalCloudStore(), new CloudConfig(false, "", "", "", ""));
    }

    @After public void cleanup() throws Exception {
        onFx(() -> { for (Window w : List.copyOf(Window.getWindows())) w.hide(); });
        com.dnd.lan.LanSessionServer.stopShared();
        Cloud.override(null, oldConfig);
    }

    @Test public void transitionClosesGmWindowsLocksKeysClearsContextAndBlocksGmRoutes() throws Exception {
        PrivateAccount account = PrivateAccount.finishSetup(new LocalCloudStore(),
            PrivateAccount.prepare("gm", "gm@example.test", "GM", "password"), "password");
        onFx(() -> {
            Stage primary = new Stage();
            UiSession session = session(primary);
            session.getCurrentUser().setEmail("gm@example.test");
            session.setPrivateAccount(account);
            BaseScene owner = owner(session, new AtomicReference<>());
            primary.setScene(owner.themedScene(new VBox(new Label("GM menu")), 500, 300));
            primary.show();
            Stage notes = new Stage();
            notes.setScene(owner.themedScene(new VBox(new Label("Secret encounter notes")), 400, 200));
            AtomicBoolean closeRequested = new AtomicBoolean();
            notes.setOnCloseRequest(e -> closeRequested.set(true));
            notes.show();
            confirmNextDialog();
            PlayerModeUi.enterPlayer(owner);
            assertTrue(closeRequested.get());
            assertFalse(notes.isShowing());
            assertFalse(session.isDm());
            assertFalse(account.isUnlocked());
            assertNull(session.getPrivateAccount());
            assertNull(session.campaignRoot());
            assertNull(session.getActiveEntityId());
            assertNull(session.getActiveMapId());
            assertNull(session.getSession().getActivePlayerCharacterId());
            session.getRouter().goTo(SceneType.STORYLINE);
            primary.getScene().getRoot().applyCss();
            primary.getScene().getRoot().layout();
            assertTrue(primary.getScene().getRoot().lookupAll(".label").stream()
                .anyMatch(node -> node instanceof Label l && l.getText().startsWith("Player Mode")));
            assertFalse(primary.getScene().getRoot().lookupAll(".label").stream()
                .anyMatch(node -> node instanceof Label l && l.getText().contains("Secret encounter")));
            Stage stale = new Stage();
            stale.setScene(owner.themedScene(new VBox(new Label("Old GM preview")), 300, 200));
            stale.show();
            assertFalse(stale.isShowing());
        });
        account.close();
    }

    @Test public void cancelledWindowClosePreservesGmContext() throws Exception {
        onFx(() -> {
            Stage primary = new Stage();
            UiSession session = session(primary);
            Path before = session.campaignRoot();
            AtomicReference<String> info = new AtomicReference<>();
            BaseScene owner = owner(session, info);
            primary.setScene(owner.themedScene(new VBox(), 500, 300));
            primary.show();
            Stage editor = new Stage();
            editor.setScene(owner.themedScene(new VBox(), 300, 200));
            editor.setOnCloseRequest(e -> e.consume());
            editor.show();
            confirmNextDialog();
            PlayerModeUi.enterPlayer(owner);
            assertTrue(session.isDm());
            assertEquals(before, session.campaignRoot());
            assertTrue(editor.isShowing());
            assertEquals("Role switch cancelled because a window is still open.", info.get());
        });
    }

    @Test public void hostingAndPendingActionsPreventSwitchBeforeAnyWindowCloses() throws Exception {
        onFx(() -> {
            Stage primary = new Stage();
            UiSession session = session(primary);
            Files.createDirectories(session.campaignRoot().resolve("players"));
            Files.writeString(session.campaignRoot().resolve("players").resolve("players.json"), "{\"players\":[]}");
            com.dnd.lan.LanSessionServer.startShared(session.campaignRoot(), "Test", text -> text);
            AtomicReference<String> info = new AtomicReference<>();
            BaseScene owner = owner(session, info);
            PlayerModeUi.enterPlayer(owner);
            assertEquals("Stop hosting before switching to Player Mode.", info.get());
            assertTrue(session.isDm());
            assertTrue(com.dnd.lan.LanSessionServer.current().isOpen());
            com.dnd.lan.LanSessionServer.stopShared();
            session.beginBackgroundAction();
            PlayerModeUi.enterPlayer(owner);
            assertEquals("Wait for pending actions before switching roles.", info.get());
            assertTrue(session.isDm());
            session.endBackgroundAction();
        });
    }

    @Test public void previousRoleBackgroundCallbackCannotReopenGmContent() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean callback = new AtomicBoolean();
        AtomicReference<UiSession> ref = new AtomicReference<>();
        onFx(() -> {
            UiSession session = session(new Stage());
            ref.set(session);
            BaseScene owner = owner(session, new AtomicReference<>());
            owner.background(() -> {
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return "Private preview";
            }, text -> callback.set(true), error -> callback.set(true));
            assertTrue(session.hasBackgroundActions());
            session.setDm(false);
            release.countDown();
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        AtomicBoolean pending = new AtomicBoolean(true);
        while (pending.get() && System.nanoTime() < deadline) {
            onFx(() -> pending.set(ref.get().hasBackgroundActions()));
            if (pending.get()) Thread.sleep(25);
        }
        assertFalse(pending.get());
        assertFalse(callback.get());
    }

    private UiSession session(Stage primary) throws Exception {
        CampaignStorage storage = new CampaignStorage(temp.getRoot().toPath());
        CliSession cli = new CliSession(storage, null);
        cli.setCampaignContext(new CampaignContext("GM campaign", temp.newFolder().toPath()));
        cli.setActivePlayerCharacterId("old-character");
        UiSession session = new UiSession(cli, storage, primary);
        User user = new User();
        user.setUsername("GM");
        session.setCurrentUser(user);
        session.setDm(true);
        session.setActiveEntityId("secret-npc");
        session.setActiveMapId("secret-map");
        return session;
    }

    private static BaseScene owner(UiSession session, AtomicReference<String> info) {
        return new BaseScene(session) {
            @Override public javafx.scene.Scene build() { throw new UnsupportedOperationException(); }
            @Override protected void showInfo(String header, String text) { info.set(text); }
        };
    }

    private static void confirmNextDialog() {
        Platform.runLater(() -> {
            var pane = Window.getWindows().stream().map(window -> window.getScene().getRoot())
                .filter(root -> root instanceof DialogPane).map(root -> (DialogPane) root).findFirst().orElseThrow();
            ((Button) pane.lookupButton(ButtonType.OK)).fire();
        });
    }

    private interface Work { void run() throws Exception; }
    private static void onFx(Work work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { work.run(); } catch (Throwable problem) { error.set(problem); } finally { done.countDown(); }
        });
        assertTrue(done.await(25, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
