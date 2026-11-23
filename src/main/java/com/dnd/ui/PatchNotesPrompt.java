package com.dnd.ui;

import com.dnd.data.AppHome;
import com.dnd.update.PatchNotes;
import com.dnd.update.UpdateChecker;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Shows the running packaged version's notes until they are acknowledged. */
public final class PatchNotesPrompt {
    private static final Logger LOGGER = Logger.getLogger(PatchNotesPrompt.class.getName());

    private PatchNotesPrompt() {}

    public static void showIfNeeded(Stage owner) {
        String version = UpdateChecker.currentVersion();
        if (version == null) return;
        PatchNotes notes = new PatchNotes(AppHome.resolve("data", "patch-notes-acknowledged"));
        showIfNeeded(owner, version, notes);
    }

    static void showIfNeeded(Stage owner, String version, PatchNotes notes) {
        try {
            var pending = notes.pending(version);
            if (pending.isEmpty()) return;
            TextArea text = new TextArea(pending.get());
            text.setEditable(false);
            text.setWrapText(true);
            text.setPrefRowCount(12);
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "", ButtonType.OK);
            alert.initOwner(owner);
            alert.setTitle("Patch notes");
            alert.setHeaderText("What's new in Almerion " + version);
            alert.getDialogPane().setContent(text);
            var css = PatchNotesPrompt.class.getResource("/com/dnd/ui/styles/dnd-theme.css");
            if (css != null) alert.getDialogPane().getStylesheets().add(css.toExternalForm());
            if (alert.showAndWait().filter(ButtonType.OK::equals).isPresent()) notes.acknowledge(version);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Could not show or save patch notes for " + version, e);
            Alert warning = new Alert(Alert.AlertType.WARNING,
                "Could not load or save patch notes. Please check the application log.", ButtonType.OK);
            warning.initOwner(owner);
            warning.setTitle("Patch notes");
            warning.showAndWait();
        }
    }
}
