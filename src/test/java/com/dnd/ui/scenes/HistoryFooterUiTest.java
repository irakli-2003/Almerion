package com.dnd.ui.scenes;

import com.dnd.auth.User;
import com.dnd.cli.core.CampaignContext;
import com.dnd.cli.core.CliSession;
import com.dnd.cli.storage.CampaignStorage;
import com.dnd.cloud.*;
import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.ui.ReportUi;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class HistoryFooterUiTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String previousHome;

    @Before public void isolateDeviceState() {
        previousHome = System.getProperty("dnd.home");
        System.setProperty("dnd.home", temp.getRoot().getAbsolutePath());
    }

    @After public void restoreDeviceState() {
        if (previousHome == null) System.clearProperty("dnd.home");
        else System.setProperty("dnd.home", previousHome);
    }

    @BeforeClass public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Runnable start = () -> { Platform.setImplicitExit(false); ready.countDown(); };
        try { Platform.startup(start); } catch (IllegalStateException running) { Platform.runLater(start); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test public void footerUndoesRedoesReloadsAndDoesNotOperateFromCampaignSelection() throws Exception {
        var root = temp.newFolder("campaign").toPath();
        String id = CampaignMeta.ensureId(root);
        LocalCloudStore cloud = new LocalCloudStore();
        Cloud.override(cloud, null);
        new CampaignDirectory(cloud).register(id, "Campaign", "GM");
        var repos = new CampaignRepositories(root);
        var pc = new PlayerCharacter();
        pc.setId("hero"); pc.setName("Hero"); pc.setMaxHitPoints(20); pc.setCurrentHitPoints(20);
        repos.players().add(pc);
        pc.applyDamage(5); repos.players().save(pc);
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<UiSession> session = new AtomicReference<>();
        CompletableFuture<Void> undoReady = new CompletableFuture<>();
        CompletableFuture<Void> redoReady = new CompletableFuture<>();
        try {
            onFx(() -> {
                ReportUi.install();
                Stage window = new Stage(); stage.set(window);
                var storage = new CampaignStorage(temp.getRoot().toPath());
                var cli = new CliSession(storage, null);
                cli.setCampaignContext(new CampaignContext("Campaign", root));
                var ui = new UiSession(cli, storage, window); session.set(ui);
                User user = new User(); user.setUsername("GM"); ui.setCurrentUser(user); ui.setDm(true);
                ui.getRouter().goTo(SceneType.DM_MENU); window.show();
                button(window.getScene().getRoot(), "undo-action").disabledProperty().addListener((o, old, disabled) -> {
                    if (!disabled) undoReady.complete(null);
                });
                window.sceneProperty().addListener((o, old, scene) -> {
                    if (scene.lookup("#redo-action") instanceof Button redo) {
                        redo.disabledProperty().addListener((p, before, disabled) -> {
                            if (!disabled) redoReady.complete(null);
                        });
                    }
                });
                Parent menu = window.getScene().getRoot();
                menu.applyCss(); menu.layout();
                Button sync = button(menu, "campaign-github-sync");
                assertTrue(sync.isVisible());
                assertSame(button(menu, "undo-action").getParent(), sync.getParent());
                assertNotNull(menu.lookupAll(".button").stream().filter(node -> node instanceof Button b
                    && b.getText().equals(com.dnd.ui.i18n.UiTranslator.text("World Map"))).findFirst().orElse(null));
                assertFalse(menu.lookupAll(".button").stream().anyMatch(node -> node instanceof Button b && b.getText().contains("🧭")));
                for (String name : new String[]{"Stats & Skills", "Class Upgrade Trees"}) {
                    Button control = menu.lookupAll(".button").stream().filter(node -> node instanceof Button b
                        && b.getText().equals(com.dnd.ui.i18n.UiTranslator.text(name))).map(node -> (Button) node).findFirst().orElseThrow();
                    assertTrue(control.getParent().getParent().lookupAll(".label").stream().anyMatch(node ->
                        node instanceof javafx.scene.control.Label label
                            && label.getText().equals(com.dnd.ui.i18n.UiTranslator.text("Magic & Rules"))));
                }
            });
            undoReady.get(15, TimeUnit.SECONDS);
            onFx(() -> {
                Parent menu = stage.get().getScene().getRoot();
                assertFalse(button(menu, "campaign-github-sync").isDisabled());
                button(menu, "undo-action").fire();
            });
            redoReady.get(15, TimeUnit.SECONDS);
            assertEquals(20, repos.players().getById("hero").getCurrentHitPoints());
            CompletableFuture<Void> nextUndo = new CompletableFuture<>();
            onFx(() -> {
                stage.get().sceneProperty().addListener((o, old, scene) -> {
                    if (scene.lookup("#undo-action") instanceof Button undo) undo.disabledProperty().addListener((p, before, disabled) -> {
                        if (!disabled) nextUndo.complete(null);
                    });
                });
                button(stage.get().getScene().getRoot(), "redo-action").fire();
            });
            nextUndo.get(15, TimeUnit.SECONDS);
            assertEquals(15, repos.players().getById("hero").getCurrentHitPoints());
            CompletableFuture<Void> editorReady = new CompletableFuture<>();
            onFx(() -> {
                session.get().setActiveEntityCategory(com.dnd.ui.EntityCategory.PLAYER);
                session.get().setActiveEntityId("hero");
                session.get().getRouter().goTo(SceneType.ENTITY_DETAIL);
                Button undo = button(stage.get().getScene().getRoot(), "undo-action");
                assertTrue(button(stage.get().getScene().getRoot(), "campaign-github-sync").isVisible());
                if (!undo.isDisabled()) editorReady.complete(null);
                undo.disabledProperty().addListener((o, previous, disabled) -> { if (!disabled) editorReady.complete(null); });
            });
            editorReady.get(15, TimeUnit.SECONDS);
            onFx(() -> {
                Parent editor = stage.get().getScene().getRoot();
                editor.applyCss(); editor.layout();
                var name = editor.lookupAll(".text-field").stream().filter(node -> node instanceof javafx.scene.control.TextField field
                    && field.getText().equals("Hero")).map(node -> (javafx.scene.control.TextField) node).findFirst().orElseThrow();
                name.setText("Unsaved name");
                Platform.runLater(() -> {
                    var dialog = javafx.stage.Window.getWindows().stream().filter(window -> window != stage.get())
                        .map(window -> window.getScene().getRoot()).filter(node -> node instanceof javafx.scene.control.DialogPane)
                        .map(node -> (javafx.scene.control.DialogPane) node).findFirst().orElseThrow();
                    ((Button) dialog.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire();
                });
                button(editor, "undo-action").fire();
                assertSame(editor, stage.get().getScene().getRoot());
                assertEquals("Unsaved name", name.getText());
                assertEquals(15, repos.players().getById("hero").getCurrentHitPoints());
            });
            onFx(() -> {
                session.get().getRouter().goTo(SceneType.CAMPAIGN_SELECTION);
                assertTrue(button(stage.get().getScene().getRoot(), "undo-action").isDisabled());
                assertTrue(button(stage.get().getScene().getRoot(), "redo-action").isDisabled());
                assertFalse(button(stage.get().getScene().getRoot(), "campaign-github-sync").isVisible());
            });
        } finally {
            onFx(() -> { if (stage.get() != null) stage.get().close(); });
            Cloud.override(null, null);
        }
    }

    private static Button button(Parent root, String id) { return (Button) root.lookup("#" + id); }
    private static void onFx(Runnable work) throws Exception {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { work.run(); result.complete(null); } catch (Throwable error) { result.completeExceptionally(error); }
        });
        result.get(20, TimeUnit.SECONDS);
    }
}
