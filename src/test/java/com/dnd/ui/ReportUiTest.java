package com.dnd.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ReportUiTest {
    @BeforeClass
    public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException running) {
            ready.countDown();
        }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test
    public void encodesScreenshotPixelsAsPng() throws Exception {
        onFx(() -> {
            WritableImage image = new WritableImage(3, 2);
            image.getPixelWriter().setArgb(1, 0, 0xffc90000);
            var png = ImageIO.read(new ByteArrayInputStream(ReportUi.png(image)));
            assertEquals(3, png.getWidth());
            assertEquals(2, png.getHeight());
            assertEquals(0xffc90000, png.getRGB(1, 0));
        });
    }

    @Test
    public void reportControlSurvivesNavigationAndWorksInsideModalWindows() throws Exception {
        onFx(() -> {
            Platform.setImplicitExit(false);
            ReportUi.install();
            Stage stage = new Stage();
            Label content = new Label("Original screen");
            stage.setScene(new Scene(new VBox(content), 500, 300));
            stage.show();
            try {
                assertSame(stage.getScene(), content.getScene());
                assertNotNull(reportButton(stage.getScene()));
                stage.setScene(new Scene(new VBox(new Label("Next screen")), 500, 300));
                Button report = reportButton(stage.getScene());
                AtomicReference<Throwable> nestedFailure = new AtomicReference<>();
                Platform.runLater(() -> {
                    try {
                        Stage reportWindow = new ArrayList<>(Window.getWindows()).stream()
                            .filter(w -> w instanceof Stage s && s.getOwner() == stage
                                && "Report a bug or suggestion".equals(s.getTitle()))
                            .map(w -> (Stage) w).findFirst().orElseThrow();
                        assertEquals(0, reportWindow.getScene().getRoot().lookupAll(".button").stream()
                            .filter(n -> n instanceof Button b && "\uD83D\uDC1E".equals(b.getText())).count());
                        CheckBox include = (CheckBox) reportWindow.getScene().getRoot().lookup(".check-box");
                        assertTrue(include.isSelected());
                        include.setSelected(false);
                        DialogPane pane = (DialogPane) reportWindow.getScene().getRoot();
                        ScrollPane scroll = (ScrollPane) pane.getContent();
                        VBox form = (VBox) scroll.getContent();
                        ImageView preview = form.getChildren().stream().filter(n -> n instanceof ImageView)
                            .map(n -> (ImageView) n).findFirst().orElseThrow();
                        assertFalse(preview.isVisible());
                        assertFalse(preview.isManaged());
                    } catch (Throwable t) {
                        nestedFailure.set(t);
                    } finally {
                        new ArrayList<>(Window.getWindows()).stream()
                            .filter(w -> w instanceof Stage s && s.getOwner() == stage).forEach(Window::hide);
                    }
                });
                report.fire();
                if (nestedFailure.get() != null) throw new AssertionError(nestedFailure.get());

                Dialog<Void> modal = new Dialog<>();
                modal.initOwner(stage);
                modal.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
                modal.show();
                assertNotNull(reportButton(modal.getDialogPane().getScene()));
                modal.close();
            } finally {
                stage.close();
            }
        });
    }

    private static Button reportButton(Scene scene) {
        return scene.getRoot().lookupAll(".button").stream()
            .filter(n -> n instanceof Button b && "\uD83D\uDC1E".equals(b.getText()))
            .map(n -> (Button) n).findFirst().orElseThrow();
    }

    private static void onFx(Work work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { work.run(); }
            catch (Throwable t) { error.set(t); }
            finally { done.countDown(); }
        });
        assertTrue("FX task timed out", done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private interface Work { void run() throws Exception; }
}
