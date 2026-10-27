package com.dnd.ui.i18n;

import javafx.application.Platform;
import javafx.geometry.NodeOrientation;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UiTranslatorTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final FakeProvider provider = new FakeProvider();
    private TranslationService service;
    private UiTranslator translator;

    @BeforeClass
    public static void startToolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            ready.countDown();
        }
        assertTrue("JavaFX toolkit did not start", ready.await(20, TimeUnit.SECONDS));
    }

    private static void onFx(ThrowingRunnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue("FX task timed out", done.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw failure.get() instanceof Exception e ? e : new RuntimeException(failure.get());
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void setUpTranslator() throws Exception {
        service = new TranslationService(provider, temp.getRoot().toPath(), Platform::runLater, 10);
        onFx(() -> translator = new UiTranslator(service, AppSettings.load(temp.getRoot().toPath().resolve("settings.json"))));
    }

    /** Lets the background batch finish and its FX-thread callbacks run. */
    private void settle() throws Exception {
        service.awaitIdle(5000);
        onFx(() -> { });
        onFx(() -> { });
    }

    @Test
    public void translatesASceneAndFollowsLaterChanges() throws Exception {
        setUpTranslator();
        Label title = new Label("Create Character");
        Button level = new Button("Level 3");
        TextField name = new TextField("Gandalf the Grey");
        name.setPromptText("Your name");
        Text hover = new Text("Hit points");
        TextFlow flow = new TextFlow(hover);
        Tab tab = new Tab("Stats");
        TabPane tabs = new TabPane(tab);
        MenuItem open = new MenuItem("Open map");
        MenuButton menu = new MenuButton("Actions", null, open);
        Tooltip tip = new Tooltip("Roll the dice");
        level.setTooltip(tip);
        VBox root = new VBox(title, level, name, flow, tabs, menu);
        Scene scene = new Scene(root);

        onFx(() -> {
            translator.applyLanguage("de");
            translator.hookScene(scene);
        });
        settle();

        onFx(() -> {
            assertEquals("DE:Create Character", title.getText());
            assertEquals("DE:Level 3", level.getText());
            assertEquals("Gandalf the Grey", name.getText());
            assertEquals("DE:Your name", name.getPromptText());
            assertEquals("DE:Hit points", hover.getText());
            assertEquals("DE:Stats", tab.getText());
            assertEquals("DE:Open map", open.getText());
            assertEquals("DE:Actions", menu.getText());
            assertEquals("DE:Roll the dice", tip.getText());

            level.setText("Level 4");
            assertEquals("DE:Level 4", level.getText());
            title.setText("Log Out");
            root.getChildren().add(new Label("Added later"));
        });
        settle();

        onFx(() -> {
            assertEquals("DE:Log Out", title.getText());
            assertEquals("DE:Added later", ((Label) root.getChildren().get(root.getChildren().size() - 1)).getText());

            translator.applyLanguage("en");
            assertEquals("Log Out", title.getText());
            assertEquals("Level 4", level.getText());
            assertEquals("Your name", name.getPromptText());
            assertEquals("Stats", tab.getText());
            assertEquals("Open map", open.getText());
            assertEquals("Roll the dice", tip.getText());
            assertEquals("Gandalf the Grey", name.getText());
        });
    }

    @Test
    public void skippedSubtreesStayInEnglish() throws Exception {
        setUpTranslator();
        Label notes = new Label("My secret campaign notes");
        VBox skipped = UiTranslator.skip(new VBox(notes));
        Label styled = new Label("Player handout");
        styled.getStyleClass().add(UiTranslator.SKIP_STYLE_CLASS);
        Label normal = new Label("Save");
        Scene scene = new Scene(new VBox(skipped, styled, normal));

        onFx(() -> {
            translator.applyLanguage("de");
            translator.hookScene(scene);
            skipped.getChildren().add(new Label("Late note"));
        });
        settle();

        onFx(() -> {
            assertEquals("DE:Save", normal.getText());
            assertEquals("My secret campaign notes", notes.getText());
            assertEquals("Player handout", styled.getText());
            assertEquals("Late note", ((Label) skipped.getChildren().get(1)).getText());
            assertTrue(UiTranslator.isSkipped(notes));
        });
        assertFalse(provider.requests.stream().anyMatch(r -> r.contains("secret") || r.contains("handout") || r.contains("Late note")));
    }

    @Test
    public void englishDoesNothing() throws Exception {
        setUpTranslator();
        Label label = new Label("Create Character");
        new Scene(new VBox(label));
        onFx(() -> translator.applyLanguage("en"));
        settle();
        onFx(() -> {
            assertFalse(translator.isHooked());
            assertEquals("Create Character", label.getText());
            assertNull(label.getProperties().get("i18n.hooked"));
        });
        assertTrue(provider.requests.isEmpty());
    }

    @Test
    public void rightToLeftAndFontFallbackFollowTheLanguage() throws Exception {
        setUpTranslator();
        Label label = new Label("Inventory");
        VBox root = new VBox(label);
        Scene scene = new Scene(root);

        onFx(() -> {
            translator.applyLanguage("ar");
            translator.hookScene(scene);
            assertEquals(NodeOrientation.RIGHT_TO_LEFT, scene.getNodeOrientation());
            translator.applyLanguage("am");
            assertEquals(NodeOrientation.INHERIT, scene.getNodeOrientation());
            assertEquals(1, root.getStylesheets().size());
            root.applyCss();
            if (Font.getFamilies().contains("Ebrima")) assertEquals("Ebrima", label.getFont().getFamily());
            translator.applyLanguage("de");
            assertTrue(root.getStylesheets().isEmpty());
        });
        settle();
    }

    @Test
    public void pickerShowsTheCurrentLanguageAndIsNeverTranslated() throws Exception {
        setUpTranslator();
        onFx(() -> {
            UiTranslator.install(service, AppSettings.load(temp.getRoot().toPath().resolve("picker-settings.json")));
            LanguagePicker picker = new LanguagePicker();
            assertTrue(picker.getText().contains("English"));
            UiTranslator.get().setLanguage("ka");
            assertTrue(picker.getText().contains("ქართული"));
            assertTrue(UiTranslator.isSkipped(picker));
            UiTranslator.get().setLanguage("en");
            assertTrue(picker.getText().contains("English"));
        });
        assertEquals("en", AppSettings.load(temp.getRoot().toPath().resolve("picker-settings.json")).getLanguage());
    }
}
