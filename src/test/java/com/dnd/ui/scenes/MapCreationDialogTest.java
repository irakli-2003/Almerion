package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.MapTemplates;
import com.dnd.model.world.map.GameMap;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class MapCreationDialogTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass
    public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException running) { ready.countDown(); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test
    public void creatingFromTempleRequiresANewNameAndProducesANamedIndependentMap() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(), 300, 200));
            stage.show();
            try {
                CampaignRepositories repos = new CampaignRepositories(temp.getRoot().toPath());
                repos.maps().add(new GameMap("old", "Queen's Castle", 2, 2));
                BaseScene owner = new BaseScene(null) {
                    @Override public Scene build() { return stage.getScene(); }
                };
                AtomicReference<GameMap> created = new AtomicReference<>();
                Platform.runLater(() -> {
                    try {
                        Stage window = new ArrayList<>(Window.getWindows()).stream()
                            .filter(w -> w instanceof Stage s && "Create Map from Template".equals(s.getTitle()))
                            .map(w -> (Stage) w).findFirst().orElseThrow();
                        TextField name = (TextField) window.getScene().lookup("#map-location-name");
                        @SuppressWarnings("unchecked")
                        ComboBox<MapTemplates.Template> template =
                            (ComboBox<MapTemplates.Template>) window.getScene().lookup("#map-template");
                        template.setValue(template.getItems().stream()
                            .filter(t -> t.name().equals("Temple")).findFirst().orElseThrow());
                        Button create = (Button) window.getScene().getRoot().lookupAll(".button").stream()
                            .filter(n -> n instanceof Button b && b.getText().equals("Create Map"))
                            .findFirst().orElseThrow();
                        create.fire();
                        assertTrue(window.isShowing());
                        assertEquals(2, repos.maps().list().size());
                        name.setText("QUEEN'S CASTLE");
                        create.fire();
                        assertTrue(window.isShowing());
                        assertEquals(2, repos.maps().list().size());
                        name.setText("Royal Chapel");
                        create.fire();
                        assertTrue("a location needs a square on a world map", window.isShowing());
                        assertEquals(2, repos.maps().list().size());
                        Canvas where = (Canvas) window.getScene().lookup("#map-world-location");
                        double cell = where.getWidth() / 40;
                        var point = where.localToScene(cell * 3.5, cell * 2.5);
                        where.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, point.getX(), point.getY(),
                            0, 0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false,
                            true, false, false, null));
                        create.fire();
                    } catch (Throwable t) {
                        failure.set(t);
                        new ArrayList<>(Window.getWindows()).stream()
                            .filter(w -> w != stage && w instanceof Stage).forEach(Window::hide);
                    }
                });
                MapCreationDialog.show(owner, repos, created::set);
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertNotNull(created.get());
                assertEquals("Royal Chapel", created.get().getName());
                assertNotEquals("temple-dawnfather-map", created.get().getId());
                assertEquals(18, created.get().getWidth());
                assertEquals("Royal Chapel", repos.maps().getById(created.get().getId()).getName());
                assertEquals(3, repos.maps().list().size());
                GameMap world = new com.dnd.data.WorldMaps(repos).list().get(0);
                assertEquals(created.get().getId(), world.linkAt(3, 2).getMapId());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                stage.close();
                done.countDown();
            }
        });
        assertTrue("Map creation UI timed out", done.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
