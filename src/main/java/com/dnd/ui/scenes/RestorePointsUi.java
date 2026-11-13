package com.dnd.ui.scenes;

import com.dnd.cloud.CampaignDirectory;
import com.dnd.cloud.CampaignMeta;
import com.dnd.data.CampaignArchive;
import com.dnd.data.CampaignRestorePoints;
import com.dnd.sync.CampaignSyncService;
import com.dnd.ui.i18n.UiTranslator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** GM-only local history, including campaigns deleted by this account. */
final class RestorePointsUi {
    private record Target(CampaignRestorePoints.Point point, Path folder) {
        @Override public String toString() { return point.name() + " - " + java.time.Instant.ofEpochMilli(point.createdAt())
            + " - " + UiTranslator.text(point.reason()); }
    }
    private record Compared(String saved, String current) { }
    private final BaseScene owner;
    private final Path fixedCampaign;
    private final Runnable reloadOwner;
    private final Stage stage = new Stage();
    private final ComboBox<Target> points = UiTranslator.skip(new ComboBox<>());
    private final ListView<CampaignArchive.FileEntry> files = UiTranslator.skip(new ListView<>());
    private final TextArea saved = new TextArea();
    private final TextArea current = new TextArea();
    private final Label status = new Label();
    private final VBox layout = new VBox(10);
    private CampaignRestorePoints store;
    private CampaignRestorePoints.Plan plan;
    private long previewGeneration;
    private boolean changingPoint;

    static void show(BaseScene owner, Path campaign, Runnable reload) {
        new RestorePointsUi(owner, campaign, reload).open();
    }

    private RestorePointsUi(BaseScene owner, Path campaign, Runnable reload) {
        this.owner = owner;
        fixedCampaign = campaign;
        reloadOwner = reload;
    }

    private Path deviceRoot() {
        return fixedCampaign == null
            ? CampaignRestorePoints.deviceRoot(owner.uiSession.getStorage().getCustomCampaignRoot().resolve("campaign"))
            : CampaignRestorePoints.deviceRoot(fixedCampaign);
    }

    private void open() {
        com.dnd.ui.WindowOrder.adopt(stage);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Restore points");
        Button refresh = owner.btn("Refresh", this::refresh);
        Button capture = owner.btn("Create restore point", this::capture);
        Button unlock = owner.btn("Unlock private files", () -> PrivateFilesUi.unlocked(owner, this::preview));
        Button restore = owner.dangerBtn("Restore selected files", this::restore);
        restore.disableProperty().bind(files.getSelectionModel().selectedItemProperty().isNull());
        points.setMaxWidth(Double.MAX_VALUE);
        points.setOnAction(e -> { if (!changingPoint) loadPoint(); });
        files.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        files.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(CampaignArchive.FileEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null : UiTranslator.text("{A} ({B} bytes)")
                    .replace("{A}", entry.path()).replace("{B}", Long.toString(entry.size())));
            }
        });
        files.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> preview());
        files.setPrefWidth(340);
        saved.setEditable(false);
        current.setEditable(false);
        saved.setWrapText(true);
        current.setWrapText(true);
        saved.setPromptText("Select a file to preview its saved text.");
        current.setPromptText("Current file text, when present.");
        TabPane comparison = new TabPane(new Tab("Restore-point text", saved), new Tab("Current file text", current));
        comparison.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        HBox center = new HBox(10, files, comparison);
        HBox.setHgrow(comparison, Priority.ALWAYS);
        VBox.setVgrow(center, Priority.ALWAYS);
        Label explanation = new Label("The latest 20 restore points are kept per campaign on this device."
            + " Select files to compare and restore. Other current files are kept."
            + " Storyline files also restore their encounter rosters."
            + " Private-file access is checked again; current grants are never replaced with old grants.");
        explanation.setWrapText(true);
        status.setWrapText(true);
        layout.setPadding(new Insets(16));
        layout.getStyleClass().add("root");
        layout.getChildren().addAll(explanation, new FlowPane(10, 8, refresh, capture, unlock, restore),
            points, center, status);
        stage.setScene(owner.themedScene(layout, 1100, 740));
        stage.setOnHidden(e -> previewGeneration++);
        stage.setOnCloseRequest(e -> { if (layout.isDisabled()) e.consume(); });
        stage.show();
        refresh();
    }

    private boolean authorized(Path folder, CampaignRestorePoints.Point point) {
        var user = owner.uiSession.getCurrentUser();
        if (user == null) return false;
        String identity = point == null ? CampaignMeta.idOf(folder).orElseThrow() : point.campaignId();
        var directory = CampaignDirectory.shared();
        if (directory.isRegistered(identity)) {
            return directory.findForMember(identity, user.getUsername()).filter(record -> record.isDm(user.getUsername())).isPresent();
        }
        return point != null && user.getProviderUid() != null && user.getProviderUid().equals(point.creatorUid());
    }

    private void refresh() {
        run(() -> {
            List<Target> found = new ArrayList<>();
            if (fixedCampaign != null) {
                var service = CampaignRestorePoints.forCampaign(fixedCampaign);
                for (var point : service.list()) {
                    if (authorized(fixedCampaign, point)) found.add(new Target(point, fixedCampaign));
                }
            } else {
                Path allowed = owner.uiSession.getStorage().getCustomCampaignRoot().toAbsolutePath().normalize();
                Map<String, Path> existing = new HashMap<>();
                for (String name : owner.uiSession.getStorage().listCustomCampaigns()) {
                    Path folder = owner.uiSession.getStorage().resolveCustomCampaignPath(name).toAbsolutePath().normalize();
                    var identity = CampaignMeta.idOf(folder);
                    if (identity.isPresent() && existing.putIfAbsent(identity.get(), folder) != null) {
                        throw new IOException("More than one local folder has this campaign ID. Resolve the duplicate before restoring.");
                    }
                }
                for (var point : CampaignRestorePoints.discover(deviceRoot())) {
                    Path destination = existing.getOrDefault(point.campaignId(), Path.of(point.campaignPath()).toAbsolutePath().normalize());
                    if (!allowed.equals(destination.getParent())) continue;
                    if (authorized(destination, point)) found.add(new Target(point, destination));
                }
            }
            return found;
        }, found -> {
            changingPoint = true;
            points.getItems().setAll(found);
            points.getSelectionModel().selectFirst();
            changingPoint = false;
            status.setText(found.isEmpty() ? "No accessible restore points on this device." : "");
            loadPoint();
        });
    }

    private void loadPoint() {
        previewGeneration++;
        files.getItems().clear();
        saved.clear();
        current.clear();
        plan = null;
        Target target = points.getValue();
        if (target == null) return;
        run(() -> {
            if (!authorized(target.folder(), target.point())) throw new SecurityException("Only this campaign's Game Masters may restore its files.");
            var service = CampaignRestorePoints.forPoint(deviceRoot(), target.folder(), target.point());
            return Map.entry(service, service.plan(target.point().id()));
        }, loaded -> {
            store = loaded.getKey();
            plan = loaded.getValue();
            files.getItems().setAll(plan.files());
        });
    }

    private void preview() {
        long generation = ++previewGeneration;
        saved.clear();
        current.clear();
        var entry = files.getSelectionModel().getSelectedItem();
        var selectedPlan = plan;
        var selectedStore = store;
        if (entry == null || selectedPlan == null) return;
        var account = owner.uiSession.getPrivateAccount();
        owner.background(() -> {
            try {
                return new Compared(selectedStore.preview(selectedPlan, entry.path(), account),
                    selectedStore.currentPreview(selectedPlan, entry.path(), account));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, text -> {
            if (generation != previewGeneration || !stage.isShowing()) return;
            saved.setText(text.saved());
            current.setText(text.current());
            status.setText("");
        }, error -> {
            if (generation != previewGeneration || !stage.isShowing()) return;
            status.setText(BaseScene.friendlyError(error));
        });
    }

    private void capture() {
        Target selected = points.getValue();
        Path root = fixedCampaign != null ? fixedCampaign : selected == null ? null : selected.folder();
        if (root == null || !Files.isDirectory(root)) {
            owner.showInfo("Restore points", "Choose an existing campaign before creating a restore point.");
            return;
        }
        run(() -> {
            if (!authorized(root, selected == null ? null : selected.point())) {
                throw new SecurityException("Only this campaign's Game Masters may restore its files.");
            }
            return CampaignRestorePoints.forCampaign(root).capture("Manual restore point",
                owner.uiSession.getCurrentUser().getProviderUid());
        }, point -> refresh());
    }

    private void restore() {
        var selected = files.getSelectionModel().getSelectedItems().stream().map(CampaignArchive.FileEntry::path).toList();
        var selectedPlan = plan;
        var selectedStore = store;
        var target = points.getValue();
        if (selected.isEmpty() || selectedPlan == null || target == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Restore the selected files? Existing campaigns get a safety restore point first. Other current files are kept."
                + " Current private-file grants are preserved. A missing private file can only be restored by its owner,"
                + " and starts with owner-only access. Online memberships and Git history are not restored.",
            ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        owner.styleDialog(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        run(() -> {
            if (!authorized(target.folder(), target.point())) throw new SecurityException("Only this campaign's Game Masters may restore its files.");
            return selectedStore.restore(selectedPlan, selected, owner.uiSession.getPrivateAccount());
        }, safety -> {
            stage.close();
            reloadOwner.run();
            owner.showInfo("Files restored", "Selected local files were restored. Online membership was not changed."
                + " Linked campaigns will share these changes through their normal GitHub sync.");
        });
    }

    private <T> void run(Callable<T> work, Consumer<T> done) {
        layout.setDisable(true);
        CampaignSyncService.shared().localFiles(work).whenComplete((result, error) -> Platform.runLater(() -> {
            if (!stage.isShowing()) return;
            layout.setDisable(false);
            if (error == null) done.accept(result);
            else owner.showError("Could not manage restore points", error);
        }));
    }
}
