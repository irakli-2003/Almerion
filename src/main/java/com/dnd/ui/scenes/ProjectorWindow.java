package com.dnd.ui.scenes;

import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.MapObject;
import com.dnd.ui.MapRenderer;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;

/**
 * Player-facing copy of the battle map for a projector or second monitor: only the map and
 * tokens, scaled to fill the screen, with no DM panels, health bars, selection or range previews.
 */
public class ProjectorWindow {

    private final MapRenderer renderer;
    private final MapRenderer.Decorations decorations = new MapRenderer.Decorations();
    private final Canvas canvas = new Canvas();
    private final StackPane root = new StackPane(canvas);
    private Stage stage;
    private GameMap map;

    public ProjectorWindow(Path campaignRoot) {
        this.renderer = new MapRenderer(campaignRoot);
        decorations.showHealthBars = false;
        root.setStyle("-fx-background-color: black;");
    }

    public boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    /** Opens on the screen the owner is not on, or as a normal window when there is only one screen. */
    public void show(Window owner) {
        if (isShowing()) {
            stage.toFront();
            return;
        }
        stage = new Stage();
        stage.setTitle("Battle Map - Players");
        Scene scene = new Scene(root, 1280, 720);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) close();
        });
        stage.setScene(scene);

        Screen target = otherScreen(owner);
        if (target != null) {
            Rectangle2D b = target.getBounds();
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setX(b.getMinX());
            stage.setY(b.getMinY());
            stage.setWidth(b.getWidth());
            stage.setHeight(b.getHeight());
        }
        root.widthProperty().addListener((o, a, b) -> redraw());
        root.heightProperty().addListener((o, a, b) -> redraw());
        stage.show();
        redraw();
    }

    public void close() {
        if (stage != null) stage.close();
    }

    /** Mirrors the DM's map; call after every change on the DM screen. */
    public void update(GameMap map, MapObject currentTurn) {
        this.map = map;
        decorations.currentTurn = currentTurn;
        if (isShowing()) redraw();
    }

    private void redraw() {
        if (map == null || map.getWidth() <= 0 || map.getHeight() <= 0) return;
        double w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        renderer.setCellSize(fitCellSize(w, h, map.getWidth(), map.getHeight()));
        canvas.setWidth(map.getWidth() * renderer.getCellSize());
        canvas.setHeight(map.getHeight() * renderer.getCellSize());
        renderer.render(canvas, map, decorations);
    }

    /** Largest cell size that fits the whole map into the given area. */
    static double fitCellSize(double width, double height, int cols, int rows) {
        return Math.floor(Math.min(width / cols, height / rows));
    }

    private static Screen otherScreen(Window owner) {
        List<Screen> screens = Screen.getScreens();
        if (screens.size() < 2) return null;
        Screen dm = Screen.getPrimary();
        if (owner != null) {
            List<Screen> under = Screen.getScreensForRectangle(
                owner.getX(), owner.getY(), Math.max(1, owner.getWidth()), Math.max(1, owner.getHeight()));
            if (!under.isEmpty()) dm = under.get(0);
        }
        for (Screen s : screens) {
            if (!s.equals(dm)) return s;
        }
        return null;
    }
}
