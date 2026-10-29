package com.dnd.ui.scenes;

import com.dnd.ui.UiSession;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;

public abstract class BaseScene {
    protected final UiSession uiSession;
    private static final String CSS_PATH = "/com/dnd/ui/styles/dnd-theme.css";

    protected BaseScene(UiSession uiSession) {
        this.uiSession = uiSession;
    }

    public abstract Scene build();

    protected Scene wrapInScene(javafx.scene.Parent root) {
        return themedScene(root, 900, 650);
    }

    /** Builds a {@link Scene} of the given size with the app's stylesheet applied, for windows/dialogs that shouldn't use the default 900x650 size. */
    protected Scene themedScene(Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());
        return scene;
    }

    /**
     * Applies the app's dark-blue stylesheet to a {@link Dialog}'s (Alert, TextInputDialog,
     * ChoiceDialog, ...) own {@code DialogPane}. Dialogs open in their own {@code Scene} that
     * does NOT inherit stylesheets from the scene that spawned them, so without this they
     * render with JavaFX's default white/light-gray look.
     */
    protected void styleDialog(Dialog<?> dialog) {
        com.dnd.ui.WindowOrder.adopt(dialog);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());
        // Translated captions are often longer than the English ones; never cut them off with "...".
        Runnable fitButtons = () -> {
            for (javafx.scene.control.ButtonType type : dialog.getDialogPane().getButtonTypes()) {
                javafx.scene.Node node = dialog.getDialogPane().lookupButton(type);
                if (node instanceof Button b) {
                    b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                    javafx.scene.control.ButtonBar.setButtonUniformSize(b, false);
                }
            }
        };
        fitButtons.run();
        dialog.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_SHOWING, e -> fitButtons.run());
    }

    protected Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("title-label");
        return l;
    }

    protected Label subtitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("subtitle-label");
        return l;
    }

    protected Label body(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("body-label");
        l.setMaxWidth(600);
        return l;
    }

    protected Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-label");
        return l;
    }

    protected Button btn(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("dnd-button");
        b.setOnAction(e -> action.run());
        return b;
    }

    protected Button dangerBtn(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("danger-button");
        b.setOnAction(e -> action.run());
        return b;
    }

    protected TextField textField(String prompt) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.getStyleClass().add("dnd-text-field");
        tf.setMaxWidth(400);
        return tf;
    }

    protected PasswordField passwordField(String prompt) {
        PasswordField pf = new PasswordField();
        pf.setPromptText(prompt);
        pf.getStyleClass().add("dnd-text-field");
        pf.setMaxWidth(400);
        return pf;
    }

    /**
     * Builds a {@link CheckBox} with an explicit, single-fire toggle handler.
     *
     * <p>Some environments (e.g. remote-desktop input) can deliver a click event to the
     * default {@code CheckBox} skin twice, which toggles the selection twice and makes the
     * control appear permanently "stuck" from the user's point of view. Handling the toggle
     * manually and consuming the mouse event guarantees exactly one state flip per click.</p>
     */
    protected CheckBox checkBox(String label) {
        CheckBox cb = new CheckBox(label);
        cb.getStyleClass().add("dnd-check-box");
        // Event FILTERS run in the capturing phase, before the control's own skin/behavior
        // handles the event in the bubbling phase. Consuming here fully replaces the
        // built-in toggle-on-click behavior with our own single, deterministic toggle,
        // instead of layering a second toggle on top of it (which is what caused the
        // reported "checkbox can't be unchecked" bug: two toggles per click net to no change).
        cb.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            cb.requestFocus();
            cb.setSelected(!cb.isSelected());
            e.consume();
        });
        return cb;
    }

    protected Region spacer() {
        Region r = new Region();
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    protected Region hSpacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    protected VBox centeredVBox(int spacing) {
        VBox box = new VBox(spacing);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        return box;
    }

    protected HBox backBar(com.dnd.ui.SceneType backTo) {
        Button back = new Button("← Back");
        back.getStyleClass().add("dnd-button");
        back.setOnAction(e -> uiSession.getRouter().goTo(backTo));
        HBox bar = new HBox(back);
        bar.setPadding(new Insets(12, 20, 0, 20));
        return bar;
    }

    /**
     * Shows a one-time verification/reset code on screen, explaining exactly why it
     * couldn't be emailed. Without the concrete reason the two common setup failures
     * (a Gmail account missing an App Password, and a network blocking outbound SMTP)
     * are indistinguishable to the user.
     */
    protected void showCodeFallbackDialog(String codeLabel, String code, String emailError) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
        alert.setTitle("Email could not be sent");
        alert.setHeaderText("Your " + codeLabel + " is: " + code);
        alert.setContentText("The code above is valid - use it to continue.\n\nIt wasn't emailed because:\n"
            + (emailError == null || emailError.isBlank() ? "SMTP is not configured yet." : emailError));
        alert.getDialogPane().setPrefWidth(560);
        styleDialog(alert);
        alert.showAndWait();
    }

    /** Runs slow (network) work off the UI thread and hands the result back on it. */
    protected <T> void background(java.util.function.Supplier<T> work, java.util.function.Consumer<T> done,
                                  java.util.function.Consumer<Throwable> failed) {
        Thread thread = new Thread(() -> {
            try {
                T result = work.get();
                javafx.application.Platform.runLater(() -> done.accept(result));
            } catch (Throwable t) {
                javafx.application.Platform.runLater(() -> failed.accept(t));
            }
        }, "dnd-background");
        thread.setDaemon(true);
        thread.start();
    }

    protected void showError(String header, Throwable error) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
        alert.setHeaderText(header);
        alert.setContentText(friendlyError(error));
        styleDialog(alert);
        alert.showAndWait();
    }

    protected void showInfo(String header, String text) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setHeaderText(header);
        alert.setContentText(text);
        alert.getDialogPane().setPrefWidth(520);
        styleDialog(alert);
        alert.showAndWait();
    }

    protected static String friendlyError(Throwable error) {
        if (error instanceof com.dnd.cloud.CloudStore.CloudException ce && ce.isOffline()) {
            return "Can't reach the online campaign directory. Check your internet connection and try again.";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
