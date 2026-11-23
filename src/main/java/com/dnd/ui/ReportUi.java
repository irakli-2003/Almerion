package com.dnd.ui;

import com.dnd.auth.EmailService;
import com.dnd.auth.SmtpConfig;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/** A compact report control on every app window, including modal dialogs. */
public final class ReportUi {
    private static final String HOOKED = "almerion.report.hooked";
    private static final String REPORT = "almerion.report.window";
    private static final String CSS = "/com/dnd/ui/styles/dnd-theme.css";
    private static boolean installed;
    private static final String OWNER = "almerion.report.owner";
    private static final String PRESENTATION = "almerion.report.presentation";

    public static void markPresentation(Scene scene) {
        scene.getProperties().put(PRESENTATION, true);
    }

    private ReportUi() {}

    public static void bind(Scene scene, com.dnd.ui.scenes.BaseScene owner) {
        scene.getProperties().put(OWNER, owner);
    }

    public static boolean prepareHistoryChange(Scene scene) {
        return !(scene.getProperties().get(OWNER) instanceof com.dnd.ui.scenes.BaseScene owner)
            || owner.prepareHistoryChange();
    }

    public static boolean prepareCampaignClose(Scene scene) {
        return scene == null || !(scene.getProperties().get(OWNER) instanceof com.dnd.ui.scenes.BaseScene owner)
            || owner.prepareCampaignClose();
    }

    public static void install() {
        if (installed) return;
        installed = true;
        new ArrayList<>(Window.getWindows()).forEach(ReportUi::hook);
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                new ArrayList<>(change.getAddedSubList()).forEach(ReportUi::hook);
            }
        });
    }

    private static void hook(Window window) {
        if (!(window instanceof Stage) || window.getProperties().putIfAbsent(HOOKED, true) != null) return;
        decorate(window.getScene());
        window.sceneProperty().addListener((obs, old, scene) -> decorate(scene));
    }

    private static void decorate(Scene scene) {
        if (scene == null || scene.getProperties().containsKey(PRESENTATION)) return;
        TextClipboard.install(scene);
        if (scene.getProperties().containsKey(REPORT)) return;
        scene.getProperties().put(REPORT, true);
        Parent content = scene.getRoot();
        if (content instanceof DialogPane pane) {
            // JavaFX sizes dialogs from their DialogPane; replacing the root causes resize feedback.
            ButtonType report = new ButtonType("\uD83D\uDC1E", ButtonBar.ButtonData.LEFT);
            pane.getButtonTypes().add(0, report);
            Button bug = (Button) pane.lookupButton(report);
            configureButton(bug);
            bug.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                e.consume();
                show(scene);
            });
            return;
        }
        Button bug = new Button("\uD83D\uDC1E");
        configureButton(bug);
        bug.setOnAction(e -> show(scene));
        var language = new com.dnd.ui.i18n.LanguagePicker();
        HBox footer = new HBox(6, language, bug);
        if (scene.getProperties().get(OWNER) instanceof com.dnd.ui.scenes.BaseScene owner) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            footer.getChildren().setAll(com.dnd.ui.scenes.OperationHistoryUi.footer(owner, scene), spacer, language, bug);
        }
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(2, 8, 2, 8));
        // A separate strip avoids covering Save buttons or map controls.
        scene.setRoot(new StackPane());
        BorderPane wrapper = new BorderPane(content);
        wrapper.setBottom(footer);
        scene.setRoot(wrapper);
    }

    private static void configureButton(Button bug) {
        bug.setTooltip(new Tooltip("Report a bug or suggestion"));
        bug.setAccessibleText("Report a bug or suggestion");
        bug.getStyleClass().add("dnd-button");
        bug.setMinSize(32, 28);
    }

    private static void show(Scene source) {
        show(source, (category, body, screenshot) -> {
            EmailService email = new EmailService();
            String problem = email.reportConfigProblem();
            if (problem != null) throw new EmailService.EmailNotConfiguredException(problem);
            String version = com.dnd.update.UpdateChecker.currentVersion();
            email.send(SmtpConfig.SERVICE_ADDRESS, "Almerion - " + ("bug".equals(category) ? "Bug" : "Suggestion"),
                "Almerion " + (version == null ? "(development build)" : version) + "\n"
                    + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                    + "\n\n" + body, screenshot);
        }, () -> new EmailService().reportConfigProblem() == null, ReportUi::configureEmail);
    }

    @FunctionalInterface
    interface Submission {
        void send(String category, String description, byte[] screenshot);
    }

    static void show(Scene source, Submission submission) {
        show(source, submission, () -> true, owner -> false);
    }

    static void show(Scene source, Submission submission, java.util.function.BooleanSupplier configured,
                     java.util.function.Predicate<Window> configure) {
        Image capture = null;
        String captureError = null;
        try {
            capture = source.snapshot(null);
        } catch (RuntimeException e) {
            captureError = e.getMessage();
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Report a bug or suggestion");
        dialog.initOwner(source.getWindow());
        dialog.setResizable(true);
        DialogPane pane = dialog.getDialogPane();
        pane.setMinWidth(Region.USE_PREF_SIZE);
        pane.getStylesheets().add(ReportUi.class.getResource(CSS).toExternalForm());
        pane.getScene().getProperties().put(REPORT, true);

        RadioButton bug = new RadioButton("Bug");
        RadioButton suggestion = new RadioButton("Suggestion");
        ToggleGroup categories = new ToggleGroup();
        bug.setToggleGroup(categories);
        suggestion.setToggleGroup(categories);
        bug.setSelected(true);
        TextArea description = new TextArea();
        description.setPromptText("Describe what happened, what you expected, and how to reproduce it.");
        description.setPrefRowCount(6);
        description.setWrapText(true);
        CheckBox include = new CheckBox("Include screenshot");
        include.setDisable(capture == null);
        ImageView preview = new ImageView(capture);
        preview.setPreserveRatio(true);
        preview.setFitWidth(480);
        preview.setFitHeight(240);
        preview.visibleProperty().bind(include.selectedProperty());
        preview.managedProperty().bind(include.selectedProperty());
        Label privacy = new Label("Review the screenshot before sending: it may contain private campaign or account information.");
        privacy.setWrapText(true);
        Label status = new Label();
        status.setWrapText(true);
        if (captureError != null) status.setText("Screenshot could not be captured: " + captureError);
        Label destination = new Label("Send to: " + SmtpConfig.SERVICE_ADDRESS);
        destination.setWrapText(true);
        Button configureButton = new Button("Configure email");
        configureButton.setId("configure-report-email");
        configureButton.setMinWidth(Region.USE_PREF_SIZE);
        configureButton.setOnAction(e -> {
            if (configure.test(pane.getScene().getWindow())) {
                status.setText("Email settings saved. Click Send to submit your report.");
            }
        });
        if (!configured.getAsBoolean()) status.setText("Email is not configured. Click Configure email to set it up.");
        VBox form = new VBox(8, new HBox(12, bug, suggestion), destination,
            new Label("Description:"), description, privacy, include, preview, configureButton, status);
        form.setPadding(new Insets(12));
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(530);
        scroll.setPrefViewportHeight(650);
        pane.setContent(scroll);
        ButtonType sendType = new ButtonType("Send", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(sendType, ButtonType.CANCEL);
        Button send = (Button) pane.lookupButton(sendType);
        Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
        for (Button button : new Button[]{send, cancel}) {
            button.setMinWidth(Region.USE_PREF_SIZE);
            ButtonBar.setButtonUniformSize(button, false);
        }
        Image screenshot = capture;
        send.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            String body = description.getText().strip();
            if (body.isBlank()) {
                status.setText("Describe the bug or suggestion before sending.");
                return;
            }
            if (body.length() > 8000) {
                status.setText("Enter a description of at most 8,000 characters.");
                return;
            }
            if (!configured.getAsBoolean()) {
                status.setText("Email is not configured. Click Configure email to set it up.");
                return;
            }
            String category = bug.isSelected() ? "bug" : "suggestion";
            boolean attach = include.isSelected();
            send.setDisable(true);
            cancel.setDisable(true);
            configureButton.setDisable(true);
            status.setText("Sending report...");
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws IOException {
                    submission.send(category, body, attach ? png(screenshot) : null);
                    return null;
                }
            };
            task.setOnSucceeded(done -> {
                status.setText("Your report was sent. Thank you!");
                cancel.setText("Close");
                cancel.setDisable(false);
                description.setDisable(true);
                bug.setDisable(true);
                suggestion.setDisable(true);
                include.setDisable(true);
            });
            task.setOnFailed(failed -> {
                send.setDisable(false);
                cancel.setDisable(false);
                configureButton.setDisable(false);
                Throwable problem = task.getException();
                status.setText(problem instanceof EmailService.EmailNotConfiguredException
                    ? problem.getMessage() : "Report could not be sent: " + problem.getMessage());
            });
            Thread worker = new Thread(task, "almerion-report");
            worker.setDaemon(true);
            worker.start();
        });
        dialog.showAndWait();
    }

    private static boolean configureEmail(Window owner) {
        SmtpConfig current = SmtpConfig.loadOrCreateTemplate();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Configure email");
        dialog.initOwner(owner);
        DialogPane pane = dialog.getDialogPane();
        pane.setMinWidth(Region.USE_PREF_SIZE);
        pane.getStylesheets().add(ReportUi.class.getResource(CSS).toExternalForm());
        pane.getScene().getProperties().put(REPORT, true);

        TextField address = new TextField(SmtpConfig.SERVICE_ADDRESS.equalsIgnoreCase(current.username)
            && !current.enabled ? "" : current.username);
        address.setPromptText("you@example.com");
        TextField host = new TextField(current.host);
        TextField port = new TextField(Integer.toString(current.port));
        PasswordField password = new PasswordField();
        Label help = new Label("Enter your own sender address. For Gmail, use smtp.gmail.com, port 587 or 465,"
            + " and a Google App Password (requires 2-Step Verification). Do not use your normal Google password.");
        help.setWrapText(true);
        help.setMaxWidth(465);
        Label status = new Label("Test and save sends a test email to your sender address, not to Almerion support.");
        status.setWrapText(true);
        status.setMaxWidth(465);
        VBox form = new VBox(8, help, new Label("Sender email"), address, new Label("SMTP server"), host,
            new Label("SMTP port (465 uses implicit TLS; other ports use STARTTLS)"), port,
            new Label("SMTP password or app password"), password, status);
        form.setPadding(new Insets(12));
        pane.setContent(form);
        ButtonType testType = new ButtonType("Test and save", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(testType, ButtonType.CANCEL);
        Button test = (Button) pane.lookupButton(testType);
        Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
        dialog.setOnCloseRequest(event -> { if (test.isDisabled()) event.consume(); });
        test.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            SmtpConfig candidate = new SmtpConfig();
            candidate.username = address.getText().strip();
            candidate.host = host.getText().strip();
            candidate.password = password.getText();
            candidate.enabled = true;
            try {
                candidate.port = Integer.parseInt(port.getText().strip());
            } catch (NumberFormatException e) {
                candidate.port = 0;
            }
            EmailService email = new EmailService(candidate);
            String problem = email.reportConfigProblem();
            if (problem != null) {
                status.setText(problem);
                return;
            }
            test.setDisable(true);
            cancel.setDisable(true);
            form.setDisable(true);
            status.setText("Sending test email...");
            Task<Void> worker = new Task<>() {
                @Override protected Void call() throws IOException {
                    email.testConnection();
                    candidate.saveWithProtectedPassword();
                    return null;
                }
            };
            worker.setOnSucceeded(done -> {
                test.setDisable(false);
                dialog.setResult(testType);
                dialog.close();
            });
            worker.setOnFailed(failed -> {
                form.setDisable(false);
                test.setDisable(false);
                cancel.setDisable(false);
                Throwable error = worker.getException();
                status.setText(error instanceof IOException
                    ? "The test email was sent, but Windows could not save the email settings securely."
                    : error.getMessage());
            });
            Thread thread = new Thread(worker, "almerion-test-email");
            thread.setDaemon(true);
            thread.start();
        });
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == testType;
    }

    static byte[] png(Image image) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(buffered, "png", out)) throw new IOException("PNG encoder is unavailable.");
        return out.toByteArray();
    }
}
