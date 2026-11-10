package com.dnd.ui.scenes;

import com.dnd.data.CampaignArchive;
import com.dnd.cli.storage.CampaignStorage;
import com.dnd.cloud.CampaignDirectory;
import com.dnd.sync.CampaignSyncService;
import com.dnd.ui.i18n.UiTranslator;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;

final class CampaignArchiveUi {
    private static final String ADDED = "Files added: ";
    private static final String UPDATED = "Older files updated: ";
    private static final String KEPT = "Different local files kept: ";
    private static final String BACKUP = "Backup: ";
    private static final String CLAIM = "If it is not in your campaign list yet, claim it below.";
    private CampaignArchiveUi() { }

    static void exportCampaign(BaseScene owner, Path root, Button action, Label status) {
        FileChooser chooser = chooser("Export Complete Campaign");
        chooser.setInitialFileName(root.getFileName() + ".almerion.zip");
        File file = chooser.showSaveDialog(com.dnd.ui.WindowOrder.current());
        if (file == null) return;
        action.setDisable(true);
        status.setText("Exporting the complete campaign, including large files...");
        status.setManaged(true);
        CampaignSyncService.shared().localFiles(() -> {
            CampaignArchive.exportCampaign(root, file.toPath());
            return file.toPath();
        }).whenComplete((saved, error) -> Platform.runLater(() -> {
            action.setDisable(false);
            status.setText("");
            status.setManaged(false);
            if (error != null) owner.showError("Couldn't export the campaign", error);
            else owner.showInfo("Campaign exported", saved.toString());
        }));
    }

    static void importCampaign(BaseScene owner, CampaignStorage storage, String user,
                               Button action, Label status, Runnable reload) {
        File file = chooser("Import Complete Campaign").showOpenDialog(com.dnd.ui.WindowOrder.current());
        if (file == null) return;
        action.setDisable(true);
        status.setText("Checking the campaign export...");
        status.setManaged(true);
        CampaignSyncService.shared().localFiles(() -> {
            var snapshot = CampaignArchive.inspect(file.toPath());
            var record = CampaignDirectory.shared().find(snapshot.campaignId()).orElse(null);
            if (record != null && !record.isDm(user)) {
                throw new java.io.IOException("Only this campaign's Game Masters may import or update its files.");
            }
            return snapshot;
        }).whenComplete((snapshot, error) -> Platform.runLater(() -> {
            if (error != null) {
                action.setDisable(false);
                status.setText("");
                status.setManaged(false);
                owner.showError("Couldn't import the campaign", error);
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Import this complete campaign? The campaign ID identifies an existing copy, even if its folder"
                    + " has a different name. Newer imported files update older local files. Newer or equally"
                    + " dated local files and local-only content are kept."
                    + "\n\nReplaced files are backed up. GitHub sign-in and Git history are not imported.",
                ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText(snapshot.name());
            owner.styleDialog(confirm);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                action.setDisable(false);
                status.setText("");
                status.setManaged(false);
                return;
            }
            status.setText("Importing campaign files...");
            CampaignSyncService.shared().localFiles(() -> CampaignArchive.importCampaign(storage, file.toPath()))
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    action.setDisable(false);
                    status.setText("");
                    status.setManaged(false);
                    if (failure != null) owner.showError("Couldn't import the campaign", failure);
                    else {
                        owner.showInfo("Campaign imported",
                            "Campaign folder: " + result.campaign() + "\n"
                                + UiTranslator.text(ADDED) + result.added() + "\n"
                                + UiTranslator.text(UPDATED) + result.updated() + "\n"
                                + UiTranslator.text(KEPT) + result.kept()
                                + (result.backup() == null ? "" : "\n" + UiTranslator.text(BACKUP) + result.backup())
                                + "\n\n" + UiTranslator.text(CLAIM));
                        reload.run();
                    }
                }));
        }));
    }

    private static FileChooser chooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(UiTranslator.text(title));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Almerion campaign (*.zip)", "*.zip"));
        File directory = ManualFilesUi.defaultDestination();
        if (directory != null) chooser.setInitialDirectory(directory);
        return chooser;
    }
}
