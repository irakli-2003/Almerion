package com.dnd.ui.i18n;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.NodeOrientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeTableView;
import javafx.scene.text.Text;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Translates the running UI in place. Every window's scene graph is walked and each piece of
 * displayed text (labels, buttons, cells, tabs, menu items, prompts, tooltips, window titles,
 * standalone {@link Text} nodes) is replaced by its translation, remembering the English
 * original so later changes are followed and the switch back to English is exact.
 *
 * <p>User input ({@link TextInputControl#textProperty()}) and rich-text editors are never
 * touched. Mark any other subtree that shows user-authored content with {@link #skip(Node)}
 * (or the {@code no-translate} style class). With English selected nothing is hooked at all.</p>
 *
 * <p>All methods must be called on the FX thread.</p>
 */
public final class UiTranslator {
    public static final String SKIP_KEY = "i18n.skip";
    public static final String SKIP_STYLE_CLASS = "no-translate";
    private static final String HOOKED = "i18n.hooked";
    private static final String SLOT_PREFIX = "i18n.slot.";
    private static final String FONT_SHEET = "i18n.fontSheet";
    private static final String RTL_SET = "i18n.rtl";

    private static UiTranslator instance;

    private final TranslationService service;
    private final AppSettings settings;
    private final Set<Slot> slots = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Scene> scenes = Collections.newSetFromMap(new WeakHashMap<>());
    private final ReadOnlyObjectWrapper<Language> language = new ReadOnlyObjectWrapper<>(Language.english());
    private final ListChangeListener<Node> childrenListener = change -> {
        while (change.next()) {
            for (Node added : new ArrayList<>(change.getAddedSubList())) walk(added);
        }
    };
    private boolean hooked;

    UiTranslator(TranslationService service, AppSettings settings) {
        this.service = service;
        this.settings = settings;
    }

    /** Creates the app-wide translator and applies the saved language. Call once on the FX thread. */
    public static UiTranslator install(TranslationService service, AppSettings settings) {
        instance = new UiTranslator(service, settings);
        instance.applyLanguage(settings == null ? Language.ENGLISH_CODE : settings.getLanguage());
        return instance;
    }

    /** The installed translator, or {@code null} when none was installed. */
    public static UiTranslator get() {
        return instance;
    }

    /** Keeps {@code node} and everything inside it in the original language (for user-authored text). */
    public static <T extends Node> T skip(T node) {
        node.getProperties().put(SKIP_KEY, Boolean.TRUE);
        return node;
    }

    public ReadOnlyObjectProperty<Language> languageProperty() {
        return language.getReadOnlyProperty();
    }

    public Language getLanguage() {
        return language.get();
    }

    /** Switches the UI language, persists the choice and retranslates every open window. */
    public void setLanguage(String code) {
        Language next = Language.byCode(code);
        if (settings != null) settings.setLanguage(next.code());
        if (next.equals(language.get())) return;
        applyLanguage(next.code());
    }

    void applyLanguage(String code) {
        Language next = Language.byCode(code);
        service.setLanguage(next.code());
        language.set(next);
        if (!next.isEnglish()) hookAllWindows();
        for (Scene scene : new ArrayList<>(scenes)) applySceneStyle(scene);
        for (Slot slot : new ArrayList<>(slots)) slot.refresh();
    }

    /** True once windows are being followed (never while only English has been used). */
    boolean isHooked() {
        return hooked;
    }

    private void hookAllWindows() {
        if (hooked) return;
        hooked = true;
        for (Window window : new ArrayList<>(Window.getWindows())) hookWindow(window);
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window added : new ArrayList<>(change.getAddedSubList())) hookWindow(added);
            }
        });
    }

    private void hookWindow(Window window) {
        Map<Object, Object> props = window.getProperties();
        if (props.containsKey(HOOKED)) return;
        props.put(HOOKED, Boolean.TRUE);
        if (window instanceof PopupWindow popup && isSkipped(popup.getOwnerNode())) return;
        if (window instanceof Tooltip tip) trackTooltip(tip);
        if (window instanceof Stage stage) track(props, "title", stage.titleProperty());
        window.sceneProperty().addListener((obs, old, scene) -> hookScene(scene));
        hookScene(window.getScene());
    }

    void hookScene(Scene scene) {
        if (scene == null) return;
        if (!scene.getProperties().containsKey(HOOKED)) {
            scene.getProperties().put(HOOKED, Boolean.TRUE);
            scenes.add(scene);
            scene.rootProperty().addListener((obs, old, root) -> {
                walk(root);
                applySceneStyle(scene);
            });
        }
        walk(scene.getRoot());
        applySceneStyle(scene);
    }

    /** Right-to-left layout and, for scripts the theme font can't show, a fallback system font. */
    private void applySceneStyle(Scene scene) {
        Language lang = language.get();
        if (!(scene.getWindow() instanceof PopupWindow)) {
            if (lang.isRightToLeft()) {
                scene.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
                scene.getProperties().put(RTL_SET, Boolean.TRUE);
            } else if (scene.getProperties().remove(RTL_SET) != null) {
                scene.setNodeOrientation(NodeOrientation.INHERIT);
            }
        }
        Parent root = scene.getRoot();
        if (root == null) return;
        Object previous = root.getProperties().remove(FONT_SHEET);
        if (previous != null) root.getStylesheets().remove(previous);
        String font = lang.fontOverride();
        if (font != null) {
            String sheet = fontStylesheet(font);
            root.getStylesheets().add(sheet);
            root.getProperties().put(FONT_SHEET, sheet);
        }
    }

    static String fontStylesheet(String family) {
        String css = ".root, .root .text-input, .root .token-label, .root .timer-display, .root .player-view"
            + " { -fx-font-family: \"" + family + "\"; }";
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }

    private void walk(Node node) {
        if (node == null) return;
        Map<Object, Object> props = node.getProperties();
        if (props.containsKey(HOOKED) || isSkipMarked(node)) return;
        props.put(HOOKED, Boolean.TRUE);
        if (node instanceof TextInputControl input) {
            track(props, "prompt", input.promptTextProperty());
            hookControl(input);
            return;
        }
        // RichTextFX editors (storyline, notes) hold user text in their own Text nodes.
        if (node.getClass().getName().startsWith("org.fxmisc.")) return;
        if (node instanceof Labeled labeled) {
            track(props, "text", labeled.textProperty());
        } else if (node instanceof Text text && !"LabeledText".equals(node.getClass().getSimpleName())) {
            track(props, "text", text.textProperty());
        }
        if (node instanceof Control control) hookControl(control);
        if (node instanceof DialogPane pane) {
            track(props, "header", pane.headerTextProperty());
            track(props, "content", pane.contentTextProperty());
        }
        if (node instanceof TabPane tabs) hookTabs(tabs.getTabs());
        if (node instanceof TableView<?> table) hookColumns(table.getColumns());
        if (node instanceof TreeTableView<?> table) hookColumns(table.getColumns());
        if (node instanceof MenuButton button) hookMenuItems(button.getItems());
        if (node instanceof MenuBar bar) hookMenuItems(bar.getMenus());
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().addListener(childrenListener);
            for (Node child : new ArrayList<>(parent.getChildrenUnmodifiable())) walk(child);
        }
    }

    private void hookControl(Control control) {
        control.tooltipProperty().addListener((obs, old, tip) -> trackTooltip(tip));
        trackTooltip(control.getTooltip());
        control.contextMenuProperty().addListener((obs, old, menu) -> hookContextMenu(menu));
        hookContextMenu(control.getContextMenu());
    }

    private void trackTooltip(Tooltip tip) {
        if (tip == null) return;
        track(tip.getProperties(), "text", tip.textProperty());
        walk(tip.getGraphic());
    }

    private void hookContextMenu(ContextMenu menu) {
        if (menu == null || menu.getProperties().containsKey(HOOKED)) return;
        menu.getProperties().put(HOOKED, Boolean.TRUE);
        hookMenuItems(menu.getItems());
    }

    private <T extends MenuItem> void hookMenuItems(ObservableList<T> items) {
        items.addListener((ListChangeListener<T>) change -> {
            while (change.next()) {
                for (T added : new ArrayList<>(change.getAddedSubList())) hookMenuItem(added);
            }
        });
        for (T item : new ArrayList<>(items)) hookMenuItem(item);
    }

    private void hookMenuItem(MenuItem item) {
        if (item == null) return;
        Map<Object, Object> props = item.getProperties();
        if (props.containsKey(HOOKED) || Boolean.TRUE.equals(props.get(SKIP_KEY))
            || item.getStyleClass().contains(SKIP_STYLE_CLASS)) return;
        props.put(HOOKED, Boolean.TRUE);
        track(props, "text", item.textProperty());
        if (item instanceof Menu menu) hookMenuItems(menu.getItems());
        if (item instanceof CustomMenuItem custom) walk(custom.getContent());
    }

    private void hookTabs(ObservableList<Tab> tabs) {
        tabs.addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) {
                for (Tab added : new ArrayList<>(change.getAddedSubList())) hookTab(added);
            }
        });
        for (Tab tab : new ArrayList<>(tabs)) hookTab(tab);
    }

    private void hookTab(Tab tab) {
        Map<Object, Object> props = tab.getProperties();
        if (props.containsKey(HOOKED) || Boolean.TRUE.equals(props.get(SKIP_KEY))) return;
        props.put(HOOKED, Boolean.TRUE);
        track(props, "text", tab.textProperty());
        tab.tooltipProperty().addListener((obs, old, tip) -> trackTooltip(tip));
        trackTooltip(tab.getTooltip());
        tab.contextMenuProperty().addListener((obs, old, menu) -> hookContextMenu(menu));
        hookContextMenu(tab.getContextMenu());
    }

    private <T extends TableColumnBase<?, ?>> void hookColumns(ObservableList<T> columns) {
        columns.addListener((ListChangeListener<T>) change -> {
            while (change.next()) {
                for (T added : new ArrayList<>(change.getAddedSubList())) hookColumn(added);
            }
        });
        for (T column : new ArrayList<>(columns)) hookColumn(column);
    }

    private void hookColumn(TableColumnBase<?, ?> column) {
        Map<Object, Object> props = column.getProperties();
        if (props.containsKey(HOOKED)) return;
        props.put(HOOKED, Boolean.TRUE);
        track(props, "text", column.textProperty());
        hookContextMenu(column.getContextMenu());
        hookColumns(column.getColumns());
    }

    private void track(Map<Object, Object> props, String name, StringProperty property) {
        String key = SLOT_PREFIX + name;
        if (props.containsKey(key)) return;
        Slot slot = new Slot(property);
        props.put(key, slot);
        slots.add(slot);
        if (!language.get().isEnglish()) slot.refresh();
    }

    private static boolean isSkipMarked(Node node) {
        return Boolean.TRUE.equals(node.getProperties().get(SKIP_KEY)) || node.getStyleClass().contains(SKIP_STYLE_CLASS);
    }

    /** True when the node or any ancestor (following popups to their owner) is marked as skipped. */
    static boolean isSkipped(Node node) {
        for (Node n = node; n != null; ) {
            if (isSkipMarked(n)) return true;
            Node parent = n.getParent();
            if (parent == null && n.getScene() != null && n.getScene().getWindow() instanceof PopupWindow popup) {
                parent = popup.getOwnerNode();
            }
            n = parent;
        }
        return false;
    }

    /** One translated text property: remembers the English original and what was put in its place. */
    private final class Slot implements ChangeListener<String> {
        private final StringProperty property;
        private String original;
        private String applied;
        private boolean setting;

        Slot(StringProperty property) {
            this.property = property;
            this.original = property.get();
            property.addListener(this);
        }

        @Override
        public void changed(ObservableValue<? extends String> obs, String old, String now) {
            if (setting) return;
            if (applied != null && applied.equals(now)) return;
            original = now;
            applied = null;
            if (!language.get().isEnglish()) refresh();
        }

        void refresh() {
            if (property.isBound()) return;
            String target = language.get().isEnglish() ? original : service.lookup(original);
            if (!Objects.equals(property.get(), target)) {
                setting = true;
                try {
                    property.set(target);
                } finally {
                    setting = false;
                }
            }
            applied = Objects.equals(target, original) ? null : target;
        }
    }
}
