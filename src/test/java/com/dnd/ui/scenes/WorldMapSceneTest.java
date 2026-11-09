package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.world.map.GameMap;
import com.dnd.ui.UiSession;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class WorldMapSceneTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass
    public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException running) { ready.countDown(); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test
    public void panelCreatesTheFirstWorldMapAndShowsOnlyWorldMaps() throws Exception {
        Path root = temp.getRoot().toPath();
        new CampaignRepositories(root).maps().add(new GameMap("castle", "Queen's Castle", 3, 3));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Platform.setImplicitExit(false);
                UiSession session = new UiSession(null, null, new Stage()) {
                    @Override public Path campaignRoot() { return root; }
                };
                session.setDm(true);
                Scene scene = new WorldMapScene(session).build();
                @SuppressWarnings("unchecked")
                ComboBox<GameMap> picker = (ComboBox<GameMap>) scene.lookup("#world-map-picker");
                assertNotNull(picker);
                assertEquals(1, picker.getItems().size());
                assertEquals("World Map", picker.getValue().getName());
                assertTrue(picker.getValue().isWorldMap());
                assertNotNull(scene.lookup("#world-tool-party"));
                assertNotNull(scene.lookup("#world-tool-link"));
                assertFalse(new CampaignRepositories(root).maps().getById("castle").isWorldMap());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
