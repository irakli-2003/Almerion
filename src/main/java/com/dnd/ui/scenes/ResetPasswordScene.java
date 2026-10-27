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

public class ResetPasswordScene extends BaseScene {
    private final AuthService authService = new AuthService();

    public ResetPasswordScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = centeredVBox(14);
        root.getStyleClass().add("root");

        TextField emailField = textField("Email");
        if (uiSession.getPendingAuthEmail() != null) emailField.setText(uiSession.getPendingAuthEmail());
        TextField codeField = textField("6-digit reset code");
        PasswordField newPasswordField = passwordField("New password (min 6 characters)");
        PasswordField confirmField = passwordField("Confirm new password");
        Label error = body("");
        error.getStyleClass().add("error-label");

        root.getChildren().addAll(
            title("⚔  Almerion  ⚔"),
            subtitle("Choose a new password"),
            emailField,
            codeField,
            newPasswordField,
            confirmField,
            error,
            btn("Reset password", () -> {
                AuthService.SimpleResult result = authService.resetPassword(
                    emailField.getText().trim(), codeField.getText().trim(),
                    newPasswordField.getText(), confirmField.getText());
                if (result.success()) {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Password reset");
                    alert.setHeaderText(null);
                    alert.setContentText(result.message());
                    styleDialog(alert);
                    alert.showAndWait();
                    uiSession.getRouter().goTo(SceneType.LOGIN);
                } else {
                    error.setText(result.message());
                }
            }),
            btn("← Back to log in", () -> uiSession.getRouter().goTo(SceneType.LOGIN))
        );

        return wrapInScene(root);
    }
}
