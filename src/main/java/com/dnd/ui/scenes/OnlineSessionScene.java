package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.SessionSnapshot;
import com.dnd.lan.LanSessionServer;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.security.FirebaseSessionSync;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import com.dnd.ui.i18n.UiTranslator;
import javafx.geometry.Pos;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.IOException;
import java.util.List;

/**
 * "Host Session": shares the campaign with the players, on the local network (browser
 * character sheets, see {@link LanSessionServer}) and/or over the internet (Firebase).
 */
public class OnlineSessionScene extends BaseScene {

    public OnlineSessionScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(SceneType.DM_MENU));

        VBox content = new VBox(16);
        content.setPadding(new Insets(20, 60, 40, 60));
        content.getChildren().addAll(title("Host Session"),
            body("Share the campaign with your players while you run the game. Both ways can run at the same time."));

        VBox lan = section("📶 Local network");
        buildLocalView(lan);
        VBox internet = section("🌐 Over the internet");
        if (uiSession.getSession().isOnline()) {
            buildActiveView(internet);
        } else {
            buildStartView(internet);
        }
        content.getChildren().addAll(lan, internet);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);
        return wrapInScene(root);
    }

    private VBox section(String heading) {
        VBox box = new VBox(10);
        box.getStyleClass().add("dm-card");
        box.setMaxWidth(820);
        box.getChildren().add(sectionLabel(heading));
        return box;
    }

    // ── Local network ───────────────────────────────────────────────────────

    private void buildLocalView(VBox content) {
        var context = uiSession.getSession().getCampaignContext();
        LanSessionServer server = LanSessionServer.current();
        boolean running = server != null && server.isOpen() && context != null
            && server.campaignRoot().equals(context.getPath());
        Label error = body("");
        error.setStyle("-fx-text-fill: #e07070;");

        if (!running) {
            content.getChildren().addAll(
                body("Players on the same Wi-Fi or network open their character sheet in a browser, on a computer,"
                    + " tablet or phone. The sheet is view only and updates by itself when a character takes damage,"
                    + " spends a slot, gains XP or anything else changes."),
                error,
                btn("Start local session", () -> {
                    if (context == null) { error.setText("No campaign selected."); return; }
                    try {
                        LanSessionServer.startShared(context.getPath(), context.getName(), UiTranslator::text);
                    } catch (IOException e) {
                        error.setText("Could not start the local session: " + e.getMessage());
                        return;
                    }
                    uiSession.getRouter().goTo(SceneType.DM_ONLINE_SESSION);
                }));
        } else {
            Label live = body("● Running. Players open one of these addresses in their browser:");
            live.setStyle("-fx-text-fill: #80ff80;");
            VBox addresses = new VBox(6);
            for (String url : server.addresses()) {
                TextField field = new TextField(url);
                field.setEditable(false);
                field.getStyleClass().add("dnd-text-field");
                field.setPrefColumnCount(28);
                Button copy = btn("Copy", () -> {
                    ClipboardContent clip = new ClipboardContent();
                    clip.putString(url);
                    Clipboard.getSystemClipboard().setContent(clip);
                });
                HBox row = new HBox(8, UiTranslator.skip(field), copy);
                row.setAlignment(Pos.CENTER_LEFT);
                addresses.getChildren().add(row);
            }
            content.getChildren().addAll(live, addresses,
                body("If Windows asks, allow Almerion on private networks, or players won't be able to connect."),
                sectionLabel("Character passwords"),
                body("Each player picks their character and types its password. A character without a password can't be opened."));
            CampaignRepositories repos = new CampaignRepositories(context.getPath());
            VBox list = new VBox(6);
            fillPasswords(list, repos);
            content.getChildren().addAll(list, error, dangerBtn("Stop local session", () -> {
                LanSessionServer.stopShared();
                uiSession.getRouter().goTo(SceneType.DM_ONLINE_SESSION);
            }));
        }
    }

    private void fillPasswords(VBox list, CampaignRepositories repos) {
        list.getChildren().clear();
        List<PlayerCharacter> players;
        try {
            players = repos.players().list();
        } catch (RuntimeException e) {
            players = List.of();
        }
        if (players.isEmpty()) list.getChildren().add(body("This campaign has no characters yet."));
        for (PlayerCharacter pc : players) {
            Label name = UiTranslator.skip(body(pc.getName() == null ? pc.getId() : pc.getName()));
            name.setMinWidth(220);
            Label state = body(pc.hasPassword() ? "✔ password set" : "✖ no password");
            state.setStyle(pc.hasPassword() ? "-fx-text-fill: #80ff80;" : "-fx-text-fill: #e0a070;");
            state.setMinWidth(160);
            Button set = btn(pc.hasPassword() ? "Change password" : "Set password", () -> {
                if (askPlayerPassword(pc, repos)) fillPasswords(list, repos);
            });
            HBox row = new HBox(12, name, state, set);
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(row);
        }
    }

    // ── Over the internet ───────────────────────────────────────────────────

    private void buildStartView(VBox content) {
        Label error = body("");
        error.setStyle("-fx-text-fill: #e07070;");

        Button startBtn = btn("Start internet session", () -> { });
        startBtn.setOnAction(event -> {
            var context = uiSession.getSession().getCampaignContext();
            var user = uiSession.getCurrentUser();
            if (context == null) {
                error.setText("No campaign selected.");
                return;
            }

            startBtn.setDisable(true);
            background(() -> {
                String campaignId = com.dnd.cloud.CampaignMeta.idOf(context.getPath()).orElseThrow(
                    () -> new IllegalStateException("Register the campaign before hosting it online."));
                FirebaseSessionSync sync = FirebaseSessionSync.host(campaignId);
                sync.push(SessionSnapshot.capture(context.getPath(), context.getName()));
                try {
                    sync.startChat(context.getPath());
                } catch (RuntimeException failure) {
                    try { sync.delete(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
                return sync;
            }, sync -> {
                if (context != uiSession.getSession().getCampaignContext() || user != uiSession.getCurrentUser()) {
                    background(() -> { sync.delete(); return true; }, ignored -> { },
                        failure -> showError("Could not end the online session. Check your connection and account permissions.", failure));
                    startBtn.setDisable(false);
                    return;
                }
                uiSession.getSession().setFirebaseSync(sync);
                uiSession.setSessionToken(sync.token());
                publishLiveToken(sync.token());
                uiSession.getRouter().goTo(SceneType.DM_ONLINE_SESSION);
            }, failure -> {
                startBtn.setDisable(false);
                error.setText("Could not start the online session: " + failure.getMessage());
            });
        });

        content.getChildren().addAll(
            body("Players anywhere join from Player → My Campaigns, or with a token. All data is AES-256 encrypted,"
                + " and players only receive their characters and what those characters use, never your notes, NPCs or maps."),
            body("The player token is read only and uses a randomly generated encryption key. Only the verified host can publish changes."
                + " Access expires 12 hours after the last push. Keep the token private."),
            error,
            startBtn
        );
    }

    private void buildActiveView(VBox content) {
        Label statusLabel = body("Session is live. Members of this campaign can join it from Player → My Campaigns,"
            + " or with the token below.");
        statusLabel.setStyle("-fx-text-fill: #80ff80;");

        String token = uiSession.getSessionToken();
        TextArea tokenArea = new TextArea(token != null ? token : "(token unavailable)");
        tokenArea.setEditable(false);
        tokenArea.setWrapText(true);
        tokenArea.setPrefRowCount(3);
        tokenArea.getStyleClass().add("dnd-text-field");
        tokenArea.setMaxWidth(700);

        Label error = body("");
        error.setStyle("-fx-text-fill: #e07070;");
        Button stopBtn = dangerBtn("Stop internet session", () -> background(() -> {
            var context = uiSession.getSession().getCampaignContext();
            String id = com.dnd.cloud.CampaignMeta.idOf(context.getPath()).orElseThrow();
            com.dnd.cloud.CampaignDirectory.shared().setLiveToken(id, uiSession.getCurrentUser().getUsername(), null);
            uiSession.getSession().getFirebaseSync().delete();
            return true;
        }, ignored -> {
            uiSession.getSession().setFirebaseSync(null);
            uiSession.setSessionToken(null);
            uiSession.getRouter().goTo(SceneType.DM_ONLINE_SESSION);
        }, failure -> error.setText("Could not finish stopping the session. Retry: " + failure.getMessage())));

        Button pushBtn = btn("Push State Now", () -> {
            background(() -> {
                uiSession.getSession().getFirebaseSync().push(buildSnapshot());
                return true;
            }, ignored -> error.setText(""), failure -> error.setText("Session push failed: " + failure.getMessage()));
        });

        content.getChildren().addAll(
            body("Campaign state is pushed automatically when you navigate back to the GM Menu."),
            statusLabel,
            sectionLabel("Player Token"),
            tokenArea,
            error,
            new HBox(12, pushBtn, stopBtn)
        );
    }

    /**
     * Lets the campaign's members join from their "My Campaigns" list without pasting the
     * token. Best effort: the token shown on screen still works if this can't be published.
     */
    private void publishLiveToken(String token) {
        var context = uiSession.getSession().getCampaignContext();
        String id = context == null ? null : com.dnd.cloud.CampaignMeta.idOf(context.getPath()).orElse(null);
        String user = uiSession.getCurrentUser() == null ? null : uiSession.getCurrentUser().getUsername();
        if (id == null || user == null) return;
        background(() -> {
            com.dnd.cloud.CampaignDirectory.shared().setLiveToken(id, user, token);
            return true;
        }, ok -> { }, error -> showError("Could not update the campaign's live token. Players can use the token shown on screen.", error));
    }

    private String buildSnapshot() {
        // Players get their characters and whatever those use, never the GM's secrets.
        var context = uiSession.getSession().getCampaignContext();
        return SessionSnapshot.capture(context.getPath(), context.getName());
    }
}
