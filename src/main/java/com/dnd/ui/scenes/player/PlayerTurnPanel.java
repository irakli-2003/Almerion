package com.dnd.ui.scenes.player;

import com.dnd.data.BattleTurns;
import com.dnd.data.SessionSnapshot;
import com.dnd.ui.UiSession;
import com.dnd.ui.i18n.UiTranslator;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.util.Duration;

final class PlayerTurnPanel extends Label {
    private String snapshot;
    private BattleTurns.Hint onlineHint;

    PlayerTurnPanel(UiSession session, String playerId) {
        getStyleClass().add("section-label");
        setWrapText(true);
        UiTranslator.skip(this);
        setVisible(false);
        managedProperty().bind(visibleProperty());
        var updates = new Timeline(new KeyFrame(Duration.millis(350), event -> update(session, playerId)));
        updates.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((obs, previous, next) -> {
            updates.stop();
            if (next != null) {
                next.windowProperty().addListener((window, old, current) -> {
                    if (getScene() != next) return;
                    if (current == null) updates.stop();
                    else {
                        update(session, playerId);
                        updates.play();
                    }
                });
                if (next.getWindow() != null) updates.play();
                update(session, playerId);
            }
        });
    }

    private void update(UiSession session, String playerId) {
        var sync = session.getSession().getFirebaseSync();
        BattleTurns.Hint hint;
        if (sync != null && !sync.isHost()) {
            String next = sync.getLatestSnapshot();
            if (next != snapshot) {
                snapshot = next;
                try {
                    onlineHint = SessionSnapshot.turnOf(next, playerId);
                } catch (com.dnd.data.DataAccessException e) {
                    onlineHint = null;
                    java.util.logging.Logger.getLogger(PlayerTurnPanel.class.getName())
                        .log(java.util.logging.Level.WARNING, "Could not read the online turn update.", e);
                }
            }
            hint = onlineHint;
        } else {
            var battle = BattleTurns.view(session.campaignRoot());
            hint = battle == null ? null : battle.forPlayer(playerId);
        }
        setVisible(hint != null);
        setText(hint == null ? "" : switch (hint.remaining()) {
            case 0 -> UiTranslator.text("Your turn!");
            case 1 -> UiTranslator.text("My turn in 1 turn");
            default -> UiTranslator.text("My turn in {A} turns").replace("{A}", Integer.toString(hint.remaining()));
        });
    }
}
