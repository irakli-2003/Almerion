package com.dnd.ui;

import javafx.collections.ListChangeListener;
import javafx.scene.control.Tooltip;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Hover information panels stay open for as long as the mouse rests on their node; JavaFX
 * would otherwise hide every tooltip after five seconds.
 */
public final class TooltipPersistence {
    private static boolean installed;

    private TooltipPersistence() {
    }

    /** Call once on the FX thread. Applies to every tooltip, including ones created later. */
    public static void install() {
        if (installed) return;
        installed = true;
        Window.getWindows().forEach(TooltipPersistence::keepOpen);
        // A tooltip joins the window list while it is being shown, before its hide timer
        // reads the show duration, so updating it here takes effect for that showing.
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(TooltipPersistence::keepOpen);
            }
        });
    }

    static void keepOpen(Window window) {
        if (window instanceof Tooltip tip) keepOpen(tip);
    }

    public static void keepOpen(Tooltip tip) {
        if (!Duration.INDEFINITE.equals(tip.getShowDuration())) tip.setShowDuration(Duration.INDEFINITE);
    }
}
