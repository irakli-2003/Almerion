package com.dnd.ui.scenes;

import com.dnd.chat.SessionChat;
import com.dnd.model.character.UpgradeRequests;
import com.dnd.model.character.UpgradeRequests.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.util.*;
import java.util.function.Consumer;

/** The same constrained proposal wizard for players and the GM's editable review. */
public final class UpgradeRequestsUi {
    private static final Map<com.dnd.ui.UiSession, Stage> WINDOWS = new WeakHashMap<>();
    private final BaseScene owner;
    private final Stage stage = new Stage();
    private final VBox body = new VBox(10);
    private final Label error = new Label();
    private final Button retry = new Button("Retry pending action");
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.nio.file.Path campaign;
    private final com.dnd.auth.User user;
    private SessionChat.Connection connection;
    private SessionChat.Command pending;
    private Consumer<SessionChat.View> pendingDone;
    private boolean busy;
    private boolean closed;

    private UpgradeRequestsUi(BaseScene owner) {
        this.owner = owner;
        campaign = owner.uiSession.campaignRoot();
        user = owner.uiSession.getCurrentUser();
    }

    public static Button button(BaseScene owner) {
        Button button = owner.btn("Level-up requests", () -> show(owner));
        var root = owner.uiSession.campaignRoot();
        var user = owner.uiSession.getCurrentUser();
        Timeline timer = new Timeline();
        boolean[] busy = {false};
        timer.getKeyFrames().add(new KeyFrame(Duration.seconds(3), e -> {
            if (!owner.isCurrentRole() || owner.uiSession.getCurrentUser() != user || !Objects.equals(owner.uiSession.campaignRoot(), root)
                    || button.getScene() == null || button.getScene().getWindow() == null
                    || !button.getScene().getWindow().isShowing()) { timer.stop(); return; }
            if (busy[0]) return;
            busy[0] = true;
            owner.background(() -> UpgradeRequests.at(root).inbox(SessionChat.HOST),
                inbox -> {
                    busy[0] = false;
                    long count = inbox.requests().stream().filter(r -> r.status().equals("pending")).count();
                    button.setText(com.dnd.ui.i18n.UiTranslator.text("Level-up requests") + (count == 0 ? "" : " (" + count + ")"));
                    button.setStyle(count == 0 ? "" : "-fx-border-color: #e7b54a; -fx-text-fill: #e7b54a;");
                }, ex -> { busy[0] = false; button.setText("Upgrade inbox unavailable"); button.setTooltip(new Tooltip(owner.friendlyError(ex))); });
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        button.sceneProperty().addListener((o, before, scene) -> {
            if (before != null && scene == null) javafx.application.Platform.runLater(() -> {
                if (button.getScene() == null) timer.stop();
            });
        });
        timer.play();
        return button;
    }

    public static void show(BaseScene owner) {
        if (!owner.isCurrentRole()) return;
        Stage existing = WINDOWS.get(owner.uiSession);
        if (existing != null && existing.isShowing()) { existing.toFront(); return; }
        UpgradeRequestsUi window = new UpgradeRequestsUi(owner);
        if (owner.uiSession.isDm()) {
            window.connection = command -> new SessionChat.View(SessionChat.HOST, List.of(), List.of(), null, List.of(),
                UpgradeRequests.at(window.campaign).execute(SessionChat.HOST, command));
        } else {
            var sync = owner.uiSession.getSession().getFirebaseSync();
            String character = owner.uiSession.getSession().getActivePlayerCharacterId();
            if (sync == null || sync.isHost() || character == null) {
                owner.showInfo("Level up", "Join a hosted session and choose your character before requesting a level up.");
                return;
            }
            var pc = new com.dnd.data.CampaignRepositories(window.campaign).players().getById(character);
            String password = null;
            if (pc == null || !com.dnd.cloud.Cloud.hasOnlineAccount() || owner.uiSession.getCurrentUser() == null
                    || !pc.isOwnedBy(owner.uiSession.getCurrentUser().getUsername())) {
                Dialog<String> dialog = new Dialog<>();
                dialog.setTitle("Character password");
                PasswordField field = new PasswordField();
                dialog.getDialogPane().setContent(field);
                dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                dialog.setResultConverter(b -> b == ButtonType.OK ? field.getText() : null);
                owner.styleDialog(dialog);
                var answer = dialog.showAndWait();
                if (answer.isEmpty()) return;
                password = answer.get();
            }
            String secret = password;
            window.open();
            owner.background(() -> sync.chatClient(character, secret), client -> {
                if (window.closed) { client.close(); return; }
                window.connection = client;
                window.refresh();
            }, ex -> window.error.setText(owner.friendlyError(ex)));
            return;
        }
        window.open();
        window.refresh();
    }

    private void open() {
        com.dnd.ui.WindowOrder.adopt(stage);
        stage.setTitle(owner.uiSession.isDm() ? "Level-up requests" : "Request level up");
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #e07070;");
        retry.setVisible(false);
        retry.setManaged(false);
        retry.setOnAction(e -> execute(pending, pendingDone));
        Button check = owner.btn("Reload after connection error", () -> {
            if (busy) return;
            pending = null; pendingDone = null;
            retry.setVisible(false); retry.setManaged(false);
            refresh();
        });
        check.visibleProperty().bind(retry.visibleProperty());
        check.managedProperty().bind(check.visibleProperty());
        retry.getStyleClass().add("dnd-button");
        retry.setTooltip(new Tooltip("Resend the same request without applying it twice."));
        check.setTooltip(new Tooltip("Check whether the last action succeeded without resending it."));
        VBox root = new VBox(12, body, error, retry, check);
        root.setPadding(new Insets(18));
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        stage.setScene(owner.themedScene(scroll, 760, 740));
        WINDOWS.put(owner.uiSession, stage);
        stage.setOnHidden(e -> {
            closed = true;
            WINDOWS.remove(owner.uiSession, stage);
            if (connection != null) connection.close();
        });
        javafx.beans.value.ChangeListener<javafx.scene.Scene> changed = (o, before, scene) -> valid();
        owner.uiSession.primaryStage().sceneProperty().addListener(changed);
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN,
            e -> owner.uiSession.primaryStage().sceneProperty().removeListener(changed));
        stage.show();
    }

    private boolean valid() {
        if (!closed && owner.isCurrentRole() && user == owner.uiSession.getCurrentUser()
                && Objects.equals(campaign, owner.uiSession.campaignRoot())) return true;
        stage.close();
        return false;
    }

    private void refresh() {
        execute(SessionChat.Command.poll(null), view -> {
            body.getChildren().clear();
            if (view.upgrades() == null) { error.setText("The hosting GM needs to update Almerion to support upgrade requests."); return; }
            if (!owner.uiSession.isDm()) {
                body.getChildren().add(owner.btn("Level up", () -> loadWizard(null, null)));
                body.getChildren().add(label("Choose ability improvements, then a class-tree upgrade. Nothing changes until the GM accepts."));
            }
            for (Request request : view.upgrades().requests().reversed()) {
                body.getChildren().add(label(request.name() + " - " + request.status()));
                TextArea details = new TextArea(request.details());
                details.setEditable(false); details.setWrapText(true); details.setPrefRowCount(6);
                body.getChildren().add(details);
                if (owner.uiSession.isDm() && request.status().equals("pending")) {
                    body.getChildren().add(new HBox(10,
                        owner.btn("Accept request", () -> execute(command("upgrade-accept", request.id(), request.submitted()),
                            ignored -> refresh())),
                        owner.btn("Review & edit", () -> loadWizard(request, request.submitted())),
                        owner.btn("Decline", () -> execute(command("upgrade-decline", request.id(), null), ignored -> refresh()))));
                }
            }
            body.getChildren().add(owner.btn("Refresh requests", this::refresh));
        });
    }

    private void loadWizard(Request request, Proposal initial) {
        execute(command("upgrade-options", request == null ? null : request.character(), initial), view -> {
            Options options = view.upgrades().options();
            body.getChildren().clear();
            body.getChildren().add(label("1. Ability improvements"));
            body.getChildren().add(label(options.summary()));
            Map<String, Spinner<Integer>> inputs = new LinkedHashMap<>();
            for (Ability ability : options.abilities()) {
                Spinner<Integer> spinner = new Spinner<>(0, Math.min(options.budget(), Math.max(0, 20 - ability.score())),
                    initial == null ? 0 : initial.points().getOrDefault(ability.id(), 0));
                spinner.setPrefWidth(85);
                spinner.setEditable(false);
                inputs.put(ability.id(), spinner);
                body.getChildren().add(new HBox(12, label(ability.name() + " (" + ability.score() + ") +"), spinner));
            }
            Button next = owner.btn("Next: class upgrade", () -> {
                Map<String, Integer> points = new LinkedHashMap<>();
                inputs.forEach((id, spinner) -> { if (spinner.getValue() > 0) points.put(id, spinner.getValue()); });
                if (points.values().stream().mapToInt(Integer::intValue).sum() != options.budget()) {
                    error.setText("Allocate exactly " + options.budget() + " points."); return;
                }
                Proposal draft = new Proposal(options.revision(), points, null, Map.of());
                execute(command("upgrade-options", request == null ? null : request.character(), draft),
                    result -> chooseNode(request, initial, draft, result.upgrades().options()));
            });
            body.getChildren().addAll(next, owner.btn("Back to requests", this::refresh));
        });
    }

    private void chooseNode(Request request, Proposal initial, Proposal draft, Options options) {
        body.getChildren().clear();
        body.getChildren().add(label("2. Class upgrade tree"));
        body.getChildren().add(label(options.summary()));
        ComboBox<Choice> nodes = new ComboBox<>();
        nodes.setMinWidth(0);
        nodes.getItems().setAll(options.choices().stream().filter(c -> c.blocked().isBlank()).toList());
        nodes.setMaxWidth(Double.MAX_VALUE);
        TextArea description = new TextArea();
        description.setEditable(false); description.setWrapText(true); description.setPrefRowCount(9);
        VBox spells = new VBox(8);
        Map<String, ComboBox<SpellOption>> selections = new LinkedHashMap<>();
        nodes.valueProperty().addListener((o, before, choice) -> {
            selections.clear(); spells.getChildren().clear();
            description.setText(choice == null ? "" : choice.description());
            if (choice == null) return;
            for (SpellChoice spell : choice.spells()) {
                ComboBox<SpellOption> select = new ComboBox<>();
                select.getItems().setAll(spell.options());
                select.setConverter(new javafx.util.StringConverter<>() {
                    @Override public String toString(SpellOption value) { return value == null ? "" : value.name(); }
                    @Override public SpellOption fromString(String value) { throw new UnsupportedOperationException(); }
                });
                Label detail = label("");
                select.valueProperty().addListener((p, a, b) -> detail.setText(b == null ? "" : b.description()));
                selections.put(spell.primary(), select);
                spells.getChildren().addAll(label("Already known: " + spell.name() + ". Choose an alternative, or acknowledge the existing spell if none remain."), select, detail);
                if (initial != null && initial.spells() != null && Objects.equals(initial.node(), choice.id()))
                    select.getItems().stream().filter(s -> s.id().equals(initial.spells().get(spell.primary()))).findFirst().ifPresent(select::setValue);
            }
        });
        if (initial != null) nodes.getItems().stream().filter(n -> n.id().equals(initial.node())).findFirst().ifPresent(nodes::setValue);
        Button send = owner.btn(request == null ? "Send to GM" : "Accept edited upgrade", () -> {
            Choice choice = nodes.getValue();
            if (choice == null || selections.values().stream().anyMatch(s -> s.getValue() == null)) {
                error.setText("Choose one class upgrade and every required spell alternative."); return;
            }
            Map<String, String> chosen = new LinkedHashMap<>();
            selections.forEach((id, select) -> chosen.put(id, select.getValue().id()));
            Proposal proposal = new Proposal(draft.revision(), draft.points(), choice.id(), chosen);
            execute(command(request == null ? "upgrade-submit" : "upgrade-accept", request == null ? null : request.id(), proposal), ignored -> refresh());
        });
        send.setDisable(nodes.getItems().isEmpty());
        if (nodes.getItems().isEmpty()) body.getChildren().add(label("No class upgrades are available. Ask the GM to review prerequisites or configure the class tree."));
        body.getChildren().addAll(nodes, description, spells, send,
            owner.btn("Back: ability improvements", () -> loadWizard(request, new Proposal(draft.revision(), draft.points(),
                nodes.getValue() == null ? null : nodes.getValue().id(), Map.of()))),
            owner.btn("Back to requests", this::refresh));
    }

    private SessionChat.Command command(String operation, String target, Proposal proposal) {
        try {
            return new SessionChat.Command(UUID.randomUUID().toString(), operation, target, List.of(), "",
                proposal == null ? "" : mapper.writeValueAsString(proposal));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private void execute(SessionChat.Command command, Consumer<SessionChat.View> done) {
        if (busy || command == null || connection == null || !valid()) return;
        busy = true;
        boolean mutation = Set.of("upgrade-submit", "upgrade-accept", "upgrade-decline").contains(command.operation());
        if (mutation) { pending = command; pendingDone = done; }
        body.setDisable(true); retry.setDisable(true); error.setText("");
        var sync = owner.uiSession.getSession().getFirebaseSync();
        String campaignName = owner.uiSession.getSession().getCampaignContext().getName();
        owner.background(() -> {
            SessionChat.View view = connection.execute(command);
            if (command.operation().equals("upgrade-accept")) {
                if (sync != null && sync.isHost()) sync.pushCampaign(campaign, campaignName);
            }
            return view;
        }, view -> {
            busy = false;
            if (!valid()) return;
            pending = null; pendingDone = null;
            retry.setVisible(false); retry.setManaged(false);
            body.setDisable(false);
            done.accept(view);
        }, ex -> {
            busy = false;
            if (!valid()) return;
            error.setText(owner.friendlyError(ex));
            body.setDisable(pending != null);
            retry.setDisable(false); retry.setVisible(pending != null); retry.setManaged(pending != null);
        });
    }

    private static Label label(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }
}
