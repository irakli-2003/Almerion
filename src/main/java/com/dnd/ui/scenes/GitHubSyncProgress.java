package com.dnd.ui.scenes;

import com.dnd.sync.CampaignSyncService;
import com.dnd.ui.i18n.UiTranslator;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class GitHubSyncProgress extends HBox {
    private static final long QUIET_SECONDS = 30;

    GitHubSyncProgress(Path campaign, Button sync) {
        this(campaign, sync.visibleProperty(), false);
    }

    public GitHubSyncProgress(Path campaign) {
        this(campaign, new SimpleBooleanProperty(true), true);
    }

    private GitHubSyncProgress(Path campaign, ObservableBooleanValue enabled, boolean closeDialog) {
        super(6);
        var service = CampaignSyncService.shared();
        setId("github-sync-progress");
        setAlignment(Pos.CENTER_LEFT);
        setVisible(false);
        managedProperty().bind(visibleProperty());
        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(closeDialog ? 120 : 54);
        bar.managedProperty().bind(bar.visibleProperty());
        Label label = new Label();
        label.setMaxWidth(240);
        Tooltip tooltip = new Tooltip();
        label.setTooltip(tooltip);
        getChildren().addAll(bar, label);

        Runnable refresh = () -> {
            if (campaign == null || !enabled.get()) {
                setVisible(false);
                return;
            }
            var progress = service.progress(campaign).orElse(null);
            var status = service.status(campaign).orElse(null);
            boolean queued = service.isQueued(campaign);
            boolean working = progress != null || queued;
            setVisible(closeDialog || working || (status != null && status.state() != CampaignSyncService.State.SYNCING));
            bar.setVisible(closeDialog || working);
            if (progress != null) {
                long idle = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - progress.lastActivityNanos());
                String phase = UiTranslator.text(progress.phase());
                boolean quiet = idle >= QUIET_SECONDS;
                double fraction = progress.fraction();
                bar.setProgress(quiet && fraction < 0 ? 0 : fraction);
                String detail = progress.task().isBlank() ? phase : phase + " — " + UiTranslator.text(progress.task());
                if (fraction >= 0) detail += " (" + Math.round(fraction * 100) + "%)";
                String wait = quiet ? UiTranslator.text("No progress reported") + " (" + idle + "s)" : "";
                label.setText(quiet ? wait : detail);
                tooltip.setText(quiet ? detail + "\n" + wait : detail);
            } else if (queued) {
                bar.setProgress(0);
                label.setText(UiTranslator.text("Sync queued"));
                tooltip.setText(UiTranslator.text("Waiting for another campaign operation to finish."));
            } else if (closeDialog) {
                bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                label.setText(UiTranslator.text("Preparing sync"));
                tooltip.setText(label.getText());
            } else if (status != null) {
                String text = switch (status.state()) {
                    case SYNCED -> status.message().equals("Synced with GitHub") ? "Synced" : "Remote has newer changes";
                    case OFFLINE -> "Offline";
                    case CONFLICT -> "Sync conflict";
                    case ERROR -> "Sync failed";
                    case SYNCING -> "";
                };
                label.setText(UiTranslator.text(text));
                tooltip.setText(UiTranslator.text(status.message()));
            }
        };
        Timeline timer = new Timeline(new KeyFrame(Duration.millis(500), event -> refresh.run()));
        timer.setCycleCount(Timeline.INDEFINITE);
        var showing = sceneProperty().flatMap(Scene::windowProperty).flatMap(Window::showingProperty).orElse(false);
        Runnable updateTimer = () -> {
            refresh.run();
            if (showing.getValue() && enabled.get()) timer.play();
            else timer.stop();
        };
        showing.addListener((observable, previous, next) -> updateTimer.run());
        enabled.addListener((observable, previous, next) -> updateTimer.run());
    }
}
