package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
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
        VBox members = section("Campaign membership");
        var context = uiSession.getSession().getCampaignContext();
        String username = chatHostName();
        Button manage = btn("Manage members", () -> {
            if (context != null && username != null) MembersWindow.open(this, context.getPath(), username);
        });
        manage.setDisable(context == null || username == null);
        members.getChildren().addAll(
            body("Invite by account code or manage existing members. Membership stays after the session ends. "
                + "Members can join an internet session from Player → My Campaigns."),
            manage);
        content.getChildren().addAll(lan, internet, members);

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
                        LanSessionServer.startShared(context.getPath(), context.getName(), UiTranslator::text, chatHostName());
                    } catch (IOException e) {
                        error.setText("Could not start the local session: " + e.getMessage());
                        return;
                    }
                    uiSession.getRouter().goTo(SceneType.DM_ONLINE_SESSION);
                }));
        } else {
            Label live = body("● Local session running");
            live.setStyle("-fx-text-fill: #80ff80;");
            VBox addresses = new VBox(6);
            fillJoinAddresses(addresses, server);
            content.getChildren().addAll(live,
                body("Share the link for the host adapter connected to the same network as your players."), addresses,
                new FlowPane(8, 8, btn("Refresh network links", () -> fillJoinAddresses(addresses, server)),
                    btn("Open host preview", () -> openHostPreview(server))),
                body("The host preview only tests this computer. If it opens but phones time out, check that everyone "
                    + "is on the same trusted network, not guest Wi-Fi or a VPN, and allow Almerion through Windows "
                    + "Firewall on private networks."),
                body("These links use HTTP. If a browser changes the address to HTTPS, re-enter the full http:// link. "
                    + "Use local hosting only on a trusted network."),
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

    private void fillJoinAddresses(VBox addresses, LanSessionServer server) {
        addresses.getChildren().clear();
        List<LanSessionServer.JoinAddress> links;
        try {
            links = server.joinAddresses();
        } catch (IOException e) {
            addresses.getChildren().add(body("Could not read the network adapters. Refresh network links to retry."));
            return;
        }
        if (links.isEmpty()) {
            addresses.getChildren().add(body("No shareable IPv4 link found. Connect the host to Wi-Fi or Ethernet and refresh."));
        }
        for (var link : links) {
            TextField field = new TextField(link.url());
            field.setEditable(false);
            field.getStyleClass().add("dnd-text-field");
            field.setPrefColumnCount(28);
            field.setMinWidth(0);
            HBox.setHgrow(field, Priority.ALWAYS);
            Button copy = btn("Copy", () -> {
                ClipboardContent clip = new ClipboardContent();
                clip.putString(link.url());
                if (!Clipboard.getSystemClipboard().setContent(clip)) {
                    showInfo("Could not copy the link", "Select and copy the address from the text field.");
                }
            });
            HBox row = new HBox(8, UiTranslator.skip(field), copy);
            row.setAlignment(Pos.CENTER_LEFT);
            addresses.getChildren().add(new VBox(2, UiTranslator.skip(body(link.adapter())), row));
        }
    }

    private void openHostPreview(LanSessionServer server) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(server.hostPreviewUrl()));
        } catch (IOException | UnsupportedOperationException e) {
            showInfo("Could not open the host preview", UiTranslator.text("Open this address on the host computer:")
                + "\n" + server.hostPreviewUrl());
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
                sync.pushCampaign(context.getPath(), context.getName());
                try {
                    sync.startChat(context.getPath(), chatHostName());
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
                var context = uiSession.getSession().getCampaignContext();
                uiSession.getSession().getFirebaseSync().pushCampaign(context.getPath(), context.getName());
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
    private String chatHostName() {
        return uiSession.getCurrentUser() == null ? null : uiSession.getCurrentUser().getUsername();
    }

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

}
