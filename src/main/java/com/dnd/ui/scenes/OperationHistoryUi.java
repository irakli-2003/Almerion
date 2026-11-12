package com.dnd.ui.scenes;

import com.dnd.cloud.CampaignDirectory;
import com.dnd.cloud.CampaignMeta;
import com.dnd.data.OperationHistory;
import com.dnd.sync.CampaignSyncService;
import com.dnd.ui.i18n.UiTranslator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;

final class OperationHistoryUi {
    private final BaseScene owner;
    private final Path campaign;
    private final Stage stage = new Stage();
    private final VBox root = new VBox(10);
    private final ListView<OperationHistory.Action> actions = UiTranslator.skip(new ListView<>());
    private final TextArea before = new TextArea();
    private final TextArea after = new TextArea();
    private final Label status = new Label();
    private final Button undo;
    private OperationHistory history;
    private Set<String> undone = Set.of();
    private long generation;

    static void show(BaseScene owner) {
        if (!owner.isCurrentRole() || !owner.uiSession.isDm()) return;
        if (owner.uiSession.hasBackgroundActions()) {
            owner.showInfo("Action history", "Wait for pending actions before opening action history.");
            return;
        }
        if (!PlayerModeUi.closeSecondaryWindows(owner.uiSession.primaryStage())) {
            owner.showInfo("Action history", "Close the remaining editor or map before using action history.");
            return;
        }
        if (owner.uiSession.hasBackgroundActions()) {
            owner.showInfo("Action history", "Wait for pending actions before opening action history.");
            return;
        }
        new OperationHistoryUi(owner).open();
    }

    private OperationHistoryUi(BaseScene owner) {
        this.owner = owner;
        campaign = owner.uiSession.campaignRoot();
        undo = owner.dangerBtn("Undo selected action", this::undo);
    }

    private void authorize() {
        if (!owner.isCurrentRole() || !owner.uiSession.isDm() || campaign == null) throw new SecurityException("GM mode is required.");
        String id = CampaignMeta.idOf(campaign).orElseThrow();
        String user = owner.uiSession.getCurrentUser().getUsername();
        if (CampaignDirectory.shared().findForMember(id, user).filter(r -> r.isDm(user)).isEmpty()) {
            throw new SecurityException("Only this campaign's Game Masters may use action history.");
        }
    }

    private void open() {
        com.dnd.ui.WindowOrder.adopt(stage);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Action history");
        before.setEditable(false);
        after.setEditable(false);
        before.setWrapText(true);
        after.setWrapText(true);
        actions.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(OperationHistory.Action action, boolean empty) {
                super.updateItem(action, empty);
                if (empty || action == null) { setText(null); return; }
                String kind = switch (action.kind()) {
                    case PRIVATE_ROSTER -> UiTranslator.text("Private encounter action");
                    case ROSTER -> UiTranslator.text("Encounter action");
                    case MAPS -> UiTranslator.text("Battle action");
                    case PLAYERS -> UiTranslator.text("Character action");
                };
                String state = undone.contains(action.id()) ? UiTranslator.text("Undone") : "";
                String correction = action.undoOf() == null ? "" : UiTranslator.text("Undo correction");
                setText(java.time.Instant.ofEpochMilli(action.at()) + " - " + kind
                    + (state.isEmpty() ? "" : " - " + state) + (correction.isEmpty() ? "" : " - " + correction));
            }
        });
        actions.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> preview());
        actions.setPrefWidth(390);
        TabPane comparison = new TabPane(new Tab("Before action", before), new Tab("After action", after));
        comparison.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        HBox center = new HBox(10, actions, comparison);
        HBox.setHgrow(comparison, Priority.ALWAYS);
        VBox.setVgrow(center, Priority.ALWAYS);
        Label explanation = owner.body("The latest 500 saved mechanical actions are kept on this device."
            + " Undo only changes the recorded fields; later edits to those values block undo."
            + " This is not Rabbit's rewind. Notes, passwords and chat are not recorded."
            + " Private encounters still require current file access.");
        explanation.setWrapText(true);
        undo.setDisable(true);
        root.getChildren().addAll(explanation, new FlowPane(10, 8, owner.btn("Refresh", this::refresh),
            owner.btn("Unlock private files", () -> PrivateFilesUi.unlocked(owner, this::preview)),
            owner.btn("Resolve interrupted actions", this::recover), undo), center, status);
        root.setPadding(new Insets(16));
        stage.setScene(owner.themedScene(root, 1100, 720));
        stage.setOnCloseRequest(e -> { if (root.isDisabled()) e.consume(); });
        stage.setOnHidden(e -> generation++);
        stage.show();
        refresh();
    }

    private void refresh() {
        run(() -> {
            authorize();
            history = new OperationHistory(campaign);
            return history.list();
        }, list -> {
            undone = list.stream().map(OperationHistory.Action::undoOf).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
            actions.getItems().setAll(list);
            actions.getSelectionModel().selectFirst();
            status.setText(list.isEmpty() ? "No recorded actions on this device yet." : "");
        });
    }

    private void preview() {
        long request = ++generation;
        before.clear();
        after.clear();
        undo.setDisable(true);
        OperationHistory.Action action = actions.getSelectionModel().getSelectedItem();
        if (action == null) return;
        var account = owner.uiSession.getPrivateAccount();
        run(() -> {
            authorize();
            return history.changes(action, account);
        }, changes -> {
            if (request != generation) return;
            before.setText(format(changes, true));
            after.setText(format(changes, false));
            undo.setDisable(undone.contains(action.id()));
            status.setText("");
        });
    }

    private void recover() {
        run(() -> {
            authorize();
            return new OperationHistory(campaign).interrupted();
        }, interrupted -> {
            if (interrupted.isEmpty()) { refresh(); return; }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Keep current campaign files and discard incomplete action records?"
                    + " No campaign data is changed. These uncertain actions will no longer be available to undo.",
                ButtonType.OK, ButtonType.CANCEL);
            owner.styleDialog(confirm);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            run(() -> {
                authorize();
                new OperationHistory(campaign).discardInterrupted(interrupted);
                return true;
            }, ignored -> refresh());
        });
    }

    private static String format(List<OperationHistory.Delta> changes, boolean before) {
        StringBuilder text = new StringBuilder();
        for (var delta : changes) {
            text.append(delta.map() == null ? "" : delta.map() + "/").append(delta.subject()).append(" - ").append(delta.field()).append('\n');
            boolean present = before ? delta.beforePresent() : delta.afterPresent();
            var value = before ? delta.before() : delta.after();
            text.append(present ? value == null ? "null" : value.toPrettyString()
                : UiTranslator.text("Deleted / not present")).append("\n\n");
        }
        return text.toString();
    }

    private void undo() {
        var action = actions.getSelectionModel().getSelectedItem();
        if (action == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Undo this saved action? Only its recorded mechanical fields are restored."
                + " All those fields must still match the action's result. The correction is recorded too.",
            ButtonType.OK, ButtonType.CANCEL);
        owner.styleDialog(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        var account = owner.uiSession.getPrivateAccount();
        run(() -> {
            authorize();
            history.undo(action, account);
            return true;
        }, ignored -> {
            stage.close();
            owner.uiSession.getRouter().goTo(com.dnd.ui.SceneType.DM_MENU);
            owner.showInfo("Action undone", "The recorded values were restored. Other current fields were kept.");
        });
    }

    private <T> void run(Callable<T> work, Consumer<T> done) {
        root.setDisable(true);
        owner.uiSession.beginBackgroundAction();
        CampaignSyncService.shared().localFiles(work).whenComplete((result, error) -> Platform.runLater(() -> {
            owner.uiSession.endBackgroundAction();
            if (!stage.isShowing() || !owner.isCurrentRole()) {
                if (error != null) java.util.logging.Logger.getLogger(OperationHistoryUi.class.getName())
                    .log(java.util.logging.Level.WARNING, "Action history failed after its window closed", error);
                return;
            }
            root.setDisable(false);
            if (error == null) done.accept(result);
            else {
                undo.setDisable(true);
                owner.showError("Could not manage action history", error);
            }
        }));
    }
}
