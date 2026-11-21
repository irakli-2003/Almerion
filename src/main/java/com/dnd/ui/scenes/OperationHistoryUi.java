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

public final class OperationHistoryUi {
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
                String correction = action.undoOf() == null ? ""
                    : UiTranslator.text(Boolean.TRUE.equals(action.redo()) ? "Redo correction" : "Undo correction");
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
        Label explanation = owner.body("Action history records saved character stats, inventory and encounter changes on this device."
            + "\nSelect an action to compare Before action and After action, then choose Undo selected action."
            + " Only the listed fields are restored. If those fields changed later, the action is blocked."
            + "\nThe bottom-left arrows undo the latest available action and redo the last undo."
            + " A new saved action clears redo. The latest 500 records are kept."
            + "\nNotes, chat, passwords, map terrain and unsaved text are not included."
            + " Encrypted encounters require current private-file access."
            + " Resolve interrupted actions only clears incomplete history records; it does not restore files.");
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
            var available = OperationHistory.cursor(list);
            var active = available.undo().stream().map(OperationHistory.Action::id).collect(Collectors.toSet());
            undone = list.stream().map(OperationHistory.Action::id).filter(id -> !active.contains(id)).collect(Collectors.toSet());
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

    public static HBox footer(BaseScene owner, javafx.scene.Scene scene) {
        Button undo = arrow(false);
        Button redo = arrow(true);
        HBox controls = new HBox(6, undo, redo);
        controls.setId("operation-history-controls");
        Path campaign = owner.uiSession == null ? null : owner.uiSession.campaignRoot();
        java.util.function.BooleanSupplier campaignAvailable = () -> campaign != null
            && owner.isCurrentRole() && owner.uiSession.isDm()
            && campaign.equals(owner.uiSession.campaignRoot())
            && owner.uiSession.getRouter() != null && owner.uiSession.getRouter().isCampaignOpen()
            && scene.getWindow() != null && scene.getWindow().isShowing()
            && !(scene.getWindow() instanceof Stage s && s.getModality() != Modality.NONE);
        Button sync = owner.btn("GitHub Sync", () -> {
            if (!campaignAvailable.getAsBoolean() || owner.uiSession.hasBackgroundActions()) return;
            GitHubSyncUi.configureCampaign(owner, campaign.getFileName().toString(), campaign, () -> { });
        });
        sync.setId("campaign-github-sync");
        sync.setTooltip(new Tooltip("Sync saved campaign changes with GitHub"));
        sync.setVisible(false);
        sync.managedProperty().bind(sync.visibleProperty());
        controls.getChildren().add(sync);
        java.util.concurrent.atomic.AtomicBoolean reading = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable refresh = () -> {
            undo.setDisable(true);
            redo.setDisable(true);
            boolean available = campaignAvailable.getAsBoolean();
            sync.setVisible(available);
            boolean busy = available && owner.uiSession.hasBackgroundActions();
            sync.setDisable(!available || busy);
            if (!available || busy || !reading.compareAndSet(false, true)) return;
            CampaignSyncService.shared().localFiles(() -> new OperationHistory(campaign).cursor())
                .whenComplete((cursor, error) -> Platform.runLater(() -> {
                    reading.set(false);
                    if (scene.getWindow() == null || !scene.getWindow().isShowing()
                        || !owner.isCurrentRole() || !campaign.equals(owner.uiSession.campaignRoot())
                        || !owner.uiSession.getRouter().isCampaignOpen() || owner.uiSession.hasBackgroundActions()) return;
                    undo.setDisable(error != null || cursor.nextUndo() == null);
                    redo.setDisable(error != null || cursor.nextRedo() == null);
                    if (error != null) {
                        undo.setTooltip(new Tooltip("Action history is unavailable. Open Action history to review the problem."));
                    }
                }));
        };
        AutoCloseable[] listener = {null};
        javafx.beans.value.ChangeListener<Number> busyListener = (obs, previous, count) -> refresh.run();
        java.util.function.Supplier<AutoCloseable> watch = () -> {
            if (owner.uiSession != null) owner.uiSession.backgroundActionsProperty().addListener(busyListener);
            AutoCloseable historyListener = OperationHistory.listen(changed -> {
                if (campaign != null && campaign.toAbsolutePath().normalize().equals(changed)) Platform.runLater(refresh);
            });
            return () -> {
                historyListener.close();
                if (owner.uiSession != null) owner.uiSession.backgroundActionsProperty().removeListener(busyListener);
            };
        };
        Runnable detach = () -> {
            if (listener[0] == null) return;
            try { listener[0].close(); }
            catch (Exception e) { throw new IllegalStateException("Could not detach history controls.", e); }
            listener[0] = null;
        };
        javafx.beans.value.ChangeListener<Boolean> showingListener = (obs, previous, showing) -> {
            if (showing) {
                detach.run();
                listener[0] = watch.get();
                refresh.run();
            } else detach.run();
        };
        javafx.beans.value.ChangeListener<Boolean> focusListener = (obs, previous, focused) -> {
            if (focused) refresh.run();
        };
        java.util.function.Consumer<javafx.stage.Window> hook = window -> {
            if (window == null) return;
            window.showingProperty().addListener(showingListener);
            window.focusedProperty().addListener(focusListener);
            if (window.isShowing()) showingListener.changed(window.showingProperty(), false, true);
        };
        scene.windowProperty().addListener((obs, previous, window) -> {
            if (previous != null) {
                previous.showingProperty().removeListener(showingListener);
                previous.focusedProperty().removeListener(focusListener);
                detach.run();
            }
            hook.accept(window);
        });
        hook.accept(scene.getWindow());
        undo.setOnAction(event -> quick(owner, campaign, false));
        redo.setOnAction(event -> quick(owner, campaign, true));
        return controls;
    }

    private static Button arrow(boolean redo) {
        Button button = new Button();
        javafx.scene.shape.SVGPath glyph = new javafx.scene.shape.SVGPath();
        glyph.setContent(redo ? "M 3 18 C 3 8 12 5 19 10 M 19 10 L 13 10 M 19 10 L 19 4"
            : "M 19 18 C 19 8 10 5 3 10 M 3 10 L 9 10 M 3 10 L 3 4");
        glyph.setFill(null);
        glyph.setStroke(javafx.scene.paint.Color.WHITE);
        glyph.setStyle("-fx-stroke: #c9a84c;");
        glyph.setStrokeWidth(2);
        button.setGraphic(glyph);
        button.setId(redo ? "redo-action" : "undo-action");
        button.setAccessibleText(redo ? "Redo saved action" : "Undo saved action");
        button.setTooltip(new Tooltip(redo ? "Redo the last undone saved action" : "Undo the latest saved action"));
        button.getStyleClass().add("dnd-button");
        button.setMinSize(32, 28);
        button.setDisable(true);
        return button;
    }

    private static void quick(BaseScene owner, Path campaign, boolean redo) {
        if (campaign == null || !owner.isCurrentRole() || !owner.uiSession.isDm()
            || !campaign.equals(owner.uiSession.campaignRoot()) || !owner.uiSession.getRouter().isCampaignOpen()) return;
        if (owner.uiSession.hasBackgroundActions()) {
            owner.showInfo("Action history", "Wait for pending actions before using undo or redo.");
            return;
        }
        if (!com.dnd.ui.ReportUi.prepareHistoryChange(owner.uiSession.primaryStage().getScene())) return;
        if (!PlayerModeUi.closeSecondaryWindows(owner.uiSession.primaryStage())
            || owner.uiSession.hasBackgroundActions()) {
            owner.showInfo("Action history", "Finish or cancel the open editor before using undo or redo.");
            return;
        }
        var account = owner.uiSession.getPrivateAccount();
        owner.uiSession.beginBackgroundAction();
        CampaignSyncService.shared().localFiles(() -> {
            if (!owner.isCurrentRole() || !owner.uiSession.isDm()
                || !campaign.equals(owner.uiSession.campaignRoot())) throw new SecurityException("The GM context changed.");
            String id = CampaignMeta.idOf(campaign).orElseThrow();
            var user = owner.uiSession.getCurrentUser();
            if (user == null || CampaignDirectory.shared().findForMember(id, user.getUsername())
                .filter(record -> record.isDm(user.getUsername())).isEmpty()) {
                throw new SecurityException("Only this campaign's Game Masters may change action history.");
            }
            var history = new OperationHistory(campaign);
            if (redo) history.redoLatest(account); else history.undoLatest(account);
            return true;
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            owner.uiSession.endBackgroundAction();
            if (owner.uiSession.getRouter() != null) owner.uiSession.getRouter().reload();
            if (error != null) owner.showError(redo ? "Could not redo action" : "Could not undo action", error);
        }));
    }
}
