package com.dnd.ui.scenes;

import com.dnd.auth.AuthService;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class RegisterScene extends BaseScene {
    private final AuthService authService = new AuthService();

    public RegisterScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = centeredVBox(14);
        root.getStyleClass().add("root");

        TextField usernameField = textField("Username");
        TextField emailField = textField("Email");
        PasswordField passwordField = passwordField("Password (min 6 characters)");
        PasswordField confirmField = passwordField("Confirm password");
        Label error = body("");
        error.getStyleClass().add("error-label");

        root.getChildren().addAll(
            title("⚔  DnD Campaign Manager  ⚔"),
            subtitle("Create an account"),
            usernameField,
            emailField,
            passwordField,
            confirmField,
            error,
            btn("Register", () -> {
                AuthService.CodeResult result = authService.register(
                    usernameField.getText(), emailField.getText(), passwordField.getText(), confirmField.getText());
                if (!result.success()) {
                    error.setText(result.message());
                    return;
                }
                uiSession.setPendingAuthEmail(emailField.getText());
                if (result.sentAsLink()) {
                    showInfo("Check your email",
                        "A verification link was sent to " + emailField.getText().trim()
                            + ". Click it, then log in here. (Check the spam folder if it doesn't arrive.)");
                    uiSession.getRouter().goTo(SceneType.LOGIN);
                    return;
                }
                if (!result.emailSent()) {
                    showCodeFallbackDialog("verification code", result.code(), result.emailError());
                }
                uiSession.getRouter().goTo(SceneType.VERIFY_EMAIL);
            }),
            btn("Already have an account? Log in", () -> uiSession.getRouter().goTo(SceneType.LOGIN))
        );

        return wrapInScene(root);
    }
}
