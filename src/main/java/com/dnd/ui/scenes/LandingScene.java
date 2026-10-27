package com.dnd.ui.scenes;

import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import com.dnd.ui.i18n.LanguagePicker;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;

public class LandingScene extends BaseScene {
    public LandingScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = centeredVBox(20);
        root.getStyleClass().add("root");

        root.getChildren().addAll(
            title("⚔  Almerion  ⚔"),
            subtitle(uiSession.isLoggedIn()
                ? "Logged in as " + uiSession.getCurrentUser().getUsername() + " - choose your role to begin"
                : "Choose your role to begin"),
            spacer(),
            btn("Game Master", () -> { uiSession.setDm(true); uiSession.getRouter().goTo(SceneType.CAMPAIGN_SELECTION); }),
            // Players don't pick a campaign: the GM hosts one and their token grants entry to it.
            btn("Player", () -> { uiSession.setDm(false); uiSession.getRouter().goTo(SceneType.PLAYER_ONLINE_SESSION); }),
            spacer(),
            btn("Log Out", () -> { uiSession.setCurrentUser(null); uiSession.getRouter().goTo(SceneType.LOGIN); })
        );
        LanguagePicker.pinTopRight(root);

        return wrapInScene(root);
    }
}
