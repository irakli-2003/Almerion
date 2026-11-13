package com.dnd.ui.scenes;

import com.dnd.chat.SessionChat;
import com.dnd.ui.UiSession;
import com.dnd.ui.i18n.UiTranslator;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.util.*;
import java.util.function.Supplier;

/** One chat connection/window per desktop session, with explicit retry for uncertain delivery. */
public final class SessionChatUi {
    private static final Map<UiSession, Stage> WINDOWS = new WeakHashMap<>();
    private static final Map<UiSession, java.util.concurrent.atomic.AtomicReference<SessionChat.Connection>> BADGES = new WeakHashMap<>();
    private final BaseScene owner;
    private final java.nio.file.Path campaign;
    private final com.dnd.auth.User user;
    private final boolean gm;
    private final Stage stage = new Stage();
    private final ListView<SessionChat.Conversation> conversations = UiTranslator.skip(new ListView<>());
    private final TextArea messages = new TextArea();
    private final TextArea compose = new TextArea();
    private final VBox recipients = new VBox(6);
    private final TextField title = new TextField();
    private final Label status = new Label();
    private final Button send;
    private final Button create;
    private final Button retry;
    private final Button usePassword;
    private final Timeline timer;
    private SessionChat.Connection connection;
    private SessionChat.View view;
    private SessionChat.Command pending;
    private String selected;
    private boolean busy;
    private boolean updating;
    private boolean closed;

    public static Button button(BaseScene owner) {
        Button button = owner.btn("Session chat", () -> show(owner));
        var root = owner.uiSession.campaignRoot();
        var user = owner.uiSession.getCurrentUser();
        var client = new java.util.concurrent.atomic.AtomicReference<SessionChat.Connection>();
        BADGES.put(owner.uiSession, client);
        var ended = new java.util.concurrent.atomic.AtomicBoolean();
        boolean[] running = { false };
        Timeline timer = new Timeline();
        Runnable close = () -> {
            ended.set(true);
            timer.stop();
            if (BADGES.get(owner.uiSession) == client) BADGES.remove(owner.uiSession);
            var previous = client.getAndSet(null);
            if (previous != null) previous.close();
        };
        button.sceneProperty().addListener((o, previous, scene) -> {
            if (previous != null && previous != scene) close.run();
            if (scene != null) scene.windowProperty().addListener((p, before, window) -> {
                if (before != null && window == null) close.run();
            });
        });
        timer.getKeyFrames().add(new KeyFrame(Duration.seconds(3), event -> {
            if (!owner.isCurrentRole() || user != owner.uiSession.getCurrentUser()
                || !Objects.equals(root, owner.uiSession.campaignRoot())
                || button.getScene() == null || button.getScene().getWindow() == null
                || !button.getScene().getWindow().isShowing()) { close.run(); return; }
            if (running[0] || WINDOWS.containsKey(owner.uiSession)) return;
            SessionChat hosted = owner.uiSession.isDm() ? SessionChat.hosted(root) : null;
            var sync = owner.uiSession.getSession().getFirebaseSync();
            if (hosted == null && (owner.uiSession.isDm() || sync == null || sync.isHost())) return;
            running[0] = true;
            owner.background(() -> {
                if (ended.get()) return null;
                if (hosted != null) return hosted.execute(SessionChat.HOST, SessionChat.Command.poll(null));
                if (client.get() == null) {
                    String character = owner.uiSession.getSession().getActivePlayerCharacterId();
                    var pc = character == null ? null : new com.dnd.data.CampaignRepositories(root).players().getById(character);
                    if (pc == null || user == null || !com.dnd.cloud.Cloud.hasOnlineAccount() || !pc.isOwnedBy(user.getUsername())) return null;
                    var created = sync.chatClient(character, null);
                    client.set(created);
                    if (ended.get()) {
                        var late = client.getAndSet(null);
                        if (late != null) late.close();
                        return null;
                    }
                }
                var current = client.get();
                return current == null ? null : current.execute(SessionChat.Command.poll(null));
            }, state -> {
                running[0] = false;
                if (ended.get()) return;
                if (button.getScene() == null || button.getScene().getWindow() == null) { close.run(); return; }
                int unread = state == null ? 0 : state.conversations().stream().mapToInt(SessionChat.Conversation::unread).sum();
                button.setText(UiTranslator.text("Session chat") + (unread == 0 ? "" : " (" + unread + ")"));
            }, error -> {
                running[0] = false;
                if (ended.get()) return;
                var previous = client.getAndSet(null);
                if (previous != null) previous.close();
                button.setText(UiTranslator.text("Chat unavailable"));
                button.setTooltip(new Tooltip(owner.friendlyError(error)));
            });
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        return button;
    }

    public static void show(BaseScene owner) {
        if (!owner.isCurrentRole()) return;
        Stage existing = WINDOWS.get(owner.uiSession);
        if (existing != null && existing.isShowing()) { existing.toFront(); return; }
        Supplier<SessionChat.Connection> connect;
        var badge = BADGES.get(owner.uiSession);
        var cached = badge == null ? null : badge.getAndSet(null);
        if (cached != null) {
            connect = () -> cached;
        } else if (owner.uiSession.isDm()) {
            SessionChat hosted = SessionChat.hosted(owner.uiSession.campaignRoot());
            if (hosted == null) { owner.showInfo("Session chat", "Start a local or internet session before using chat."); return; }
            connect = () -> hosted.connection(SessionChat.HOST);
        } else {
            var sync = owner.uiSession.getSession().getFirebaseSync();
            String character = owner.uiSession.getSession().getActivePlayerCharacterId();
            if (sync == null || sync.isHost() || character == null) {
                owner.showInfo("Session chat", "Join a hosted session and choose your character before using chat.");
                return;
            }
            var pc = new com.dnd.data.CampaignRepositories(owner.uiSession.campaignRoot()).players().getById(character);
            String password = null;
            if (pc == null || !com.dnd.cloud.Cloud.hasOnlineAccount() || owner.uiSession.getCurrentUser() == null
                || !pc.isOwnedBy(owner.uiSession.getCurrentUser().getUsername())) {
                var result = characterPassword(owner);
                if (result.isEmpty()) return;
                password = result.get();
            }
            String secret = password;
            connect = () -> sync.chatClient(character, secret);
        }
        new SessionChatUi(owner).open(connect);
    }

    private static Optional<String> characterPassword(BaseScene owner) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Character password for chat");
        PasswordField field = new PasswordField();
        field.setPromptText("Character password");
        dialog.getDialogPane().setContent(field);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK ? field.getText() : null);
        owner.styleDialog(dialog);
        return dialog.showAndWait();
    }

    private SessionChatUi(BaseScene owner) {
        this.owner = owner;
        campaign = owner.uiSession.campaignRoot();
        user = owner.uiSession.getCurrentUser();
        gm = owner.uiSession.isDm();
        send = owner.btn("Send", () -> mutate("send"));
        create = owner.btn("New conversation", () -> mutate("create"));
        retry = owner.btn("Retry pending action", () -> execute(pending));
        usePassword = owner.btn("Use character password", () -> {
            if (busy || pending != null) return;
            var password = characterPassword(owner);
            if (password.isEmpty()) return;
            var sync = owner.uiSession.getSession().getFirebaseSync();
            String character = owner.uiSession.getSession().getActivePlayerCharacterId();
            if (sync == null || character == null) {
                owner.showInfo("Session chat", "Join a hosted session and choose your character before using chat.");
                return;
            }
            if (connection != null) connection.close();
            connection = null;
            connect(() -> sync.chatClient(character, password.get()));
        });
        timer = new Timeline(new KeyFrame(Duration.seconds(2), e -> refresh()));
        timer.setCycleCount(Timeline.INDEFINITE);
    }

    private void open(Supplier<SessionChat.Connection> connect) {
        com.dnd.ui.WindowOrder.adopt(stage);
        stage.setTitle("Session chat");
        messages.setEditable(false);
        messages.setWrapText(true);
        compose.setWrapText(true);
        compose.setPromptText("Message");
        compose.setPrefRowCount(3);
        compose.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 2000 ? change : null));
        title.setPromptText("Conversation title");
        title.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 100 ? change : null));
        conversations.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(SessionChat.Conversation item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title() + (item.unread() > 0 ? " (" + item.unread() + ")" : ""));
            }
        });
        conversations.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (updating || busy || pending != null) return;
            selected = b == null ? null : b.id();
            refresh();
        });
        conversations.setPrefWidth(280);
        ScrollPane people = new ScrollPane(recipients);
        people.setFitToWidth(true);
        people.setPrefViewportHeight(130);
        VBox newChat = new VBox(8, title, owner.body("Choose recipients"), people, create);
        TitledPane group = new TitledPane("New conversation", newChat);
        group.setExpanded(false);
        FlowPane actions = new FlowPane(8, 8, send, retry, owner.btn("Discard pending retry", this::discardRetry));
        if (!gm) actions.getChildren().add(usePassword);
        VBox main = new VBox(8, messages, compose, actions, group, status);
        VBox.setVgrow(messages, Priority.ALWAYS);
        HBox center = new HBox(12, conversations, main);
        HBox.setHgrow(main, Priority.ALWAYS);
        VBox.setVgrow(center, Priority.ALWAYS);
        Label disclosure = owner.body(SessionChat.DISCLOSURE);
        disclosure.setWrapText(true);
        VBox root = new VBox(12, disclosure, center);
        root.setPadding(new Insets(16));
        stage.setScene(owner.themedScene(root, 1000, 760));
        stage.setOnCloseRequest(event -> {
            if (pending != null && !confirmDiscard()) event.consume();
        });
        stage.setOnHidden(event -> {
            closed = true;
            timer.stop();
            var badge = BADGES.get(owner.uiSession);
            if (connection != null && !gm && active() && badge != null) {
                var previous = badge.getAndSet(connection);
                if (previous != null && previous != connection) previous.close();
            } else if (connection != null) connection.close();
            connection = null;
            WINDOWS.remove(owner.uiSession);
            messages.clear();
            compose.clear();
            view = null;
            pending = null;
        });
        javafx.beans.value.ChangeListener<javafx.scene.Scene> changed = (o, a, b) -> {
            if (!active()) stage.hide();
        };
        owner.uiSession.primaryStage().sceneProperty().addListener(changed);
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN,
            event -> owner.uiSession.primaryStage().sceneProperty().removeListener(changed));
        WINDOWS.put(owner.uiSession, stage);
        stage.show();
        connect(connect);
    }

    private void connect(Supplier<SessionChat.Connection> connect) {
        if (closed || !active()) { stage.hide(); return; }
        busy = true;
        controls();
        owner.background(connect, value -> {
            if (closed) { value.close(); return; }
            connection = value;
            busy = false;
            refresh();
            timer.play();
        }, error -> {
            busy = false;
            status.setText(UiTranslator.text("Chat unavailable") + ": " + owner.friendlyError(error));
            controls();
        });
    }

    private void refresh() {
        if (!active()) { stage.hide(); return; }
        if (closed || busy || pending != null || connection == null) return;
        execute(SessionChat.Command.poll(stage.isIconified() ? null : selected));
    }

    private void mutate(String operation) {
        if (busy || pending != null || view == null) return;
        List<String> chosen = recipients.getChildren().stream().filter(n -> n instanceof CheckBox c && c.isSelected())
            .map(n -> (String) n.getUserData()).toList();
        pending = new SessionChat.Command(UUID.randomUUID().toString(), operation, selected, chosen, title.getText(), compose.getText());
        execute(pending);
    }

    private void execute(SessionChat.Command command) {
        if (!active()) { stage.hide(); return; }
        if (closed || busy || connection == null || command == null) return;
        busy = true;
        controls();
        owner.background(() -> connection.execute(command), result -> {
            busy = false;
            if (closed || !active()) { stage.hide(); return; }
            view = result;
            if (!"poll".equals(command.operation())) {
                pending = null;
                if ("send".equals(command.operation())) compose.clear();
            }
            if (!stage.isIconified()) selected = result.selected();
            status.setText("");
            var sync = owner.uiSession.getSession().getFirebaseSync();
            if (sync != null && sync.isHost() && sync.chatFailure() != null) status.setText(UiTranslator.text(sync.chatFailure()));
            render();
        }, error -> {
            busy = false;
            if (closed) return;
            if ("poll".equals(command.operation())) {
                view = null;
                messages.clear();
                recipients.getChildren().clear();
                updating = true;
                try { conversations.getItems().clear(); }
                finally { updating = false; }
            }
            status.setText(owner.friendlyError(error));
            controls();
        });
    }

    private void render() {
        updating = true;
        try {
            conversations.getItems().setAll(view.conversations());
            for (var item : conversations.getItems()) if (item.id().equals(selected)) conversations.getSelectionModel().select(item);
        } finally { updating = false; }
        Set<String> checked = recipients.getChildren().stream().filter(n -> n instanceof CheckBox c && c.isSelected())
            .map(n -> (String) n.getUserData()).collect(java.util.stream.Collectors.toSet());
        recipients.getChildren().clear();
        Map<String, String> names = new HashMap<>();
        for (var peer : view.peers()) {
            String name = SessionChat.HOST.equals(peer.id()) ? UiTranslator.text("Game Master") : peer.name();
            names.put(peer.id(), name);
            if (!peer.id().equals(view.peer())) {
                CheckBox box = UiTranslator.skip(new CheckBox(name));
                box.setUserData(peer.id());
                box.setSelected(checked.contains(peer.id()));
                recipients.getChildren().add(box);
            }
        }
        StringBuilder text = new StringBuilder();
        for (var message : view.messages()) text.append(java.time.Instant.ofEpochMilli(message.at())).append(" - ")
            .append(names.getOrDefault(message.sender(), message.sender())).append('\n').append(message.text()).append("\n\n");
        if (!text.toString().equals(messages.getText())) {
            double scroll = messages.getScrollTop();
            messages.setText(text.toString());
            messages.setScrollTop(scroll);
        }
        controls();
    }

    private void controls() {
        boolean member = view != null && view.conversations().stream().anyMatch(c -> c.id().equals(selected) && c.members().contains(view.peer()));
        send.setDisable(busy || pending != null || !member);
        create.setDisable(busy || pending != null || view == null);
        conversations.setDisable(busy || pending != null);
        retry.setDisable(busy || pending == null);
        usePassword.setDisable(busy || pending != null);
    }

    private boolean active() {
        return owner.isCurrentRole() && user == owner.uiSession.getCurrentUser() && gm == owner.uiSession.isDm()
            && Objects.equals(campaign, owner.uiSession.campaignRoot());
    }

    private boolean confirmDiscard() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
            "Delivery may already have happened. Discard this retry and check the conversation before sending again?", ButtonType.OK, ButtonType.CANCEL);
        owner.styleDialog(alert);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void discardRetry() {
        if (!busy && pending != null && confirmDiscard()) { pending = null; refresh(); controls(); }
    }
}
