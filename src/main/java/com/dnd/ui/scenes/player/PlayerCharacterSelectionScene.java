package com.dnd.ui.scenes.player;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import com.dnd.ui.scenes.BaseScene;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;

public class PlayerCharacterSelectionScene extends BaseScene {
    public PlayerCharacterSelectionScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(SceneType.PLAYER_ONLINE_SESSION));

        VBox content = new VBox(16);
        content.setPadding(new Insets(30, 60, 40, 60));

        Label error = body("");
        error.setStyle("-fx-text-fill: #e07070;");

        CampaignRepositories repos = new CampaignRepositories(
            uiSession.getSession().getCampaignContext().getPath());

        // Only characters the DM hasn't already handed to someone else are offered, so this
        // fallback can't be used to walk into another player's sheet.
        String username = uiSession.isLoggedIn() ? uiSession.getCurrentUser().getUsername() : null;
        List<PlayerCharacter> players = repos.players().list().stream()
            .filter(pc -> pc.getOwnerUsername() == null || pc.getOwnerUsername().isBlank() || pc.isOwnedBy(username))
            .toList();

        ListView<String> list = new ListView<>();
        list.getStyleClass().add("dnd-list-view");
        for (PlayerCharacter pc : players) {
            String label = pc.getName() != null && !pc.getName().isEmpty() ? pc.getName() : pc.getId();
            list.getItems().add(label);
        }
        list.setPrefHeight(200);

        PasswordField passField = passwordField("Password (if required)");
        Label passHint = body("Enter password if this character is password protected.");

        Button selectBtn = btn("Play This Character", () -> {
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx < 0) { error.setText("Please select a character."); return; }
            PlayerCharacter pc = players.get(idx);
            if (pc.hasPassword()) {
                if (!pc.checkPassword(passField.getText())) {
                    error.setText("Incorrect password.");
                    return;
                }
            }
            uiSession.getSession().setActivePlayerCharacterId(pc.getId());
            uiSession.getRouter().goTo(SceneType.PLAYER_HOME);
        });

        content.getChildren().addAll(
            title("Choose Your Character"),
            subtitle("Campaign: " + uiSession.getSession().getCampaignContext().getName()),
            body(players.isEmpty()
                ? "No characters are available to you in this session. Ask your GM to assign one to your account."
                : "Your GM hasn't assigned a character to your account yet, so pick the one you're playing."),
            list, passHint, passField, error, selectBtn
        );
        root.getChildren().add(content);
        return wrapInScene(root);
    }
}
