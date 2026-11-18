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
    private final CampaignDirectory directory;
    /** Folders of campaigns that sync with GitHub; filled in the background with the list. */
    private final java.util.Set<String> syncedFolders = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public CampaignSelectionScene(UiSession uiSession) { this(uiSession, CampaignDirectory.shared()); }

    CampaignSelectionScene(UiSession uiSession, CampaignDirectory directory) {
        super(uiSession);
        this.directory = directory;
    }

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
            body("Open a campaign you manage, or use Join Campaign to find your account code and invitations.")
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
                if (!isCurrentRole()) return;
                uiSession.getSession().setCampaignContext(
                    new CampaignContext(selected.local().folder(), selected.local().path()));
                uiSession.getRouter().goTo(SceneType.DM_MENU);
            };
            Runnable open = () -> GitHubSyncUi.reviewSetAsideIfAny(this, selected.local().folder(),
                selected.local().path(), () -> ManualFilesUi.review(this, selected.local().path(), false, enter));
            if (!syncedFolders.contains(selected.local().folder())) {
                open.run();
                return;
            }
            // Fetch the other computer's changes before any campaign file is loaded.
            status.setText("Getting the latest changes from GitHub...");
            status.setManaged(true);
            mine.setDisable(true);
            content.setDisable(true);
            CampaignSyncService.shared().syncNow(selected.local().path()).thenAccept(result ->
                javafx.application.Platform.runLater(() -> {
                    mine.setDisable(false);
                    content.setDisable(false);
                    if (!isCurrentRole()) return;
                    if (result.outcome() == CampaignRepo.Outcome.CONFLICT) {
                        GitHubSyncUi.resolveConflicts(this, selected.local().folder(), selected.local().path(),
                            result, open);
                        return;
                    }
                    if (result.outcome() != CampaignRepo.Outcome.SYNCED) {
                        GitHubSyncUi.showResult(this, selected.local().folder(), selected.local().path(), result, open);
                    } else open.run();
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
        VBox githubGroup = toolGroup("GitHub collaboration", syncBtn, getBtn);
        githubGroup.getChildren().add(GitHubSyncUi.accountBar(this, reload));
        Button exportBtn = btn("Export Complete Campaign...", () -> { });
        exportBtn.setOnAction(e -> {
            Entry selected = mine.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showInfo("Pick a campaign", "Select one of your campaigns in the list first.");
                return;
            }
            CampaignArchiveUi.exportCampaign(this, selected.local().path(), exportBtn, status);
        });
        Button importBtn = btn("Import Complete Campaign...", () -> { });
        importBtn.setOnAction(e -> CampaignArchiveUi.importCampaign(this, uiSession.getStorage(), user(),
            importBtn, status, reload));
        root.disableProperty().bind(exportBtn.disableProperty().or(importBtn.disableProperty()));
        Button restoreBtn = btn("Restore points", () -> {
            Entry selected = mine.getSelectionModel().getSelectedItem();
            RestorePointsUi.show(this, selected == null ? null : selected.local().path(), reload);
        });
        openBtn.disableProperty().bind(mine.getSelectionModel().selectedItemProperty().isNull().or(mine.disableProperty()));
        syncBtn.disableProperty().bind(mine.getSelectionModel().selectedItemProperty().isNull());
        deleteBtn.disableProperty().bind(mine.getSelectionModel().selectedItemProperty().isNull());
        Button invite = btn("Invite to campaign", () -> {
            Entry selected = mine.getSelectionModel().getSelectedItem();
            if (selected != null) MembersWindow.invite(this, selected.record(), user(), () -> {});
        });
        Button members = btn("Manage members", () -> {
            Entry selected = mine.getSelectionModel().getSelectedItem();
            if (selected != null) MembersWindow.open(this, selected.local().path(), user());
        });
        invite.disableProperty().bind(openBtn.disableProperty());
        members.disableProperty().bind(openBtn.disableProperty());
        FlowPane campaignActions = new FlowPane(10, 8, openBtn, createBtn, invite, members,
            btn("Join Campaign", () -> showCampaignInvitations(reload)));
        VBox transferGroup = toolGroup("Backups & transfer", exportBtn, importBtn, restoreBtn);
        VBox management = toolGroup("Campaign management", deleteBtn);
        VBox remoteMemberships = new VBox(8);

        content.getChildren().addAll(status, mine, campaignActions, remoteMemberships, githubGroup, transferGroup, management,
            unclaimedBox);
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
            return CampaignAccess.classifyLocal(local, user(), directory);
        }, listing -> {
            mine.getItems().setAll(listing.mine());
            status.setText(listing.mine().isEmpty()
                ? "You aren't a Game Master of any campaign on this device yet."
                : (Cloud.isOffline() ? "Offline - showing the campaign list saved from your last connection." : ""));
            status.setManaged(!status.getText().isEmpty());
            fillUnclaimed(unclaimedBox, listing.unclaimed());
        }, error -> status.setText(friendlyError(error)));
        background(() -> directory.campaignsOf(user()), records -> {
            var installedIds = local.stream().map(LocalCampaign::id).collect(java.util.stream.Collectors.toSet());
            var remoteGm = records.stream().filter(r -> r.isDm(user()) && !installedIds.contains(r.id)).toList();
            if (remoteGm.isEmpty()) return;
            remoteMemberships.getChildren().addAll(sectionLabel("Campaign memberships without local files"),
                body("You can manage membership here. The campaign files are not on this device."));
            for (CampaignRecord record : remoteGm) {
                remoteMemberships.getChildren().add(new HBox(10, body(record.name),
                    btn("Manage members", () -> MembersWindow.openRegistered(this, record.id, user())),
                    btn("Invite to campaign", () -> MembersWindow.invite(this, record, user(), reload))));
            }
        }, error -> remoteMemberships.getChildren().setAll(body("Couldn't load online memberships: " + friendlyError(error))));

        return wrapInScene(root);
    }

    private void showCampaignInvitations(Runnable reload) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Join Campaign");
        String accountName = user();
        TextField account = textField("");
        account.setText(CampaignDirectory.accountCode(accountName));
        account.setEditable(false);
        Button copy = btn("Copy", () -> {
            var clipboard = new javafx.scene.input.ClipboardContent();
            clipboard.putString(account.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(clipboard);
        });
        VBox invites = new VBox(8, body("Loading invitations..."));
        dialog.getDialogPane().setContent(new VBox(10, body("Share this account code with a GM to receive an invitation."),
            new HBox(8, account, copy), invites));
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        styleDialog(dialog);
        background(() -> directory.invitationsOf(accountName), results -> {
            invites.getChildren().clear();
            if (results.isEmpty()) invites.getChildren().add(body("No pending invitations."));
            for (var invite : results) {
                var name = body(invite.name() + " (" + (invite.role() == CampaignRecord.Role.DM ? "GM" : "Player")
                    + ", invited by " + invite.inviter() + ")");
                java.util.function.Consumer<Boolean> respond = accept -> background(() -> {
                    directory.respondToInvitation(invite.campaignId(), accountName, accept);
                    return true;
                }, result -> {
                    dialog.close();
                    reload.run();
                }, error -> showError("Couldn't respond to invitation", error));
                invites.getChildren().add(new HBox(8, name, btn("Accept", () -> respond.accept(true)),
                    btn("Decline", () -> respond.accept(false))));
            }
        }, error -> invites.getChildren().setAll(body("Couldn't load invitations: " + friendlyError(error))));
        dialog.showAndWait();
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

    private void confirmDelete(Entry selected) {
        if (!selected.record().isAuthor(user())) {
            showInfo("Only the author can delete this campaign",
                selected.record().author + " created \"" + selected.local().folder() + "\". Ask them to delete it.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete campaign '" + selected.local().folder() + "'? Its files and member list are removed."
                + " A local restore point keeps the files, but restoring files does not restore online membership.",
            ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Confirm Delete");
        styleDialog(confirm);
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> background(() -> {
            return CampaignSyncService.shared().localFiles(() -> uiSession.getStorage().deleteCampaign(selected.local().folder(),
                () -> directory.delete(selected.record().id, user()), uiSession.getCurrentUser().getProviderUid())).join();
        }, ok -> uiSession.getRouter().goTo(SceneType.CAMPAIGN_SELECTION),
            error -> showError("Couldn't delete the campaign", error)));
    }
}
