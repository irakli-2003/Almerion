package com.dnd.ui.scenes;

import com.dnd.cli.core.CampaignContext;
import com.dnd.cloud.Cloud;
import com.dnd.cloud.CampaignAccess;
import com.dnd.cloud.CampaignAccess.Entry;
import com.dnd.cloud.CampaignAccess.LocalCampaign;
import com.dnd.cloud.CampaignDirectory;
import com.dnd.cloud.CampaignRecord;
import com.dnd.sync.CampaignRepo;
import com.dnd.sync.CampaignSyncService;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists only the campaigns the signed-in account is a Game Master of. Campaign folders on
 * this device that nobody has registered yet can be claimed; ones registered to other people
 * are not shown at all.
 */
public class CampaignSelectionScene extends BaseScene {
    private final CampaignDirectory directory = CampaignDirectory.shared();
    /** Folders of campaigns that sync with GitHub; filled in the background with the list. */
    private final java.util.Set<String> syncedFolders = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public CampaignSelectionScene(UiSession uiSession) { super(uiSession); }

    private String user() {
        return uiSession.getCurrentUser() == null ? "" : uiSession.getCurrentUser().getUsername();
    }

    @Override
    public Scene build() {
        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(SceneType.LANDING));

        VBox content = new VBox(14);
        content.setPadding(new Insets(30, 60, 40, 60));
        content.getChildren().addAll(
            title("Campaign Selection"),
            body("Your campaigns as Game Master. Players join from the Player side with a join code.")
        );

        Label status = body("Loading campaigns...");
        ListView<Entry> mine = com.dnd.ui.i18n.UiTranslator.skip(new ListView<>());
        mine.getStyleClass().add("dnd-list-view");
        mine.setPrefHeight(200);
        mine.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Entry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                String role = item.record().isAuthor(user()) ? "author" : "co-GM";
                int players = (int) item.record().members.values().stream()
                    .filter(m -> m.role == CampaignRecord.Role.PLAYER).count();
                int pending = item.record().applications.size();
                setText(item.local().folder() + "   (" + role + ", " + players + " player" + (players == 1 ? "" : "s")
                    + (pending > 0 ? ", " + pending + " waiting to join" : "") + ")"
                    + (syncedFolders.contains(item.local().folder()) ? "   · GitHub" : ""));
            }
        });

        Button openBtn = btn("Open Campaign", () -> {
            Entry selected = mine.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            Runnable enter = () -> {
                uiSession.getSession().setCampaignContext(
                    new CampaignContext(selected.local().folder(), selected.local().path()));
                uiSession.getRouter().goTo(SceneType.DM_MENU);
            };
            Runnable open = () -> GitHubSyncUi.reviewSetAsideIfAny(this, selected.local().folder(),
                selected.local().path(), enter);
            if (!syncedFolders.contains(selected.local().folder())) {
                open.run();
                return;
            }
            // Fetch the other computer's changes before any campaign file is loaded.
            status.setText("Getting the latest changes from GitHub...");
            status.setManaged(true);
            mine.setDisable(true);
            CampaignSyncService.shared().syncNow(selected.local().path()).thenAccept(result ->
                javafx.application.Platform.runLater(() -> {
                    if (result.outcome() == CampaignRepo.Outcome.CONFLICT) {
                        GitHubSyncUi.resolveConflicts(this, selected.local().folder(), selected.local().path(),
                            result, open);
                        return;
                    }
                    open.run();
                }));
        });
        mine.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2
                    && mine.getSelectionModel().getSelectedItem() != null) {
                openBtn.fire();
            }
        });

        Button createBtn = btn("Create New Campaign", () -> uiSession.getRouter().goTo(SceneType.CREATE_CAMPAIGN));
        Button deleteBtn = dangerBtn("Delete Selected", () -> {
            Entry selected = mine.getSelectionModel().getSelectedItem();
            if (selected != null) confirmDelete(selected);
        });

        VBox unclaimedBox = new VBox(8);
        VBox elsewhereBox = new VBox(6);

        Runnable reload = () -> uiSession.getRouter().goTo(SceneType.CAMPAIGN_SELECTION);
        Button syncBtn = btn("Sync with GitHub...", () -> {
            Entry selected = mine.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showInfo("Pick a campaign", "Select one of your campaigns in the list first.");
                return;
            }
            GitHubSyncUi.configureCampaign(this, selected.local().folder(), selected.local().path(), reload);
        });
        Button getBtn = btn("Get Campaign from GitHub...",
            () -> GitHubSyncUi.downloadCampaign(this, uiSession.getStorage(), reload));
        FlowPane githubRow = new FlowPane(12, 8, syncBtn, getBtn, GitHubSyncUi.accountBar(this, reload));
        githubRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        content.getChildren().addAll(status, mine, new HBox(12, openBtn, createBtn, deleteBtn), githubRow,
            unclaimedBox, elsewhereBox);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        root.getChildren().add(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        List<LocalCampaign> local = new ArrayList<>();
        for (String folder : uiSession.getStorage().listCustomCampaigns()) {
            local.add(LocalCampaign.of(folder, uiSession.getStorage().resolveCustomCampaignPath(folder)));
        }
        background(() -> {
            for (LocalCampaign campaign : local) {
                if (CampaignRepo.isSynced(campaign.path())) syncedFolders.add(campaign.folder());
            }
            return CampaignAccess.classify(local, user(), directory);
        }, listing -> {
            mine.getItems().setAll(listing.mine());
            status.setText(listing.mine().isEmpty()
                ? "You aren't a Game Master of any campaign on this device yet."
                : (Cloud.isOffline() ? "Offline - showing the campaign list saved from your last connection." : ""));
            status.setManaged(!status.getText().isEmpty());
            fillUnclaimed(unclaimedBox, listing.unclaimed());
            fillElsewhere(elsewhereBox, listing.elsewhere());
        }, error -> status.setText(friendlyError(error)));

        return wrapInScene(root);
    }

    private void fillUnclaimed(VBox box, List<LocalCampaign> unclaimed) {
        box.getChildren().clear();
        if (unclaimed.isEmpty()) return;
        box.getChildren().addAll(sectionLabel("On this device, not registered to anyone"),
            body("Claim a campaign to become its author GM. Only you will see it until you add players or GMs."));
        for (LocalCampaign campaign : unclaimed) {
            Button claim = btn("Claim", () -> background(
                () -> CampaignAccess.claim(campaign, user(), directory),
                record -> uiSession.getRouter().goTo(SceneType.CAMPAIGN_SELECTION),
                error -> showError("Couldn't claim \"" + campaign.folder() + "\"", error)));
            Label name = body(campaign.folder());
            HBox row = new HBox(12, claim, name);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            box.getChildren().add(row);
        }
    }

    private void fillElsewhere(VBox box, List<CampaignRecord> elsewhere) {
        box.getChildren().clear();
        if (elsewhere.isEmpty()) return;
        box.getChildren().add(sectionLabel("Yours, but not on this device"));
        for (CampaignRecord record : elsewhere) {
            box.getChildren().add(body("• " + record.name
                + " - copy its folder into this device's campaigns folder to run it here."));
        }
    }

    private void confirmDelete(Entry selected) {
        if (!selected.record().isAuthor(user())) {
            showInfo("Only the author can delete this campaign",
                selected.record().author + " created \"" + selected.local().folder() + "\". Ask them to delete it.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete campaign '" + selected.local().folder() + "'? Its files and its member list are removed. This cannot be undone.",
            ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Confirm Delete");
        styleDialog(confirm);
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> background(() -> {
            directory.delete(selected.record().id, user());
            return true;
        }, ok -> {
            uiSession.getStorage().deleteCampaign(selected.local().folder());
            uiSession.getRouter().goTo(SceneType.CAMPAIGN_SELECTION);
        }, error -> showError("Couldn't delete the campaign", error)));
    }
}
