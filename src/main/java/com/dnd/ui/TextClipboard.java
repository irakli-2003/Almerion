package com.dnd.ui;

import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.GenericStyledArea;

import java.util.Locale;

/** Explicit clipboard bindings also work when a non-Latin keyboard layout is active. */
final class TextClipboard {
    private static final String INSTALLED = "almerion.text.clipboard";

    private TextClipboard() { }

    static void install(Scene scene) {
        if (scene.getProperties().putIfAbsent(INSTALLED, true) != null) return;
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!event.isShortcutDown() || event.isAltDown()) return;
            Node focus = scene.getFocusOwner();
            while (focus != null && !(focus instanceof TextInputControl)
                && !(focus instanceof GenericStyledArea<?, ?, ?>)) focus = focus.getParent();
            if (focus == null || focus instanceof PasswordField) return;
            String text = event.getText().toLowerCase(Locale.ROOT);
            boolean copy = event.getCode() == KeyCode.C || text.equals("c") || text.equals("\u10ea");
            boolean paste = event.getCode() == KeyCode.V || text.equals("v") || text.equals("\u10d5");
            if (!copy && !paste) return;
            if (focus instanceof TextInputControl field) {
                if (copy) field.copy();
                else if (field.isEditable() && !field.isDisabled()) field.paste();
            } else if (focus instanceof GenericStyledArea<?, ?, ?> area) {
                if (copy) area.copy();
                else if (area.isEditable() && !area.isDisabled()) area.paste();
            }
            event.consume();
        });
    }
}
