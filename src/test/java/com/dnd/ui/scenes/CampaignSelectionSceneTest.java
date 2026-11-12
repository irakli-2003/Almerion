package com.dnd.ui.scenes;

import com.dnd.auth.User;
import com.dnd.cli.core.CliSession;
import com.dnd.cli.storage.CampaignStorage;
import com.dnd.cloud.CampaignAccess;
import com.dnd.cloud.CampaignDirectory;
import com.dnd.cloud.LocalCloudStore;
import com.dnd.ui.UiSession;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.*;

public class CampaignSelectionSceneTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException running) { ready.countDown(); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test public void campaignListDoesNotExposeOrRemoveOtherRegistrations() throws Exception {
        var storage = new CampaignStorage(temp.getRoot().toPath());
        var folder = Files.createDirectories(storage.resolveCustomCampaignPath("zurabaach"));
        Files.createDirectories(storage.resolveCustomCampaignPath("unclaimed"));
        var directory = new CampaignDirectory(new LocalCloudStore());
        var active = CampaignAccess.claim(CampaignAccess.LocalCampaign.of("zurabaach", folder), "gm", directory);
        var sameName = directory.register("different-id", "zurabaach", "gm");
        var remote = directory.register("remote-id", "Remote campaign", "gm");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            Stage stage = new Stage();
            try {
                var session = new UiSession(new CliSession(storage, null), storage, stage);
                var user = new User(); user.setUsername("gm"); session.setCurrentUser(user);
                var selection = new CampaignSelectionScene(session, directory) {
                    private int calls;
                    @Override protected <T> void background(Supplier<T> work, Consumer<T> success, Consumer<Throwable> failure) {
                        if (calls++ == 0) return; // GitHub account bar is unrelated to campaign listing.
                        try { success.accept(work.get()); } catch (Throwable ex) { failure.accept(ex); }
                    }
                };
                stage.setScene(selection.build());
                stage.show();
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                var lists = stage.getScene().getRoot().lookupAll(".list-view").stream()
                    .filter(node -> node instanceof ListView<?>).map(node -> (ListView<?>)node).toList();
                assertEquals(1, lists.size());
                assertEquals(1, lists.getFirst().getItems().size());
                var entry = (CampaignAccess.Entry)lists.getFirst().getItems().getFirst();
                assertEquals(active.id, entry.record().id);
                var text = stage.getScene().getRoot().lookupAll(".label").stream()
                    .filter(node -> node instanceof Label).map(node -> ((Label)node).getText()).toList();
                assertTrue(text.contains("On this device, not registered to anyone"));
                assertFalse(text.contains("Other online campaign registrations"));
                var buttons = stage.getScene().getRoot().lookupAll(".button").stream()
                    .filter(node -> node instanceof Button).map(node -> ((Button)node).getText()).toList();
                assertTrue(buttons.containsAll(List.of("Claim", "Get Campaign from GitHub...", "Import Complete Campaign...", "Restore points")));
                assertFalse(buttons.contains("Review online registration..."));
                assertFalse(buttons.contains("Remove only this online registration..."));
                assertTrue(directory.find(sameName.id).isPresent());
                assertTrue(directory.find(remote.id).isPresent());
                assertEquals(active.id, CampaignAccess.LocalCampaign.of("zurabaach", folder).id());
            } catch (Throwable ex) { error.set(ex); }
            finally { stage.close(); done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
