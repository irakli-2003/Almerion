package com.dnd.ui;

import com.dnd.auth.AuthService;
import com.dnd.auth.EmailService;
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
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

/** A compact report control on every app window, including modal dialogs. */
public final class ReportUi {
    private static final String HOOKED = "almerion.report.hooked";
    private static final String REPORT = "almerion.report.window";
    private static final String CSS = "/com/dnd/ui/styles/dnd-theme.css";
    private static boolean installed;

    private ReportUi() {}

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
        if (scene == null || scene.getProperties().containsKey(REPORT)) return;
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
        HBox footer = new HBox(bug);
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
        show(source, new EmailService());
    }

    static void show(Scene source, EmailService email) {
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
        TextField recipient = new TextField(email.reportRecipient());
        recipient.setPromptText("Support email address");
        TextArea description = new TextArea();
        description.setPromptText("Describe what happened, what you expected, and how to reproduce it.");
        description.setPrefRowCount(6);
        description.setWrapText(true);
        CheckBox include = new CheckBox("Include screenshot");
        include.setSelected(capture != null);
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
        VBox form = new VBox(8, new HBox(12, bug, suggestion), new Label("Send to:"), recipient,
            new Label("Description:"), description, privacy, include, preview, status);
        form.setPadding(new Insets(12));
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(530);
        scroll.setPrefViewportHeight(650);
        pane.setContent(scroll);
        ButtonType sendType = new ButtonType("Send", ButtonBar.ButtonData.OK_DONE);
        ButtonType draftType = new ButtonType("Save email draft...", ButtonBar.ButtonData.OTHER);
        pane.getButtonTypes().addAll(sendType, draftType, ButtonType.CANCEL);
        Button send = (Button) pane.lookupButton(sendType);
        Button draft = (Button) pane.lookupButton(draftType);
        Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
        for (Button button : new Button[]{send, draft, cancel}) {
            button.setMinWidth(Region.USE_PREF_SIZE);
            ButtonBar.setButtonUniformSize(button, false);
        }
        Image screenshot = capture;
        send.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            if (!validate(recipient, description, status)) return;
            if (!email.isConfigured()) {
                status.setText("Email is not configured. Save an email draft to send with your email application.");
                return;
            }
            String to = recipient.getText().trim();
            String category = bug.isSelected() ? "Bug" : "Suggestion";
            String body = reportBody(description.getText());
            boolean attach = include.isSelected();
            send.setDisable(true);
            draft.setDisable(true);
            cancel.setDisable(true);
            status.setText("Sending report...");
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws IOException {
                    email.send(to, "Almerion - " + category, body, attach ? png(screenshot) : null);
                    return null;
                }
            };
            task.setOnSucceeded(done -> {
                status.setText("Your report was sent. Thank you!");
                cancel.setText("Close");
                cancel.setDisable(false);
                recipient.setDisable(true);
                description.setDisable(true);
                bug.setDisable(true);
                suggestion.setDisable(true);
                include.setDisable(true);
            });
            task.setOnFailed(failed -> {
                send.setDisable(false);
                draft.setDisable(false);
                cancel.setDisable(false);
                status.setText("Report could not be sent: " + task.getException().getMessage()
                    + "\nSave an email draft or try again.");
            });
            Thread worker = new Thread(task, "almerion-report");
            worker.setDaemon(true);
            worker.start();
        });
        draft.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            if (!validate(recipient, description, status)) return;
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save email draft");
            chooser.setInitialFileName("almerion-report.eml");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Email draft", "*.eml"));
            var file = chooser.showSaveDialog(pane.getScene().getWindow());
            if (file == null) return;
            try {
                byte[] attachment = include.isSelected() ? png(screenshot) : null;
                Files.write(file.toPath(), email.reportDraft(recipient.getText().trim(),
                    "Almerion - " + (bug.isSelected() ? "Bug" : "Suggestion"),
                    reportBody(description.getText()), attachment));
                status.setText("Draft saved. Open the .eml file in your email application to send it.");
            } catch (IOException | EmailService.EmailSendException ex) {
                status.setText("Could not save the email draft: " + ex.getMessage());
            }
        });
        dialog.showAndWait();
    }

    private static boolean validate(TextField recipient, TextArea description, Label status) {
        if (!AuthService.isValidEmail(recipient.getText().trim())) {
            status.setText("Enter a valid support email address.");
            return false;
        }
        if (description.getText().isBlank()) {
            status.setText("Describe the bug or suggestion before sending.");
            return false;
        }
        return true;
    }

    private static String reportBody(String description) {
        String version = com.dnd.update.UpdateChecker.currentVersion();
        return "Almerion " + (version == null ? "(development build)" : version) + "\n"
            + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
            + "\n\n" + description;
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
