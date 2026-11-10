package com.dnd.ui.scenes;

import com.dnd.ui.ReportUi;
import com.dnd.ui.i18n.TranslationService;
import com.dnd.ui.i18n.UiTranslator;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class GitHubSyncUiTest {
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
    public void managementActionsWrapWithoutClippingInEnglishAndGeorgian() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Platform.setImplicitExit(false);
                ReportUi.install();
                UiTranslator translator = UiTranslator.get();
                if (translator == null) translator = UiTranslator.install(TranslationService.createDefault(), null);
                String previous = translator.getLanguage().code();
                try {
                    for (String language : new String[] {"en", "ka"}) {
                        translator.setLanguage(language);
                        for (boolean extras : new boolean[] {false, true}) {
                            Alert alert = GitHubSyncUi.managementDialog(new BaseScene(null) {
                                @Override public javafx.scene.Scene build() { throw new UnsupportedOperationException(); }
                            },
                                "gm/almerion-campaign-zurabaach", "Synced with GitHub", extras ? 12 : 0, extras);
                            try {
                                alert.show();
                                DialogPane pane = alert.getDialogPane();
                                assertSame(pane, pane.getScene().getRoot());
                                pane.applyCss();
                                pane.layout();
                                VBox content = (VBox) pane.getContent();
                                FlowPane actions = (FlowPane) content.getChildren().getLast();
                                assertEquals(extras ? 4 : 2, actions.getChildren().size());
                                for (var node : actions.getChildren()) {
                                    Button button = (Button) node;
                                    assertTrue("Full caption must fit: " + button.getText(),
                                        button.getWidth() + 1 >= button.prefWidth(-1));
                                    assertTrue(button.getBoundsInParent().getMaxX() <= actions.getWidth() + 1);
                                    assertTrue(button.getBoundsInParent().getMaxY() <= actions.getHeight() + 1);
                                    for (var other : actions.getChildren()) {
                                        if (node != other) assertFalse(node.getBoundsInParent()
                                            .intersects(other.getBoundsInParent()));
                                    }
                                }
                                Button sync = (Button) actions.getChildren().getFirst();
                                sync.fire();
                                assertFalse(alert.isShowing());
                                assertEquals("Sync Now", alert.getResult().getText());
                            } finally {
                                alert.setResult(ButtonType.CLOSE);
                                alert.close();
                            }
                        }
                    }
                } finally {
                    translator.setLanguage(previous);
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
