package com.dnd.ui;

import com.dnd.auth.EmailService;
import com.dnd.ui.i18n.TranslationService;
import com.dnd.ui.i18n.UiTranslator;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    public void successfulReportStaysReadableAndCanBeClosedInEnglishAndGeorgian() throws Exception {
        verifyReportCompletion("en", false);
        verifyReportCompletion("ka", false);
    }

    @Test
    public void failedReportCanBeRetriedAndThenClosed() throws Exception {
        verifyReportCompletion("en", true);
    }

    private static void verifyReportCompletion(String language, boolean failFirst) throws Exception {
        onFx(() -> {
            Platform.setImplicitExit(false);
            UiTranslator translator = UiTranslator.get();
            if (translator == null) translator = UiTranslator.install(TranslationService.createDefault(), null);
            String previousLanguage = translator.getLanguage().code();
            translator.setLanguage(language);
            ReportUi.install();
            AtomicInteger sends = new AtomicInteger();
            EmailService email = new EmailService(null) {
                @Override public boolean isConfigured() { return true; }
                @Override public String reportRecipient() { return "support@example.com"; }
                @Override public void send(String to, String subject, String body, byte[] screenshot) {
                    if (sends.incrementAndGet() == 1 && failFirst) throw new IllegalStateException("Test failure");
                }
            };
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(new Label("Original screen")), 500, 300));
            stage.show();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicInteger step = new AtomicInteger();
            AtomicInteger ticks = new AtomicInteger();
            Timeline poll = new Timeline();
            poll.getKeyFrames().add(new KeyFrame(Duration.millis(50), event -> {
                try {
                    assertTrue("Report completion timed out", ticks.incrementAndGet() < 200);
                    Stage reportWindow = new ArrayList<>(Window.getWindows()).stream()
                        .filter(w -> w instanceof Stage s && s.getOwner() == stage)
                        .map(w -> (Stage) w).findFirst().orElseThrow();
                    assertEquals("Sending must not open another popup", 1,
                        new ArrayList<>(Window.getWindows()).stream()
                            .filter(w -> w instanceof Stage s && s != stage).count());
                    DialogPane pane = (DialogPane) reportWindow.getScene().getRoot();
                    ScrollPane scroll = (ScrollPane) pane.getContent();
                    VBox form = (VBox) scroll.getContent();
                    Label status = (Label) form.getChildren().getLast();
                    TextArea description = (TextArea) form.lookup(".text-area");
                    Button send = (Button) pane.lookupButton(pane.getButtonTypes().get(0));
                    Button draft = (Button) pane.lookupButton(pane.getButtonTypes().get(1));
                    Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
                    if (step.get() == 0) {
                        pane.applyCss();
                        pane.layout();
                        for (Button button : new Button[] {send, draft, cancel}) {
                            assertTrue("Full button text must fit: " + button.getText(),
                                button.getWidth() + 1 >= button.prefWidth(-1));
                            assertTrue("Button must fit inside the dialog",
                                button.localToScene(button.getBoundsInLocal()).getMaxX() <= pane.getWidth() + 1);
                        }
                        description.setText("Report completion regression");
                        ((CheckBox) form.lookup(".check-box")).setSelected(false);
                        step.incrementAndGet();
                        send.fire();
                    } else if (failFirst && step.get() == 1 && status.getText().contains("Test failure")) {
                        assertFalse(send.isDisabled());
                        assertFalse(draft.isDisabled());
                        assertFalse(cancel.isDisabled());
                        assertEquals("Report completion regression", description.getText());
                        step.incrementAndGet();
                        send.fire();
                    } else if (status.getText().equals(UiTranslator.text("Your report was sent. Thank you!"))) {
                        assertTrue(reportWindow.isShowing());
                        assertTrue(send.isDisabled());
                        assertTrue(draft.isDisabled());
                        assertFalse(cancel.isDisabled());
                        assertEquals(UiTranslator.text("Close"), cancel.getText());
                        assertEquals(failFirst ? 2 : 1, sends.get());
                        poll.stop();
                        cancel.fire();
                        assertFalse(reportWindow.isShowing());
                        step.set(3);
                    }
                } catch (Throwable t) {
                    failure.set(t);
                    poll.stop();
                    new ArrayList<>(Window.getWindows()).stream()
                        .filter(w -> w instanceof Stage s && s.getOwner() == stage).forEach(Window::hide);
                }
            }));
            poll.setCycleCount(Timeline.INDEFINITE);
            try {
                poll.play();
                ReportUi.show(stage.getScene(), email);
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertEquals("Success must leave an operable Close button", 3, step.get());
            } finally {
                poll.stop();
                stage.close();
                translator.setLanguage(previousLanguage);
            }
        });
    }

    @Test
    public void updateAlertKeepsItsRootAndSeparatesReportFromUpdateButtons() throws Exception {
        onFx(() -> {
            Platform.setImplicitExit(false);
            ReportUi.install();
            ButtonType download = new ButtonType("Download update", ButtonBar.ButtonData.OK_DONE);
            ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                "The update downloads in the background and is used the next time the app starts.",
                download, later);
            alert.setHeaderText("Update available: version 1.2.5");
            alert.getDialogPane().getStylesheets().add(ReportUiTest.class
                .getResource("/com/dnd/ui/styles/dnd-theme.css").toExternalForm());
            try {
                alert.show();
                DialogPane pane = alert.getDialogPane();
                Scene scene = pane.getScene();
                assertSame(pane, scene.getRoot());
                pane.applyCss();
                pane.layout();
                Button report = reportButton(scene);
                var bounds = report.localToScene(report.getBoundsInLocal());
                for (ButtonType type : new ButtonType[] { download, later }) {
                    var button = pane.lookupButton(type);
                    assertFalse("Report must not overlap " + type.getText(),
                        bounds.intersects(button.localToScene(button.getBoundsInLocal())));
                }
                ((Button) pane.lookupButton(later)).fire();
                assertFalse(alert.isShowing());
                assertSame(later, alert.getResult());
            } finally {
                alert.close();
            }
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
                try {
                    Scene modalScene = modal.getDialogPane().getScene();
                    assertSame("Dialog sizing requires the DialogPane to remain the scene root",
                        modal.getDialogPane(), modalScene.getRoot());
                    Button modalReport = reportButton(modalScene);
                    assertSame(modal.getDialogPane().lookup(".button-bar"), modalReport.getParent().getParent());
                    nestedFailure.set(null);
                    Platform.runLater(() -> {
                        try {
                            Stage reportWindow = new ArrayList<>(Window.getWindows()).stream()
                                .filter(w -> w instanceof Stage s && s.getOwner() == modalScene.getWindow()
                                    && "Report a bug or suggestion".equals(s.getTitle()))
                                .map(w -> (Stage) w).findFirst().orElseThrow();
                            assertTrue(modal.isShowing());
                            assertSame(modal.getDialogPane(), modalScene.getRoot());
                            reportWindow.hide();
                        } catch (Throwable t) {
                            nestedFailure.set(t);
                            new ArrayList<>(Window.getWindows()).stream()
                                .filter(w -> w instanceof Stage s && s.getOwner() == modalScene.getWindow())
                                .forEach(Window::hide);
                        }
                    });
                    modalReport.fire();
                    if (nestedFailure.get() != null) throw new AssertionError(nestedFailure.get());
                    assertTrue("Reporting must not dismiss the original dialog", modal.isShowing());
                    assertNull(modal.getResult());
                    ((Button) modal.getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
                    assertFalse(modal.isShowing());
                } finally {
                    modal.close();
                }
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
