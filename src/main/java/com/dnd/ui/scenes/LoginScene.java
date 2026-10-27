package com.dnd.ui.scenes;

import com.dnd.auth.AuthService;
import com.dnd.auth.EmailService;
import com.dnd.auth.SmtpConfig;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import com.dnd.ui.i18n.LanguagePicker;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class LoginScene extends BaseScene {
    private final AuthService authService = new AuthService();

    public LoginScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = centeredVBox(14);
        root.getStyleClass().add("root");

        TextField identifierField = textField("Username or email");
        PasswordField passwordField = passwordField("Password");
        Label error = body("");
        error.getStyleClass().add("error-label");

        root.getChildren().addAll(
            title("⚔  Almerion  ⚔"),
            subtitle("Log in to continue"),
            identifierField,
            passwordField,
            error,
            btn("Log In", () -> {
                AuthService.LoginResult result = authService.login(identifierField.getText(), passwordField.getText());
                if (result.success()) {
                    uiSession.setCurrentUser(result.user());
                    uiSession.getRouter().goTo(SceneType.LANDING);
                } else {
                    error.setText(result.message());
                }
            }),
            btn("Create an account", () -> uiSession.getRouter().goTo(SceneType.REGISTER)),
            btn("Forgot password?", () -> uiSession.getRouter().goTo(SceneType.FORGOT_PASSWORD)),
            btn("Test email settings", this::testEmailSettings)
        );
        LanguagePicker.pinTopRight(root);

        return wrapInScene(root);
    }

    /**
     * Sends a test message to the configured from-address and reports the exact outcome,
     * so SMTP problems can be diagnosed without going through a whole registration.
     */
    private void testEmailSettings() {
        EmailService emailService = new EmailService();
        Alert alert;
        try {
            emailService.testConnection();
            alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Email works");
            alert.setHeaderText(null);
            alert.setContentText("A test email was sent successfully. Check your inbox.");
        } catch (Exception e) {
            alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Email settings problem");
            alert.setHeaderText("Could not send a test email");
            alert.setContentText(e.getMessage() + "\n\nSettings file: " + SmtpConfig.configPath().toAbsolutePath());
        }
        alert.getDialogPane().setPrefWidth(600);
        styleDialog(alert);
        alert.showAndWait();
    }
}
