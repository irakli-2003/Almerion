package com.dnd.ui;

import com.dnd.auth.User;
import com.dnd.cli.core.CampaignContext;
import com.dnd.cli.core.CliSession;
import com.dnd.cli.storage.CampaignStorage;
import com.dnd.sync.CampaignRepo;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class CampaignCloseTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass public static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        Runnable start = () -> { Platform.setImplicitExit(false); ready.complete(null); };
        try { Platform.startup(start); } catch (IllegalStateException running) { Platform.runLater(start); }
        ready.get(15, TimeUnit.SECONDS);
    }

    @Test public void failedNavigationKeepsCampaignOpenAndWindowCloseWaitsForSuccess() throws Exception {
        var folder = temp.newFolder("campaign").toPath();
        var offline = new CompletableFuture<CampaignRepo.Result>();
        var synced = new CompletableFuture<CampaignRepo.Result>();
        var attempts = new AtomicInteger();
        var closed = new CompletableFuture<Void>();
        onFx(() -> {
            var stage = new Stage();
            var storage = new CampaignStorage(folder);
            var cli = new CliSession(storage, null);
            cli.setCampaignContext(new CampaignContext("Test campaign", folder));
            var session = new UiSession(cli, storage, stage);
            var user = new User();
            user.setUsername("GM");
            session.setCurrentUser(user);
            session.setDm(true);
            var router = new SceneRouter(session, stage, folder::equals,
                path -> attempts.incrementAndGet() == 1 ? offline : synced);
            stage.setOnCloseRequest(e -> {
                e.consume();
                router.leaveCampaign(stage::close);
            });
            stage.setOnHidden(e -> closed.complete(null));
            router.goTo(SceneType.STORYLINE);
            stage.show();
            var scene = stage.getScene();
            var editor = new Stage();
            editor.initOwner(stage);
            editor.setOnCloseRequest(Event::consume);
            editor.show();
            try {
                router.goTo(SceneType.CAMPAIGN_SELECTION);
                assertTrue(editor.isShowing());
                assertEquals(0, attempts.get());
                assertSame(scene, stage.getScene());
            } finally {
                editor.close();
            }
            router.goTo(SceneType.CAMPAIGN_SELECTION);
            router.leaveCampaign(() -> closed.completeExceptionally(new AssertionError("Duplicate close accepted")));
            assertEquals(1, attempts.get());
            assertSame(scene, stage.getScene());
            offline.complete(new CampaignRepo.Result(CampaignRepo.Outcome.OFFLINE, List.of(), List.of(), "Offline"));
            Platform.runLater(() -> {
                try {
                    assertSame(scene, stage.getScene());
                    var pane = Window.getWindows().stream().filter(w -> w.isShowing() && w != stage)
                        .map(w -> w.getScene().getRoot()).filter(DialogPane.class::isInstance)
                        .map(DialogPane.class::cast).findFirst().orElseThrow();
                    ((Button) pane.lookupButton(ButtonType.OK)).fire();
                    Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
                    assertTrue(stage.isShowing());
                    assertEquals(2, attempts.get());
                    synced.complete(new CampaignRepo.Result(CampaignRepo.Outcome.SYNCED, List.of(), List.of(), "Synced"));
                } catch (Throwable failure) {
                    closed.completeExceptionally(failure);
                }
            });
        });
        closed.get(20, TimeUnit.SECONDS);
    }

    private static void onFx(Runnable runnable) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try { runnable.run(); done.complete(null); }
            catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        done.get(20, TimeUnit.SECONDS);
    }
}
