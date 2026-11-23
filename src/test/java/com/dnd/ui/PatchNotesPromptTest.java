package com.dnd.ui;

import com.dnd.update.PatchNotes;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class PatchNotesPromptTest {
    @Test
    public void showsNotesAndRemembersAcknowledgement() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException running) {
            ready.countDown();
        }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        PatchNotes notes = new PatchNotes(Files.createTempDirectory("notes-ui").resolve("ack"));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            Stage owner = new Stage();
            try {
                Platform.setImplicitExit(false);
                owner.setScene(new Scene(new VBox(), 300, 200));
                owner.show();
                Platform.runLater(() -> {
                    try {
                        Stage dialog = new ArrayList<>(Window.getWindows()).stream()
                            .filter(w -> w instanceof Stage s && s.getOwner() == owner)
                            .map(w -> (Stage) w).findFirst().orElseThrow();
                        DialogPane pane = (DialogPane) dialog.getScene().getRoot();
                        assertTrue(((TextArea) pane.getContent()).getText().contains("Almerion 1.4.0"));
                        ((Button) pane.lookupButton(ButtonType.OK)).fire();
                    } catch (Throwable t) {
                        failure.set(t);
                        new ArrayList<>(Window.getWindows()).stream()
                            .filter(w -> w instanceof Stage s && s.getOwner() == owner)
                            .forEach(Window::hide);
                    }
                });
                PatchNotesPrompt.showIfNeeded(owner, "1.4.0", notes);
                assertTrue(notes.pending("1.4.0").isEmpty());
                PatchNotesPrompt.showIfNeeded(owner, "1.4.0", notes);
                assertEquals(1, Window.getWindows().stream().filter(Window::isShowing).count());
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                owner.hide();
                done.countDown();
            }
        });
        assertTrue("Patch notes dialog timed out", done.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
