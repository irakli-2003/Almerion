package com.dnd.ui.scenes;

import com.dnd.sync.CampaignSyncService;
import com.dnd.sync.ManualFiles;
import com.dnd.ui.UiSession;
import com.dnd.ui.i18n.UiTranslator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Manual transfers are local copies only; closing the notification never blocks campaign use. */
public final class ManualFilesUi {
    private static final Map<Path, ManualFiles.Report> SHOWN = new HashMap<>();
    private static final Set<Path> CHECKING = new HashSet<>();
    private static final String AVAILABLE = "available on this device";
    private static final String MISSING = "missing or different on this device";

    private ManualFilesUi() { }

    /** Installed once for the app, so background autosaves notify even inside other campaign screens. */
    public static void install(UiSession session, Stage main) {
        BaseScene owner = new BaseScene(session) {
            @Override public javafx.scene.Scene build() { throw new UnsupportedOperationException(); }
        };
        CampaignSyncService service = CampaignSyncService.shared();
        java.util.function.BiConsumer<Path, CampaignSyncService.Status> listener = (folder, status) -> {
            if (status.state() == CampaignSyncService.State.SYNCING
                    || status.state() == CampaignSyncService.State.CONFLICT) return;
            Platform.runLater(() -> {
                if (main.isShowing() && session.campaignRoot() != null
                        && CampaignSyncService.key(session.campaignRoot()).equals(folder)) {
                    review(owner, folder, false, () -> { });
                }
            });
        };
        service.addListener(listener);
        main.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> service.removeListener(listener));
    }

    static void review(BaseScene owner, Path root, boolean force, Runnable after) {
        Path key = CampaignSyncService.key(root);
        if (!CHECKING.add(key)) { after.run(); return; }
        CampaignSyncService.shared().localFiles(() -> force ? ManualFiles.inspect(root) : ManualFiles.notificationReport(root)).whenComplete((report, error) ->
            Platform.runLater(() -> {
            if (error != null) {
                CHECKING.remove(key);
                owner.showError("Couldn't check manual-transfer files", cause(error));
                after.run();
                return;
            }
            if (report.isEmpty() || (!force && report.equals(SHOWN.get(key)))) {
                CHECKING.remove(key);
                if (force && report.isEmpty()) owner.showInfo("Manual-transfer files", "No files need manual transfer.");
                after.run();
                return;
            }
            SHOWN.put(key, report);
            Stage stage = window(owner, root, report);
            stage.setOnHidden(e -> {
                CHECKING.remove(key);
                after.run();
            });
            stage.show();
        }));
    }

    static Stage window(BaseScene owner, Path root, ManualFiles.Report report) {
        Stage stage = new Stage();
        com.dnd.ui.WindowOrder.adopt(stage);
        stage.initModality(javafx.stage.Modality.WINDOW_MODAL);
        stage.setTitle("Files not synced through GitHub");
        Label intro = owner.body("Files larger than 100 MiB are not uploaded to GitHub. Campaign data still syncs."
            + " Copy the available files into one transfer folder, move that folder to the other device,"
            + " then import it there. Closing this window leaves everything unchanged.");
        intro.setWrapText(true);
        ListView<String> files = UiTranslator.skip(new ListView<>());
        files.setCellFactory(list -> new ListCell<>() {
            { setWrapText(true); }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setTooltip(empty ? null : new Tooltip(item));
            }
        });
        populate(files, report);
        java.util.concurrent.atomic.AtomicReference<ManualFiles.Report> current = new java.util.concurrent.atomic.AtomicReference<>(report);
        Label status = owner.body("");
        status.setWrapText(true);
        Button export = owner.btn("Copy Files to Folder...", () -> { });
        export.setId("manual-files-export");
        export.setDisable(report.available().isEmpty());
        Button importFiles = owner.btn("Import Files from Folder...", () -> { });
        importFiles.setId("manual-files-import");
        Button close = owner.btn("Ignore and Continue", stage::close);
        close.setId("manual-files-ignore");
        javafx.beans.property.BooleanProperty busy = new javafx.beans.property.SimpleBooleanProperty(false);
        close.disableProperty().bind(busy);
        stage.setOnCloseRequest(e -> { if (busy.get()) e.consume(); });
        FlowPane actions = new FlowPane(10, 8, export, importFiles, close);
        for (var node : actions.getChildren()) ((Button) node).setMinWidth(Region.USE_PREF_SIZE);
        export.setOnAction(e -> {
            File chosen = chooser("Choose a transfer destination or an existing transfer folder").showDialog(stage);
            if (chosen == null) return;
            export.setDisable(true);
            importFiles.setDisable(true);
            busy.set(true);
            status.setText("Copying files...");
            CampaignSyncService.shared().localFiles(() -> ManualFiles.exportFiles(root, chosen.toPath()))
                .whenComplete((destination, error) -> Platform.runLater(() -> {
                busy.set(false);
                status.setText(error == null ? UiTranslator.text("Transfer folder created: ") + destination
                    : BaseScene.friendlyError(cause(error)));
                export.setDisable(current.get().available().isEmpty());
                importFiles.setDisable(false);
            }));
        });
        importFiles.setOnAction(e -> {
            File chosen = chooser("Choose the Almerion transfer folder").showDialog(stage);
            if (chosen == null) return;
            export.setDisable(true);
            importFiles.setDisable(true);
            busy.set(true);
            status.setText("Checking and importing files...");
            CampaignSyncService.shared().localFiles(() -> {
                int count = ManualFiles.importFiles(root, chosen.toPath());
                return Map.entry(count, ManualFiles.inspect(root));
            }).whenComplete((result, error) -> Platform.runLater(() -> {
                busy.set(false);
                if (error != null) status.setText(BaseScene.friendlyError(cause(error)));
                else {
                    status.setText(UiTranslator.text("Imported files: ") + result.getKey());
                    current.set(result.getValue());
                    populate(files, result.getValue());
                    SHOWN.put(CampaignSyncService.key(root), result.getValue());
                }
                export.setDisable(current.get().available().isEmpty());
                importFiles.setDisable(false);
            }));
        });
        VBox content = new VBox(12, intro, files, actions, status);
        content.setPadding(new Insets(20));
        VBox.setVgrow(files, Priority.ALWAYS);
        stage.setScene(owner.themedScene(content, 760, 470));
        return stage;
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024d * 1024));
    }

    private static void populate(ListView<String> files, ManualFiles.Report report) {
        files.getItems().clear();
        report.available().forEach(entry -> files.getItems().add(entry.path() + "  ("
            + megabytes(entry.size()) + " MiB) - " + UiTranslator.text(AVAILABLE)));
        report.missing().forEach(entry -> files.getItems().add(entry.path() + "  ("
            + megabytes(entry.size()) + " MiB) - " + UiTranslator.text(MISSING)));
    }

    private static Throwable cause(Throwable error) {
        return error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static DirectoryChooser chooser(String title) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(UiTranslator.text(title));
        File directory = defaultDestination();
        if (directory != null) chooser.setInitialDirectory(directory);
        return chooser;
    }

    static File defaultDestination() {
        File desktop = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
        if (desktop != null && desktop.isDirectory()) return desktop;
        Path home = Path.of(System.getProperty("user.home"));
        return Files.isDirectory(home) ? home.toFile() : null;
    }
}
