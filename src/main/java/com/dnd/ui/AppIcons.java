package com.dnd.ui;

import javafx.collections.ListChangeListener;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Puts the app's d20 logo in the title bar (and taskbar entry) of every window the app opens. */
public final class AppIcons {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static List<Image> icons;

    private AppIcons() {}

    public static List<Image> icons() {
        if (icons == null) {
            icons = new ArrayList<>();
            for (int size : SIZES) {
                try (InputStream in = AppIcons.class.getResourceAsStream("/com/dnd/ui/icons/app-" + size + ".png")) {
                    if (in != null) icons.add(new Image(in));
                } catch (Exception ignored) {
                    // A missing size just falls back to the others.
                }
            }
        }
        return icons;
    }

    /** Applies the logo to every open window and to each window opened from now on. */
    public static void install() {
        Window.getWindows().forEach(AppIcons::apply);
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(AppIcons::apply);
            }
        });
    }

    public static void apply(Window window) {
        if (window instanceof Stage stage && stage.getIcons().isEmpty()) {
            stage.getIcons().setAll(icons());
        }
    }
}
