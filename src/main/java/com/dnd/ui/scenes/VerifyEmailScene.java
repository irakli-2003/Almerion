package com.dnd.ui.scenes;

import com.dnd.auth.AuthService;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class VerifyEmailScene extends BaseScene {
    private final AuthService authService = new AuthService();

    public VerifyEmailScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = centeredVBox(14);
        root.getStyleClass().add("root");

        String email = uiSession.getPendingAuthEmail();
        TextField emailField = textField("Email");
        if (email != null) emailField.setText(email);
        TextField codeField = textField("6-digit verification code");
        Label error = body("");
        error.getStyleClass().add("error-label");

        root.getChildren().addAll(
            title("⚔  Almerion  ⚔"),
            subtitle("Verify your email"),
            body("Enter the verification code sent to your email."),
            emailField,
            codeField,
            error,
            btn("Verify", () -> {
                String e = emailField.getText().trim();
                AuthService.SimpleResult result = authService.verifyEmail(e, codeField.getText().trim());
                if (result.success()) {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Verified");
                    alert.setHeaderText(null);
                    alert.setContentText(result.message());
                    styleDialog(alert);
                    alert.showAndWait();
                    uiSession.getRouter().goTo(SceneType.LOGIN);
                } else {
                    error.setText(result.message());
                }
            }),
            btn("Resend code", () -> {
                String e = emailField.getText().trim();
                AuthService.CodeResult result = authService.resendVerification(e);
                if (!result.success()) {
                    error.setText(result.message());
                    return;
                }
                if (result.sentAsLink()) {
                    showInfo("Link sent", "A new verification link was emailed to you. Click it, then log in.");
                    uiSession.getRouter().goTo(SceneType.LOGIN);
                    return;
                }
                if (!result.emailSent()) {
                    showCodeFallbackDialog("new verification code", result.code(), result.emailError());
                    return;
                }
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Code sent");
                alert.setHeaderText(null);
                alert.setContentText("A new code was emailed to you.");
                styleDialog(alert);
                alert.showAndWait();
            }),
            btn("← Back to log in", () -> uiSession.getRouter().goTo(SceneType.LOGIN))
        );

        return wrapInScene(root);
    }
}
