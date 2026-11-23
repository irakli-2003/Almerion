package com.dnd.ui.scenes;

import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.MapObject;
import com.dnd.model.world.map.PlayerToken;
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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Player-facing copy of the battle map for a projector or second monitor: only the map and
 * tokens, scaled to fill the screen, with no DM panels, health bars or selection outline.
 * Movement and ability range previews are mirrored only for player characters.
 */
public class ProjectorWindow {

    private final MapRenderer renderer;
    private final MapRenderer.Decorations decorations = new MapRenderer.Decorations();
    private final Canvas canvas = new Canvas();
    private final StackPane root = new StackPane(canvas);
    private Stage stage;
    private GameMap map;
    private final Supplier<com.dnd.security.FirebaseSessionSync> online;
    private final Consumer<String> reportError;
    private final java.util.concurrent.ThreadPoolExecutor publisher = new java.util.concurrent.ThreadPoolExecutor(
        0, 1, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "projector-publisher");
            t.setDaemon(true);
            return t;
        });
    private final AtomicBoolean publishing = new AtomicBoolean();
    private javafx.animation.Timeline sharing;

    public ProjectorWindow(Path campaignRoot) {
        this(campaignRoot, () -> null, message -> {});
    }

    public ProjectorWindow(Path campaignRoot, Supplier<com.dnd.security.FirebaseSessionSync> online,
                           Consumer<String> reportError) {
        this.renderer = new MapRenderer(campaignRoot);
        this.online = online;
        this.reportError = reportError;
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
        stage.setOnHidden(e -> stopSharing());

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
        sharing = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
            javafx.util.Duration.seconds(3), e -> shareFrame()));
        sharing.setCycleCount(javafx.animation.Animation.INDEFINITE);
        sharing.play();
        shareFrame();
    }

    public void close() {
        if (stage != null) stage.close();
    }

    /** Mirrors the DM's map; call after every change on the DM screen. */
    public void update(GameMap map, MapRenderer.Decorations gmView) {
        this.map = map;
        decorations.currentTurn = gmView.currentTurn;
        decorations.reachable = gmView.selected instanceof PlayerToken ? gmView.reachable : null;
        boolean visible = isPlayerRange(gmView.rangeOrigin, gmView.rangeCells);
        decorations.rangeOrigin = visible ? gmView.rangeOrigin : null;
        decorations.rangeCells = visible ? gmView.rangeCells : 0;
        decorations.rangeRadiusCells = visible ? gmView.rangeRadiusCells : 0;
        if (isShowing()) redraw();
    }

    static boolean isPlayerRange(MapObject origin, double rangeCells) {
        return origin instanceof PlayerToken && rangeCells > 0;
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

    private void shareFrame() {
        var sync = online.get();
        if (sync == null || !sync.isHost() || map == null || canvas.getWidth() <= 0
            || canvas.getHeight() <= 0 || !publishing.compareAndSet(false, true)) return;
        try {
            double scale = Math.min(1, Math.min(640 / canvas.getWidth(), 360 / canvas.getHeight()));
            var params = new javafx.scene.SnapshotParameters();
            params.setTransform(javafx.scene.transform.Transform.scale(scale, scale));
            var image = canvas.snapshot(params, null);
            int width = (int) image.getWidth(), height = (int) image.getHeight();
            int[] pixels = new int[width * height];
            image.getPixelReader().getPixels(0, 0, width, height,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
            publisher.execute(() -> {
                try {
                    BufferedImage png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    png.setRGB(0, 0, width, height, pixels, 0, width);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (!javax.imageio.ImageIO.write(png, "png", bytes))
                        throw new java.io.IOException("PNG encoder unavailable");
                    sync.publishProjector(bytes.toByteArray());
                } catch (RuntimeException | java.io.IOException error) {
                    javafx.application.Platform.runLater(() -> reportError.accept("Projector sharing failed: " + error.getMessage()));
                } finally {
                    publishing.set(false);
                }
            });
        } catch (RuntimeException error) {
            publishing.set(false);
            reportError.accept("Projector sharing failed: " + error.getMessage());
        }
    }

    private void stopSharing() {
        if (sharing != null) sharing.stop();
        var sync = online.get();
        if (sync != null && sync.isHost()) publisher.execute(() -> {
            try {
                sync.clearProjector();
            } catch (RuntimeException error) {
                javafx.application.Platform.runLater(() -> reportError.accept("Could not clear projector: " + error.getMessage()));
            }
        });
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
