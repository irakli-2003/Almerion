package com.dnd.ui;

import com.dnd.update.UpdateChecker;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Checks for a newer release once at start-up and offers to download it (installed app only). */
public final class UpdatePrompt {
    private static final Logger LOGGER = Logger.getLogger(UpdatePrompt.class.getName());
    private static final String CSS = "/com/dnd/ui/styles/dnd-theme.css";

    private UpdatePrompt() {}

    public static void checkInBackground(Stage owner) {
        if (!UpdateChecker.enabled()) return;
        Thread t = new Thread(() -> {
            try {
                UpdateChecker checker = new UpdateChecker();
                UpdateChecker.Release release = checker.findNewer();
                if (release != null) Platform.runLater(() -> offer(owner, checker, release));
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Update check failed (offline?)", e);
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private static void offer(Stage owner, UpdateChecker checker, UpdateChecker.Release release) {
        ButtonType download = new ButtonType("Download update", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert ask = alert(owner, Alert.AlertType.INFORMATION, "Update available: version " + release.version(),
            "You have version " + UpdateChecker.currentVersion() + ". The update downloads in the background"
                + " and is used the next time the app starts. Your campaigns and accounts are kept.",
            download, later);
        if (ask.showAndWait().orElse(later) != download) return;

        Thread t = new Thread(() -> {
            try {
                UpdateChecker.Outcome outcome = checker.download(release);
                Platform.runLater(() -> {
                    if (outcome == UpdateChecker.Outcome.READY_AFTER_RESTART) offerRestart(owner, release);
                    else offerInstaller(owner, release);
                });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Update download failed", e);
                Platform.runLater(() -> alert(owner, Alert.AlertType.WARNING, "Update failed",
                    "The update could not be downloaded: " + e.getMessage() + "\nThe app will try again next time.",
                    ButtonType.OK).showAndWait());
            }
        }, "update-download");
        t.setDaemon(true);
        t.start();
    }

    private static void offerRestart(Stage owner, UpdateChecker.Release release) {
        ButtonType restart = new ButtonType("Restart now", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Next time", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert ready = alert(owner, Alert.AlertType.INFORMATION, "Version " + release.version() + " is ready",
            "Restart to use it now, or keep working: it starts automatically the next time you open the app.",
            restart, later);
        if (ready.showAndWait().orElse(later) == restart && UpdateChecker.restart()) {
            System.exit(0);
        }
    }

    private static void offerInstaller(Stage owner, UpdateChecker.Release release) {
        ButtonType open = new ButtonType("Open download page", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert needs = alert(owner, Alert.AlertType.INFORMATION, "Version " + release.version() + " needs the installer",
            "This update changes the app's libraries, so it has to be installed with the setup wizard once."
                + " Run it over the current installation; your campaigns and accounts are kept.",
            open, later);
        if (needs.showAndWait().orElse(later) != open) return;
        String url = release.installerUrl() != null ? release.installerUrl() : release.pageUrl();
        if (url == null) return;
        Thread t = new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Could not open " + url, e);
            }
        }, "open-installer");
        t.setDaemon(true);
        t.start();
    }

    private static Alert alert(Stage owner, Alert.AlertType type, String header, String text, ButtonType... buttons) {
        Alert alert = new Alert(type, text, buttons);
        alert.setTitle("Almerion update");
        alert.setHeaderText(header);
        if (owner != null && owner.isShowing()) alert.initOwner(owner);
        var css = UpdatePrompt.class.getResource(CSS);
        if (css != null) alert.getDialogPane().getStylesheets().add(css.toExternalForm());
        return alert;
    }
}
