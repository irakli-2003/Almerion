package com.dnd.ui.scenes;

import com.dnd.auth.AuthService;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class ForgotPasswordScene extends BaseScene {
    private final AuthService authService = new AuthService();

    public ForgotPasswordScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = centeredVBox(14);
        root.getStyleClass().add("root");

        TextField emailField = textField("Email");
        Label error = body("");
        error.getStyleClass().add("error-label");

        root.getChildren().addAll(
            title("⚔  DnD Campaign Manager  ⚔"),
            subtitle("Reset your password"),
            body("Enter the email associated with your account and we'll send a reset code."),
            emailField,
            error,
            btn("Send reset code", () -> {
                String email = emailField.getText().trim();
                AuthService.CodeResult result = authService.requestPasswordReset(email);
                if (!result.success()) {
                    error.setText(result.message());
                    return;
                }
                uiSession.setPendingAuthEmail(email);
                if (result.sentAsLink()) {
                    showInfo("Check your email",
                        "A password-reset link was sent to " + email + ". Choose a new password there, then log in"
                            + " here with it. (Check the spam folder if it doesn't arrive.)");
                    uiSession.getRouter().goTo(SceneType.LOGIN);
                    return;
                }
                if (!result.emailSent()) {
                    showCodeFallbackDialog("reset code", result.code(), result.emailError());
                }
                uiSession.getRouter().goTo(SceneType.RESET_PASSWORD);
            }),
            btn("← Back to log in", () -> uiSession.getRouter().goTo(SceneType.LOGIN))
        );

        return wrapInScene(root);
    }
}
