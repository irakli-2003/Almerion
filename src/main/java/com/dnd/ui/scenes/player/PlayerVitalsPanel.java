package com.dnd.ui.scenes.player;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.Progression;
import com.dnd.model.magic.CastingResource;
import com.dnd.model.magic.SpellSlots;
import com.dnd.model.world.map.ActiveEffect;
import com.dnd.ui.UiSession;
import com.dnd.ui.i18n.UiTranslator;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.Objects;
import java.util.stream.Collectors;

final class PlayerVitalsPanel extends VBox {
    private final Label status = new Label();
    private final Label vitals = new Label();
    private final Label resources = new Label();
    private final Label effects = new Label();

    PlayerVitalsPanel(UiSession session, CampaignRepositories repos, String id) {
        super(5);
        for (Label label : new Label[]{status, vitals, resources, effects}) {
            label.setWrapText(true);
            label.getStyleClass().add("body-text");
            label.visibleProperty().bind(label.textProperty().isNotEmpty());
            label.managedProperty().bind(label.visibleProperty());
            UiTranslator.skip(label);
        }
        getChildren().addAll(status, vitals, resources, effects);
        var sync = session.getSession().getFirebaseSync();
        var username = session.isLoggedIn() ? session.getCurrentUser().getUsername() : null;
        setCharacter(availableCharacter(repos, id, username), repos);
        if (sync == null || sync.isHost()) return;

        Runnable refresh = new Runnable() {
            private String applied = sync.getAppliedSnapshot();
            @Override public void run() {
                if (session.getSession().getFirebaseSync() != sync) {
                    setCharacter(null, repos);
                    status.setText(UiTranslator.text("Live session disconnected."));
                    return;
                }
                String next = sync.getAppliedSnapshot();
                if (next != applied) {
                    setCharacter(availableCharacter(repos, id, username), repos);
                    applied = next;
                }
                String received = sync.getLatestSnapshot();
                status.setText(UiTranslator.text(received == null ? "Not live - showing last received values."
                    : !received.equals(applied) ? "Waiting for the latest character update."
                    : "Live character status"));
            }
        };
        Timeline updates = new Timeline(new KeyFrame(Duration.millis(350), event -> refresh.run()));
        updates.setCycleCount(Timeline.INDEFINITE);
        ChangeListener<Window> windowChanged = (obs, old, next) -> {
            updates.stop();
            if (next != null) {
                refresh.run();
                updates.play();
            }
        };
        sceneProperty().addListener((obs, old, next) -> {
            updates.stop();
            if (old != null) old.windowProperty().removeListener(windowChanged);
            if (next != null) {
                next.windowProperty().addListener(windowChanged);
                windowChanged.changed(next.windowProperty(), null, next.getWindow());
            }
        });
        refresh.run();
    }

    static PlayerCharacter availableCharacter(CampaignRepositories repos, String id, String username) {
        if (id == null) return null;
        PlayerCharacter pc = repos.players().getById(id);
        return pc != null && (pc.getOwnerUsername() == null || pc.getOwnerUsername().isBlank()
            || pc.isOwnedBy(username)) ? pc : null;
    }

    void setCharacter(PlayerCharacter pc, CampaignRepositories repos) {
        if (pc == null) {
            vitals.setText(UiTranslator.text("Character no longer available. Go back to choose a character."));
            resources.setText("");
            effects.setText("");
            return;
        }
        String health = "HP: " + pc.getCurrentHitPoints() + " / " + pc.getMaxHitPoints();
        if (pc.getMaxMana() > 0) {
            health += "  |  " + UiTranslator.text("Mana") + ": " + pc.getCurrentMana() + " / " + pc.getMaxMana();
        }
        vitals.setText(health);
        var resource = Progression.resourceOf(pc, repos.classes().getById(pc.getClassId()));
        String slots = SpellSlots.describe(pc.getSpellSlots(), resource);
        resources.setText(slots.isBlank() ? "" : UiTranslator.text(resource == CastingResource.ENERGY_CHARGES
            ? "Energy charges" : "Spell slots") + ": " + slots);
        String active = pc.getActiveEffects().stream().filter(Objects::nonNull)
            .map(ActiveEffect::toString).collect(Collectors.joining(", "));
        effects.setText(UiTranslator.text("Active effects") + ": " + (active.isBlank() ? UiTranslator.text("None") : active));
    }
}
