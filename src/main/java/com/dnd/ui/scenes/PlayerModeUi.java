package com.dnd.ui.scenes;

import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import javafx.event.Event;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.util.ArrayList;
import java.util.Collections;

/** Explicit role transitions; this is a UI privacy boundary, not a filesystem sandbox. */
public final class PlayerModeUi {
    private PlayerModeUi() { }

    static void enterPlayer(BaseScene owner) {
        if (!owner.isCurrentRole()) return;
        UiSession session = owner.uiSession;
        if (session.isHosting()) {
            owner.showInfo("Player Mode", "Stop hosting before switching to Player Mode.");
            return;
        }
        if (!ready(owner)) return;
        if (session.isDm()) {
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Switch to Player Mode? GM windows will close using their normal save/discard prompts."
                    + " Private-file keys will lock and campaign selections will clear."
                    + " Join the current GM's session to play. This does not revoke your campaign membership.",
                ButtonType.OK, ButtonType.CANCEL);
            owner.styleDialog(confirmation);
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        if (!closeSecondaryWindows(session.primaryStage())) {
            owner.showInfo("Player Mode", "Role switch cancelled because a window is still open.");
            return;
        }
        if (!ready(owner)) return;
        session.enterPlayerMode();
        session.getRouter().goTo(SceneType.PLAYER_ONLINE_SESSION);
    }

    static void enterGm(BaseScene owner) {
        if (!owner.isCurrentRole()) return;
        UiSession session = owner.uiSession;
        if (!ready(owner)) return;
        if (!session.isDm() && !closeSecondaryWindows(session.primaryStage())) {
            owner.showInfo("Player Mode", "Role switch cancelled because a window is still open.");
            return;
        }
        session.enterGmMode();
        session.getRouter().goTo(SceneType.CAMPAIGN_SELECTION);
    }

    private static boolean ready(BaseScene owner) {
        if (!owner.uiSession.hasBackgroundActions()) return true;
        owner.showInfo("Player Mode", "Wait for pending actions before switching roles.");
        return false;
    }

    static boolean closeSecondaryWindows(Stage primary) {
        var windows = new ArrayList<>(Window.getWindows());
        Collections.reverse(windows);
        for (Window window : windows) {
            if (window == primary || !window.isShowing()) continue;
            if (window instanceof Stage stage) {
                WindowEvent request = new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST);
                Event.fireEvent(stage, request);
                if (stage.isShowing() && request.isConsumed()) return false;
                stage.close();
            } else window.hide();
        }
        return Window.getWindows().stream().noneMatch(window -> window != primary && window.isShowing());
    }

    public static HBox banner(BaseScene owner) {
        return new HBox(12, owner.body("Player Mode - GM tools and private files are locked."),
            owner.btn("Choose role", () -> owner.uiSession.getRouter().goTo(SceneType.LANDING)));
    }
}
