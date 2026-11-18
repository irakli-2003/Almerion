package com.dnd.ui.scenes;

import com.dnd.chat.SessionChat;
import com.dnd.ui.UiSession;
import com.dnd.ui.i18n.UiTranslator;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
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
    private final VBox directMessages = new VBox(4);
    private record DirectRow(SessionChat.Peer peer, String conversationId, int unread, boolean selected) { }
    private List<DirectRow> renderedDirectRows = List.of();
    private final TextArea messages = new TextArea();
    private final TextArea compose = new TextArea();
    private final Label status = new Label();
    private final Button send;
    private final Button create;
    private final MenuButton manage = new MenuButton("Manage conversation");
    private final MenuItem rename = new MenuItem("Rename group");
    private final MenuItem deleteMessage = new MenuItem("Delete message…");
    private final MenuItem deleteThread = new MenuItem("Delete conversation");
    private final Button retry;
    private final Button discard;
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
        javafx.beans.value.ChangeListener<javafx.stage.Window> windowChanged = (p, before, window) -> {
            if (before != null && window == null) close.run();
        };
        button.sceneProperty().addListener((o, previous, scene) -> {
            if (previous != null) previous.windowProperty().removeListener(windowChanged);
            if (previous != null && previous != scene) {
                if (scene == null) {
                    // Footer wrapping briefly detaches the button from the same scene.
                    javafx.application.Platform.runLater(() -> {
                        if (button.getScene() != previous) close.run();
                    });
                } else close.run();
            }
            if (scene != null) scene.windowProperty().addListener(windowChanged);
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
        create = owner.btn("New conversation", this::openNewConversation);
        retry = owner.btn("Retry delivery", () -> execute(pending));
        discard = owner.btn("Check without resending", this::discardRetry);
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
        conversations.getStyleClass().add("dnd-list-view");
        conversations.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(SessionChat.Conversation item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title() + (item.unread() > 0 ? " (" + item.unread() + ")" : ""));
            }
        });
        conversations.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (updating || pending != null) return;
            selected = b == null ? null : b.id();
            refresh();
        });
        conversations.setPrefWidth(280);
        manage.getStyleClass().add("dnd-button");
        manage.getItems().addAll(rename, deleteMessage);
        if (gm) manage.getItems().add(deleteThread);
        rename.setOnAction(e -> renameConversation());
        deleteMessage.setOnAction(e -> openMessageDeletion());
        deleteThread.setOnAction(e -> {
            var thread = selectedThread();
            if (thread != null && confirm("Delete this conversation and all of its messages?"))
                submitChatChange("delete-conversation", thread.id(), "", "");
        });
        FlowPane actions = new FlowPane(8, 8, send, create, manage, retry, discard);
        if (!gm) actions.getChildren().add(usePassword);
        VBox main = new VBox(8, messages, compose, actions, status);
        VBox.setVgrow(messages, Priority.ALWAYS);
        ScrollPane directScroll = new ScrollPane(directMessages);
        directScroll.setFitToWidth(true);
        directScroll.setPrefViewportHeight(190);
        VBox sidebar = new VBox(8, owner.sectionLabel("Direct messages"), directScroll,
            owner.sectionLabel("Other conversations"), conversations);
        VBox.setVgrow(conversations, Priority.ALWAYS);
        HBox center = new HBox(12, sidebar, main);
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
        pending = new SessionChat.Command(UUID.randomUUID().toString(), operation, selected, List.of(), "", compose.getText());
        execute(pending);
    }

    private SessionChat.Conversation selectedThread() {
        return view == null ? null : view.conversations().stream().filter(c -> c.id().equals(selected))
            .findFirst().orElse(null);
    }

    private void submitChatChange(String operation, String thread, String title, String text) {
        if (closed || pending != null) return;
        pending = new SessionChat.Command(UUID.randomUUID().toString(), operation, thread, List.of(), title, text);
        if (!busy) execute(pending);
    }

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage);
        owner.styleDialog(alert);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void renameConversation() {
        var thread = selectedThread();
        if (thread == null) return;
        TextInputDialog dialog = new TextInputDialog(thread.title());
        dialog.initOwner(stage);
        dialog.setTitle("Rename group");
        dialog.getEditor().setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().length() <= 100 ? c : null));
        owner.styleDialog(dialog);
        dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty())
            .ifPresent(title -> submitChatChange("rename", thread.id(), title, ""));
    }

    private void openMessageDeletion() {
        var thread = selectedThread();
        if (thread == null || !Objects.equals(thread.id(), view.selected())) return;
        List<SessionChat.Message> choices = view.messages().reversed().stream()
            .filter(m -> gm || m.sender().equals(view.peer())).toList();
        if (choices.isEmpty()) return;
        var names = view.peers().stream().collect(java.util.stream.Collectors.toMap(SessionChat.Peer::id, SessionChat.Peer::name));
        ListView<SessionChat.Message> list = new ListView<>();
        list.getStyleClass().add("dnd-list-view");
        list.getItems().setAll(choices);
        list.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(SessionChat.Message message, boolean empty) {
                super.updateItem(message, empty);
                if (empty || message == null) { setText(null); return; }
                String preview = message.text().replace('\n', ' ');
                if (preview.length() > 140) preview = preview.substring(0, 140) + "…";
                setText(names.getOrDefault(message.sender(), message.sender()) + ": " + preview);
            }
        });
        Dialog<SessionChat.Message> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Delete message");
        ButtonType remove = new ButtonType("Delete selected message", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(remove, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(list);
        dialog.getDialogPane().lookupButton(remove).disableProperty()
            .bind(list.getSelectionModel().selectedItemProperty().isNull());
        dialog.setResultConverter(button -> button == remove ? list.getSelectionModel().getSelectedItem() : null);
        owner.styleDialog(dialog);
        dialog.showAndWait().ifPresent(message ->
            submitChatChange("delete-message", thread.id(), "", Long.toString(message.sequence())));
    }

    private void openNewConversation() {
        if (pending != null || view == null) return;
        var current = view;
        Dialog<SessionChat.Command> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("New conversation");
        TextField title = new TextField();
        title.setPromptText("Group name (optional)");
        title.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 100 ? change : null));
        var people = current.peers().stream().filter(p -> !p.id().equals(current.peer()))
            .sorted(Comparator.comparing(SessionChat.Peer::name, String.CASE_INSENSITIVE_ORDER).thenComparing(SessionChat.Peer::id))
            .map(peer -> {
                CheckBox box = UiTranslator.skip(new CheckBox(peer.name()));
                box.setUserData(peer.id());
                return box;
            }).toList();
        VBox choices = new VBox(6);
        choices.getChildren().setAll(people);
        ScrollPane list = new ScrollPane(choices);
        list.setFitToWidth(true);
        list.setPrefViewportHeight(220);
        var confirm = new ButtonType("Open conversation", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(confirm, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(10, title, list));
        Node open = dialog.getDialogPane().lookupButton(confirm);
        Runnable update = () -> open.setDisable(people.stream().noneMatch(CheckBox::isSelected));
        people.forEach(box -> box.selectedProperty().addListener((o, a, b) -> update.run()));
        update.run();
        dialog.setResultConverter(button -> button != confirm ? null : new SessionChat.Command(
            UUID.randomUUID().toString(), "create", null, people.stream().filter(CheckBox::isSelected)
                .map(box -> (String) box.getUserData()).toList(), title.getText(), ""));
        owner.styleDialog(dialog);
        timer.stop();
        try {
            dialog.showAndWait().ifPresent(command -> {
                pending = command;
                execute(command);
            });
        } finally {
            if (!closed) timer.play();
        }
    }

    private void execute(SessionChat.Command command) {
        if (!active()) { stage.hide(); return; }
        if (closed || busy || connection == null || command == null) return;
        String selectionAtDispatch = selected;
        busy = true;
        controls();
        owner.background(() -> connection.execute(command), result -> {
            busy = false;
            if (closed || !active()) { stage.hide(); return; }
            if ("poll".equals(command.operation()) && pending != null) { execute(pending); return; }
            if ("poll".equals(command.operation()) && !stage.isIconified()
                    && !Objects.equals(selectionAtDispatch, selected)) {
                refresh();
                return;
            }
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
            if ("poll".equals(command.operation()) && pending != null) { execute(pending); return; }
            status.setText(owner.friendlyError(error));
            controls();
        });
    }

    private void render() {
        var others = view.conversations().stream().filter(c -> c.members().size() != 2
            || !c.members().contains(view.peer())
            || view.peers().stream().noneMatch(p -> !p.id().equals(view.peer()) && c.members().contains(p.id()))).toList();
        updating = true;
        try {
            if (!conversations.getItems().equals(others)) {
                conversations.getItems().setAll(others);
            }
            var active = others.stream().filter(c -> c.id().equals(selected)).findFirst().orElse(null);
            if (active == null) conversations.getSelectionModel().clearSelection();
            else conversations.getSelectionModel().select(active);
        } finally { updating = false; }
        var direct = view.peers().stream().filter(p -> !p.id().equals(view.peer()))
            .sorted(Comparator.comparing(SessionChat.Peer::name, String.CASE_INSENSITIVE_ORDER).thenComparing(SessionChat.Peer::id))
            .map(peer -> {
                var existing = view.conversations().stream().filter(c -> c.members().size() == 2
                    && c.members().contains(view.peer()) && c.members().contains(peer.id())).findFirst().orElse(null);
                return new DirectRow(peer, existing == null ? null : existing.id(), existing == null ? 0 : existing.unread(),
                    existing != null && existing.id().equals(selected));
            }).toList();
        if (!direct.equals(renderedDirectRows)) {
            renderedDirectRows = direct;
            directMessages.getChildren().setAll(direct.stream().map(row -> {
                var button = UiTranslator.skip(owner.btn(row.peer().name() + (row.unread() > 0 ? " (" + row.unread() + ")" : ""),
                    () -> openDirect(row)));
                button.setUserData(row.peer().id());
                button.setMaxWidth(Double.MAX_VALUE);
                button.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                if (row.selected()) button.setStyle("-fx-border-color: #c9a84c;");
                return button;
            }).toList());
        }
        Map<String, String> names = new HashMap<>();
        for (var peer : view.peers()) names.put(peer.id(), peer.name());
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

    private void openDirect(DirectRow row) {
        if (closed || pending != null) return;
        if (row.conversationId() != null) {
            selected = row.conversationId();
            refresh();
        } else {
            pending = new SessionChat.Command(UUID.randomUUID().toString(), "create", null, List.of(row.peer().id()), "", "");
            if (!busy) execute(pending);
        }
    }

    private void controls() {
        var thread = selectedThread();
        boolean member = thread != null && thread.members().contains(view.peer());
        send.setDisable(busy || pending != null || !member);
        create.setDisable(busy || pending != null || view == null);
        conversations.setDisable(pending != null);
        directMessages.setDisable(pending != null);
        manage.setDisable(pending != null || thread == null);
        rename.setDisable(!member || thread.members().size() < 3);
        deleteMessage.setDisable(thread == null || !Objects.equals(thread.id(), view.selected())
            || view.messages().stream().noneMatch(m -> gm || m.sender().equals(view.peer())));
        retry.setDisable(busy || pending == null);
        retry.setVisible(!busy && pending != null);
        retry.setManaged(retry.isVisible());
        discard.setVisible(retry.isVisible());
        discard.setManaged(discard.isVisible());
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
