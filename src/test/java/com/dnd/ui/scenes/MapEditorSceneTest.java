package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.WorldMaps;
import com.dnd.model.world.map.GameMap;
import com.dnd.ui.UiSession;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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

public class MapEditorSceneTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException running) { ready.countDown(); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test public void worldEditorOnlyPlacesLocationPinsAndBothEditorsZoom() throws Exception {
        Path root = temp.getRoot().toPath();
        CampaignRepositories repos = new CampaignRepositories(root);
        repos.maps().add(new GameMap("castle", "Castle", 5, 5));
        WorldMaps worlds = new WorldMaps(repos);
        String worldId = worlds.ensure().getFirst().getId();
        worlds.unlink(worldId, 0, 0);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            Stage stage = new Stage();
            try {
                Platform.setImplicitExit(false);
                UiSession session = new UiSession(null, null, stage) {
                    @Override public Path campaignRoot() { return root; }
                };
                for (String id : new String[] {worldId, "castle"}) {
                    session.setActiveMapId(id);
                    Scene scene = new MapEditorScene(session).build();
                    stage.setScene(scene);
                    stage.show();
                    scene.getRoot().applyCss();
                    scene.getRoot().layout();
                    Canvas canvas = (Canvas) scene.lookup("#map-editor-canvas");
                    ScrollPane scroll = (ScrollPane) scene.lookup("#map-editor-scroll");
                    double initialWidth = scroll.getContent().getBoundsInLocal().getWidth();
                    ((Button) scene.lookup("#map-zoom-in")).fire();
                    assertEquals(1.25, canvas.getLocalToParentTransform().getMxx(), .001);
                    assertTrue(scroll.getContent().getBoundsInLocal().getWidth() > initialWidth);
                    for (int i = 0; i < 20; i++) ((Button) scene.lookup("#map-zoom-out")).fire();
                    assertEquals(.25, canvas.getLocalToParentTransform().getMxx(), .001);
                    for (int i = 0; i < 30; i++) ((Button) scene.lookup("#map-zoom-in")).fire();
                    assertEquals(3, canvas.getLocalToParentTransform().getMxx(), .001);
                    ((Button) scene.lookup("#map-zoom-reset")).fire();
                    assertEquals(1, canvas.getLocalToParentTransform().getMxx(), .001);
                    if (id.equals(worldId)) {
                        assertNotNull(scene.lookup("#world-map-tokens"));
                        assertFalse(scene.getRoot().lookupAll(".combo-box").stream()
                            .filter(n -> n instanceof ComboBox<?>)
                            .map(n -> (ComboBox<?>) n).anyMatch(c -> c.getItems().contains("NPC")));
                        ListView<?> locations = (ListView<?>) scene.lookup("#world-map-tokens .list-view");
                        assertEquals(1, locations.getItems().size());
                        locations.getSelectionModel().select(0);
                        canvas.getOnMouseReleased().handle(mouse(MouseEvent.MOUSE_RELEASED, 72, 72));
                        canvas.getOnMousePressed().handle(mouse(MouseEvent.MOUSE_PRESSED, 72, 72));
                        canvas.getOnMouseReleased().handle(mouse(MouseEvent.MOUSE_RELEASED, 120, 120));
                        Button save = scene.getRoot().lookupAll(".button").stream()
                            .filter(n -> n instanceof Button b && b.getText().equals("Save Map"))
                            .map(n -> (Button) n).findFirst().orElseThrow();
                        // Dismiss the saved notification after the editor writes the map.
                        Platform.runLater(() -> new java.util.ArrayList<>(javafx.stage.Window.getWindows())
                            .stream().filter(w -> w != stage).forEach(javafx.stage.Window::hide));
                        save.fire();
                        GameMap saved = repos.maps().getById(worldId);
                        assertNull(saved.linkAt(1, 1));
                        assertEquals("castle", saved.linkAt(2, 2).getMapId());
                    } else {
                        assertNull(scene.lookup("#world-map-tokens"));
                        assertTrue(scene.getRoot().lookupAll(".combo-box").stream()
                            .filter(n -> n instanceof ComboBox<?>)
                            .map(n -> (ComboBox<?>) n).anyMatch(c -> c.getItems().contains("NPC")));
                    }
                }
            } catch (Throwable t) { failure.set(t); }
            finally { stage.close(); done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double x, double y) {
        return new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1,
            false, false, false, false, false, false, false, false, false, true, null);
    }
}
