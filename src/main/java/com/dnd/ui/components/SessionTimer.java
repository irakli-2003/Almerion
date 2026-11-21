package com.dnd.ui.components;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/**
 * A small stopwatch / countdown strip that DMs can drop into any window.
 *
 * <p>Both directions matter at the table for different reasons: counting up tracks how long
 * a session or a single combat has run, while counting down enforces a turn time limit or
 * the duration of a spell. One control does both so the DM doesn't have to pick a widget.</p>
 */
public class SessionTimer extends HBox {

    private final Label display = new Label("00:00");
    private final Spinner<Integer> minutesSpinner = new Spinner<>(0, 240, 1);
    private final Button startPause = new Button("Start");
    private final CheckBox sound = new CheckBox("Sound");
    private final PauseTransition finalBeep = new PauseTransition(Duration.millis(250));
    private final Timeline ticker;
    private final java.nio.file.Path campaign;
    private String countdownId;

    private int elapsedSeconds;
    private boolean countingDown;
    private boolean running;

    public SessionTimer() {
        this(null);
    }

    public SessionTimer(java.nio.file.Path campaign) {
        super(6);
        this.campaign = campaign;
        setAlignment(Pos.CENTER_LEFT);

        display.getStyleClass().add("timer-display");
        display.setTooltip(new Tooltip("Session timer"));

        minutesSpinner.setPrefWidth(70);
        minutesSpinner.setEditable(true);
        minutesSpinner.setTooltip(new Tooltip("Minutes to count down from"));

        startPause.getStyleClass().add("dnd-button");
        startPause.setOnAction(e -> toggle());

        Button up = themed("Count Up", "Start a stopwatch from zero");
        up.setOnAction(e -> startCountUp());

        Button down = themed("Count Down", "Count down from the chosen number of minutes");
        down.setOnAction(e -> startCountDown(minutesSpinner.getValue() * 60));

        Button reset = themed("Reset", "Stop and clear the timer");
        reset.setOnAction(e -> reset());
        sound.setTooltip(new Tooltip("Play sounds for the last ten seconds and at zero, including on LAN player sheets with sound enabled."));
        sound.selectedProperty().addListener((o, before, after) -> publish(false, false));
        finalBeep.setOnFinished(e -> beep());

        // A one-second tick is accurate enough for a table timer and costs nothing; using a
        // Timeline (rather than a background thread) keeps every update on the FX thread.
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        ticker.setCycleCount(Animation.INDEFINITE);

        Label caption = new Label("Timer:");
        caption.getStyleClass().add("body-label");
        getChildren().addAll(caption, display, startPause, up, down, minutesSpinner, reset, sound);
        refreshDisplay();
    }

    private Button themed(String text, String tip) {
        Button b = new Button(text);
        b.getStyleClass().add("dnd-button");
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    public void startCountUp() {
        publish(false, true);
        countdownId = null;
        countingDown = false;
        elapsedSeconds = 0;
        start();
    }

    public void startCountDown(int seconds) {
        countingDown = true;
        elapsedSeconds = Math.max(0, seconds);
        countdownId = java.util.UUID.randomUUID().toString();
        start();
        publish(true, false);
    }

    private void start() {
        finalBeep.stop();
        running = true;
        startPause.setText("Pause");
        display.getStyleClass().remove("timer-expired");
        ticker.playFromStart();
        refreshDisplay();
    }

    public void toggle() {
        if (running) {
            running = false;
            ticker.pause();
            startPause.setText("Resume");
        } else {
            running = true;
            ticker.play();
            startPause.setText("Pause");
        }
        publish(false, false);
    }

    public void reset() {
        publish(false, true);
        countdownId = null;
        countingDown = false;
        finalBeep.stop();
        running = false;
        ticker.stop();
        elapsedSeconds = 0;
        startPause.setText("Start");
        display.getStyleClass().remove("timer-expired");
        refreshDisplay();
    }

    /** Stops the underlying animation so a closed window doesn't leave a timer running. */
    public void dispose() {
        publish(false, true);
        countdownId = null;
        finalBeep.stop();
        ticker.stop();
    }

    private void tick() {
        if (countingDown) {
            boolean wasPositive = elapsedSeconds > 0;
            elapsedSeconds = Math.max(0, elapsedSeconds - 1);
            if (wasPositive && elapsedSeconds <= 10) beep();
            if (elapsedSeconds == 0) {
                if (wasPositive && sound.isSelected()) finalBeep.playFromStart();
                running = false;
                ticker.stop();
                startPause.setText("Start");
                if (!display.getStyleClass().contains("timer-expired")) {
                    display.getStyleClass().add("timer-expired");
                }
            }
        } else {
            elapsedSeconds++;
        }
        refreshDisplay();
        publish(false, false);
    }

    private void publish(boolean start, boolean clear) {
        if (campaign == null || countdownId == null) return;
        var host = com.dnd.lan.LanSessionServer.current();
        if (host != null) host.publishCountdown(campaign, countdownId, clear ? -1 : elapsedSeconds,
            running, sound.isSelected(), start);
    }

    private void beep() {
        if (sound.isSelected()) CountdownTone.play();
    }

    private void refreshDisplay() {
        display.setText(format(elapsedSeconds));
    }

    /** Formats a whole number of seconds as mm:ss, or h:mm:ss once it passes an hour. */
    public static String format(int totalSeconds) {
        int safe = Math.max(0, totalSeconds);
        int hours = safe / 3600;
        int minutes = (safe % 3600) / 60;
        int seconds = safe % 60;
        if (hours > 0) return String.format("%d:%02d:%02d", hours, minutes, seconds);
        return String.format("%02d:%02d", minutes, seconds);
    }

    public int getElapsedSeconds() {
        return elapsedSeconds;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isCountingDown() {
        return countingDown;
    }
}
