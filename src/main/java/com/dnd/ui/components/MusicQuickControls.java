package com.dnd.ui.components;

import com.dnd.ui.music.MusicPlayer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.function.Consumer;

/**
 * Previous / pause-continue / next / stop buttons for the shared music player, so the DM can
 * control playback from any toolbar without opening the music panel.
 */
public final class MusicQuickControls {
    private MusicQuickControls() {
    }

    public static List<Node> create() {
        MusicPlayer player = MusicPlayer.get();
        Button previous = button("⏮", "Previous track", player::previous);
        Button toggle = button("⏸", "Pause / continue the music", () -> {
            MusicPlayer.Status s = player.status();
            if (s.paused()) player.resume(); else if (s.isPlaying()) player.pause();
        });
        Button next = button("⏭", "Next track", player::next);
        Button stop = button("⏹", "Stop the music (fades out)", player::stop);

        refresh(toggle, player.status());
        // The player outlives every window, so hold the button weakly and unhook once it is gone.
        WeakReference<Button> ref = new WeakReference<>(toggle);
        Consumer<MusicPlayer.Status>[] holder = new Consumer[1];
        holder[0] = status -> Platform.runLater(() -> {
            Button b = ref.get();
            if (b == null) {
                player.removeListener(holder[0]);
            } else {
                refresh(b, status);
            }
        });
        player.addListener(holder[0]);
        return List.of(previous, toggle, next, stop);
    }

    /** Shows ▶ when paused (click continues) and ⏸ otherwise. */
    static String toggleLabel(MusicPlayer.Status status) {
        return status != null && status.paused() ? "▶" : "⏸";
    }

    private static void refresh(Button toggle, MusicPlayer.Status status) {
        toggle.setText(toggleLabel(status));
        toggle.getTooltip().setText(status != null && status.paused() ? "Continue the music" : "Pause the music");
    }

    private static Button button(String text, String tooltip, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("dnd-button");
        b.setTooltip(new Tooltip(tooltip));
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }
}
