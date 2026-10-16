package com.dnd.ui.scenes.player;

import com.dnd.cli.core.CampaignContext;
import com.dnd.cloud.CampaignDirectory;
import com.dnd.cloud.CampaignRecord;
import com.dnd.data.CampaignRepositories;
import com.dnd.data.SessionSnapshot;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.security.FirebaseSessionSync;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import com.dnd.ui.scenes.BaseScene;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.List;

/**
 * The player's way in: the campaigns this account has been accepted into (joinable while the
 * DM runs a live session), applying to a new one with a join code, and a token fallback.
 * Campaigns the account isn't a member of are never shown or joined.
 */
public class PlayerOnlineSessionScene extends BaseScene {

    public PlayerOnlineSessionScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(SceneType.LANDING));

        VBox content = new VBox(16);
        content.setPadding(new Insets(20, 60, 40, 60));

        if (uiSession.getSession().isOnline()) {
            buildConnectedView(root, content);
        } else {
            buildJoinView(content);
        }

        root.getChildren().add(content);
        return wrapInScene(root);
    }

    private String user() {
        return uiSession.isLoggedIn() ? uiSession.getCurrentUser().getUsername() : "";
    }

    private void buildJoinView(VBox content) {
        CampaignDirectory directory = CampaignDirectory.shared();
        Label error = body("");
        error.setStyle("-fx-text-fill: #e07070;");

        VBox mine = new VBox(8, body("Loading your campaigns..."));
        VBox pending = new VBox(6);

        TextField codeField = textField("Join code, e.g. K7QM2X");
        codeField.setMaxWidth(260);
        Runnable apply = () -> {
            String code = codeField.getText().trim();
            if (code.isEmpty()) { error.setText("Enter the join code your DM gave you."); return; }
            error.setText("");
            background(() -> directory.apply(code, user()), record -> {
                codeField.clear();
                showInfo("Application sent",
                    "You asked to join \"" + record.name + "\". Once a DM accepts you it appears under My Campaigns.");
                uiSession.getRouter().goTo(SceneType.PLAYER_ONLINE_SESSION);
            }, failure -> error.setText(friendlyError(failure)));
        };
        codeField.setOnAction(e -> apply.run());

        TextField tokenField = textField("Paste session token from DM");
        tokenField.setMaxWidth(600);
        tokenField.setOnAction(e -> join(tokenField.getText().trim(), error));
        TitledPane tokenPane = new TitledPane("Join with a session token instead",
            new VBox(8, tokenField, btn("Join Session", () -> join(tokenField.getText().trim(), error))));
        tokenPane.setExpanded(false);
        tokenPane.setMaxWidth(640);

        content.getChildren().addAll(
            title("My Campaigns"),
            body("Campaigns you've been accepted into. When the DM starts a live session, join it from here."),
            mine, error,
            sectionLabel("Join a Campaign"),
            body("Ask your DM for the campaign's join code. They have to accept you before you can see it."),
            new HBox(10, codeField, btn("Apply", apply)),
            pending,
            tokenPane
        );

        background(() -> new Object[] {directory.campaignsOf(user()), directory.pendingApplicationsOf(user())}, lists -> {
            @SuppressWarnings("unchecked") List<CampaignRecord> member = (List<CampaignRecord>) lists[0];
            @SuppressWarnings("unchecked") List<CampaignRecord> waiting = (List<CampaignRecord>) lists[1];
            mine.getChildren().clear();
            if (member.isEmpty()) mine.getChildren().add(body("You aren't part of any campaign yet."));
            for (CampaignRecord record : member) {
                boolean live = record.liveToken != null && !record.liveToken.isBlank();
                Label name = body(record.name + "   (" + (record.isDm(user()) ? "DM" : "Player")
                    + (live ? ", ● live now" : ", not running") + ")");
                if (live) name.setStyle("-fx-text-fill: #80ff80;");
                Button joinBtn = btn("Join Live Session", () -> join(record.liveToken, error));
                joinBtn.setDisable(!live);
                Button leave = dangerBtn("Leave", () -> background(() -> {
                    directory.leave(record.id, user());
                    return true;
                }, ok -> uiSession.getRouter().goTo(SceneType.PLAYER_ONLINE_SESSION),
                    failure -> showError("Couldn't leave \"" + record.name + "\"", failure)));
                leave.setVisible(!record.isAuthor(user()));
                HBox row = new HBox(10, joinBtn, leave, name);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                mine.getChildren().add(row);
            }
            pending.getChildren().clear();
            if (!waiting.isEmpty()) {
                pending.getChildren().add(sectionLabel("Waiting for a DM to accept you"));
                for (CampaignRecord record : waiting) pending.getChildren().add(body("• " + record.name));
            }
            if (com.dnd.cloud.Cloud.isOffline()) {
                error.setText("Offline - showing the list saved from your last connection.");
            }
        }, failure -> {
            mine.getChildren().setAll(body(friendlyError(failure)));
        });
    }

    private void join(String token, Label error) {
        if (token == null || token.isEmpty()) { error.setText("Token cannot be empty."); return; }

        FirebaseSessionSync sync;
        try {
            sync = FirebaseSessionSync.fromToken(token);
        } catch (IllegalArgumentException e) {
            error.setText("Invalid token: " + e.getMessage());
            return;
        }

        String json;
        try {
            json = sync.pull();
        } catch (Exception e) {
            error.setText("Connection failed: " + e.getMessage());
            return;
        }
        if (json == null) {
            error.setText("Session not started yet. Ask your DM to start the session.");
            return;
        }

        String refusal = membershipProblem(SessionSnapshot.campaignIdOf(json));
        if (refusal != null) {
            error.setText(refusal);
            return;
        }
        Path campaignRoot;
        try {
            campaignRoot = SessionSnapshot.hydrate(json, sync.getSessionId());
        } catch (RuntimeException e) {
            error.setText("Could not open the session data: " + e.getMessage());
            return;
        }

        uiSession.getSession().setCampaignContext(
            new CampaignContext(SessionSnapshot.campaignNameOf(json), campaignRoot));
        uiSession.getSession().setFirebaseSync(sync);
        uiSession.setSessionToken(token);

        // A later push from the DM refreshes the local copy in place, so the player sees
        // the updated campaign without having to rejoin.
        sync.startListening(update -> Platform.runLater(() -> {
            try {
                SessionSnapshot.hydrate(update, sync.getSessionId());
            } catch (RuntimeException ignored) {
                // A malformed update just leaves the last good copy in place.
            }
        }));

        uiSession.getRouter().goTo(destinationFor(campaignRoot));
    }

    /**
     * Why this account may not join the campaign in a snapshot, or null when it may. Only
     * accepted members get in; a campaign the DM never registered can't be joined at all.
     */
    private String membershipProblem(String campaignId) {
        if (campaignId == null) {
            return "This campaign isn't registered yet. Ask your DM to claim it in Campaign Selection and give you its join code.";
        }
        try {
            CampaignRecord record = CampaignDirectory.shared().find(campaignId).orElse(null);
            if (record == null) return "This campaign isn't registered any more. Ask your DM.";
            if (!record.isMember(user())) {
                return "You aren't part of \"" + record.name + "\". Apply with its join code and wait for a DM to accept you.";
            }
            return null;
        } catch (RuntimeException e) {
            return friendlyError(e);
        }
    }

    /**
     * Sends the player straight to their own character when the DM has assigned them one,
     * and only falls back to the picker when the roster can't say who they are.
     */
    private SceneType destinationFor(Path campaignRoot) {
        String username = uiSession.isLoggedIn() ? uiSession.getCurrentUser().getUsername() : null;
        if (username != null) {
            List<PlayerCharacter> roster = new CampaignRepositories(campaignRoot).players().list();
            for (PlayerCharacter pc : roster) {
                if (pc.isOwnedBy(username)) {
                    uiSession.getSession().setActivePlayerCharacterId(pc.getId());
                    return SceneType.PLAYER_HOME;
                }
            }
        }
        return SceneType.PLAYER_CHARACTER_SELECTION;
    }

    private void buildConnectedView(VBox root, VBox content) {
        HBox notifBar = new HBox();
        notifBar.getStyleClass().add("notification-bar");
        Label notifLabel = new Label("● Connected — updates arrive automatically from the DM");
        notifLabel.getStyleClass().add("notification-label");
        notifBar.getChildren().add(notifLabel);
        root.getChildren().add(1, notifBar);

        var context = uiSession.getSession().getCampaignContext();

        content.getChildren().addAll(
            title("Live Session"),
            body("Campaign: " + (context != null ? context.getName() : "unknown")),
            body("Your DM is hosting this session. Your character sheet follows what they push; "
                + "your portrait, name and description are yours and stay on this machine."),
            btn("Go to My Character", () -> uiSession.getRouter().goTo(
                uiSession.getSession().getActivePlayerCharacterId() != null
                    ? SceneType.PLAYER_HOME
                    : SceneType.PLAYER_CHARACTER_SELECTION)),
            dangerBtn("Leave Session", () -> {
                try { uiSession.getSession().getFirebaseSync().stopListening(); } catch (Exception ignored) {}
                uiSession.getSession().setFirebaseSync(null);
                uiSession.getSession().setActivePlayerCharacterId(null);
                uiSession.setSessionToken(null);
                uiSession.getRouter().goTo(SceneType.PLAYER_ONLINE_SESSION);
            })
        );
    }
}
