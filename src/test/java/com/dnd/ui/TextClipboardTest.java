package com.dnd.ui;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class TextClipboardTest {
    @BeforeClass public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Runnable start = () -> { Platform.setImplicitExit(false); ready.countDown(); };
        try { Platform.startup(start); } catch (IllegalStateException running) { Platform.runLater(start); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test public void copiesAndPastesAcrossLayoutsAndHonorsReadOnlyAndTextFormatters() throws Exception {
        onFx(() -> {
            Stage stage = new Stage();
            TextField source = new TextField("\u10d0\u10d1 original");
            TextArea destination = new TextArea("prefix ");
            TextField readOnly = new TextField("unchanged"); readOnly.setEditable(false);
            TextField formatted = new TextField();
            formatted.setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().length() <= 2 ? c : null));
            Scene scene = new Scene(new VBox(source, destination, readOnly, formatted));
            TextClipboard.install(scene); TextClipboard.install(scene);
            stage.setScene(scene); stage.show();
            try {
                source.requestFocus(); source.selectRange(0, 2);
                press(source, KeyCode.C, "c");
                assertEquals("\u10d0\u10d1", Clipboard.getSystemClipboard().getString());
                destination.requestFocus(); destination.positionCaret(destination.getLength());
                press(destination, KeyCode.UNDEFINED, "\u10d5");
                assertEquals("prefix \u10d0\u10d1", destination.getText());
                destination.selectAll(); press(destination, KeyCode.UNDEFINED, "\u10ea");
                readOnly.requestFocus(); readOnly.selectAll(); press(readOnly, KeyCode.V, "v");
                assertEquals("unchanged", readOnly.getText());
                press(readOnly, KeyCode.C, "c");
                assertEquals("unchanged", Clipboard.getSystemClipboard().getString());
                formatted.requestFocus(); press(formatted, KeyCode.V, "v");
                assertEquals("", formatted.getText());
            } finally { stage.close(); }
        });
    }

    @Test public void globalHookHandlesDialogAndNewFieldsWithoutInterceptingPasswords() throws Exception {
        onFx(() -> {
            ReportUi.install();
            Stage stage = new Stage();
            DialogPane pane = new DialogPane();
            VBox fields = new VBox();
            pane.setContent(fields);
            Scene scene = new Scene(pane); stage.setScene(scene); stage.show();
            TextField dynamic = new TextField();
            PasswordField password = new PasswordField();
            fields.getChildren().addAll(dynamic, password);
            try {
                ClipboardContent contents = new ClipboardContent(); contents.putString("pasted");
                Clipboard.getSystemClipboard().setContent(contents);
                dynamic.requestFocus(); press(dynamic, KeyCode.V, "v");
                assertEquals("pasted", dynamic.getText());
                var filtered = new java.util.concurrent.atomic.AtomicBoolean();
                password.addEventFilter(KeyEvent.KEY_PRESSED, event -> filtered.set(true));
                password.requestFocus(); press(password, KeyCode.C, "c");
                assertTrue("Password key event must remain with the native password control", filtered.get());
                assertEquals("pasted", Clipboard.getSystemClipboard().getString());
            } finally { stage.close(); }
        });
    }

    @Test public void sessionRichTextEditorSupportsGeorgianCopyPasteAndReadOnlyCopy() throws Exception {
        onFx(() -> {
            Stage stage = new Stage();
            var area = new org.fxmisc.richtext.CodeArea("session notes");
            Scene scene = new Scene(new VBox(area), 400, 200);
            TextClipboard.install(scene);
            stage.setScene(scene); stage.show();
            try {
                area.requestFocus(); area.selectRange(0, 7);
                press(area, KeyCode.UNDEFINED, "\u10ea");
                assertEquals("session", Clipboard.getSystemClipboard().getString());
                area.moveTo(area.getLength());
                press(area, KeyCode.UNDEFINED, "\u10d5");
                assertEquals("session notessession", area.getText());
                area.setEditable(false); area.selectRange(8, 13);
                press(area, KeyCode.C, "c");
                assertEquals("notes", Clipboard.getSystemClipboard().getString());
                press(area, KeyCode.V, "v");
                assertEquals("session notessession", area.getText());
            } finally { stage.close(); }
        });
    }

    private static void press(javafx.scene.Node field, KeyCode code, String text) {
        Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_PRESSED, "", text, code, false, true, false, false));
    }

    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { work.run(); } catch (Throwable failure) { error.set(failure); } finally { done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
