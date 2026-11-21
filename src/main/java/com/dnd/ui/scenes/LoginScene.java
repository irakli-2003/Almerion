package com.dnd.ui.scenes;

import com.dnd.auth.AuthService;
import com.dnd.auth.RememberedLogin;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import com.dnd.ui.i18n.LanguagePicker;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;

public class LoginScene extends BaseScene {
    private final AuthService authService = new AuthService();

    public LoginScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = centeredVBox(14);
        root.getStyleClass().add("root");

        TextField identifierField = textField("Username or email");
        PasswordField passwordField = passwordField("Password");
        RememberedLogin remembered = new RememberedLogin();
        CheckBox remember = new CheckBox("Remember credentials");
        remember.setDisable(!remembered.isSupported());
        remember.setTooltip(new Tooltip(remembered.isSupported()
            ? "Save your login on this Windows account. Uncheck to forget it."
            : "Remembering credentials is only available on Windows."));
        Label error = body("");
        error.getStyleClass().add("error-label");
        remember.setOnAction(event -> {
            if (remember.isSelected()) return;
            try {
                remembered.clear();
            } catch (IOException failure) {
                remember.setSelected(true);
                error.setText("Could not remove the saved credentials.");
            }
        });
        Button login = btn("Log In", () -> { });
        login.setDefaultButton(true);
        identifierField.setOnAction(event -> login.fire());
        passwordField.setOnAction(event -> login.fire());
        login.setOnAction(event -> {
            if (root.isDisabled()) return;
            String identifier = identifierField.getText();
            String password = passwordField.getText();
            boolean saveCredentials = remember.isSelected();
            error.setText("");
            root.setDisable(true);
            record Attempt(AuthService.LoginResult result, boolean saveFailed) {}
            background(() -> {
                var result = authService.login(identifier, password);
                if (result.success() && saveCredentials) {
                    try {
                        remembered.save(identifier.trim(), password);
                    } catch (IOException failure) {
                        return new Attempt(result, true);
                    }
                }
                return new Attempt(result, false);
            }, attempt -> {
                var result = attempt.result();
                if (!result.success()) {
                    root.setDisable(false);
                    error.setText(result.message());
                    return;
                }
                if (attempt.saveFailed()) {
                    showInfo("Credentials not saved", "Windows could not securely save your credentials. You can still log in.");
                }
                if (result.offline()) {
                    Alert notice = new Alert(Alert.AlertType.WARNING, result.message());
                    notice.setHeaderText(null);
                    styleDialog(notice);
                    notice.showAndWait();
                }
                passwordField.clear();
                uiSession.setCurrentUser(result.user());
                PrivateFilesUi.openAtSignIn(this, password, () -> {
                    root.setDisable(false);
                    uiSession.getRouter().goTo(SceneType.LANDING);
                });
            }, failure -> {
                root.setDisable(false);
                error.setText(failure.getMessage());
            });
        });

        root.getChildren().addAll(
            title("⚔  Almerion  ⚔"),
            subtitle("Log in to continue"),
            identifierField,
            passwordField,
            remember,
            error,
            login,
            btn("Create an account", () -> uiSession.getRouter().goTo(SceneType.REGISTER)),
            btn("Forgot password?", () -> uiSession.getRouter().goTo(SceneType.FORGOT_PASSWORD))
        );
        LanguagePicker.pinTopRight(root);
        if (remembered.isSupported()) {
            root.setDisable(true);
            background(() -> {
                try {
                    return remembered.load();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }, saved -> {
                saved.ifPresent(credentials -> {
                    identifierField.setText(credentials.identifier());
                    passwordField.setText(credentials.password());
                    remember.setSelected(true);
                });
                root.setDisable(false);
            }, failure -> {
                root.setDisable(false);
                error.setText("Could not read saved credentials. Log in again to replace them.");
            });
        }
        return wrapInScene(root);
    }

}
