package com.dnd.ui.scenes;

import com.dnd.cloud.CampaignDirectory;
import com.dnd.cloud.CampaignMeta;
import com.dnd.cloud.CampaignRecord;
import com.dnd.cloud.Cloud;
import com.dnd.cloud.CloudProfiles;
import com.dnd.data.CharacterNotes;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.security.PrivateAccount;
import com.dnd.ui.i18n.UiTranslator;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Encrypted notes are kept outside the character and never sent to player clients. */
final class CharacterNotesUi {
    private record Opened(CharacterNotes store, CharacterNotes.Loaded loaded) { }

    private CharacterNotesUi() { }

    static void open(BaseScene owner, PlayerCharacter character) {
        if (!owner.uiSession.isDm()) throw new SecurityException("Only a GM can open character notes.");
        Path root = owner.uiSession.campaignRoot();
        if (root == null || character.getId() == null) throw new IllegalStateException("Open a saved character first.");
        String campaign = CampaignMeta.idOf(root).orElseThrow(
            () -> new IllegalStateException("Register the campaign before using shared GM notes."));
        PrivateFilesUi.unlocked(owner, () -> {
            PrivateAccount account = owner.uiSession.getPrivateAccount();
            owner.background(() -> {
                List<PrivateAccount.Identity> recipients = recipients(owner, account, campaign, false);
                CharacterNotes notes = new CharacterNotes(root, campaign, account);
                return new Opened(notes, notes.read(character.getId(), recipients));
            }, opened -> edit(owner, character, root, campaign, account, opened),
                error -> owner.showError("Could not open GM character note", error));
        });
    }

    private static void edit(BaseScene owner, PlayerCharacter character, Path root, String campaign,
                             PrivateAccount account, Opened opened) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("GM character note");
        dialog.setHeaderText("Encrypted for campaign GMs. Not included in player sheets, PDFs or hosted sessions."
            + " Reading works offline after setup; saving requires online membership verification."
            + " Changes reach other GMs through campaign sync.");
        TextArea text = UiTranslator.skip(new TextArea(opened.loaded().text()));
        text.setWrapText(true);
        text.setPrefColumnCount(70);
        text.setPrefRowCount(20);
        dialog.getDialogPane().setContent(text);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        owner.styleDialog(dialog);
        var saveButton = dialog.getDialogPane().lookupButton(save);
        var cancelButton = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        dialog.setOnCloseRequest(event -> { if (saveButton.isDisabled()) event.consume(); });
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (!owner.uiSession.isDm() || !root.equals(owner.uiSession.campaignRoot())
                || account != owner.uiSession.getPrivateAccount()) {
                owner.showError("Could not save GM character note", new SecurityException("The GM context changed."));
                return;
            }
            String draft = text.getText();
            saveButton.setDisable(true);
            cancelButton.setDisable(true);
            owner.background(() -> {
                List<PrivateAccount.Identity> recipients = recipients(owner, account, campaign, true);
                opened.store().save(character.getId(), opened.loaded(), draft, recipients);
                return true;
            }, ignored -> {
                saveButton.setDisable(false);
                dialog.setResult(null);
                dialog.close();
            }, error -> {
                saveButton.setDisable(false);
                cancelButton.setDisable(false);
                owner.showError("Could not save GM character note", error);
            });
        });
        dialog.showAndWait();
    }

    static List<PrivateAccount.Identity> recipients(BaseScene owner, PrivateAccount account, String campaign,
                                                     boolean saving) {
        if (saving) requireOnline();
        CampaignRecord record = CampaignDirectory.shared().find(campaign).orElseThrow(
            () -> new SecurityException("The campaign membership is unavailable. Connect and open the campaign first."));
        if (saving) requireOnline();
        if (owner.uiSession.getCurrentUser() == null || !record.isDm(owner.uiSession.getCurrentUser().getUsername())) {
            throw new SecurityException("Only a campaign GM may open these notes.");
        }
        CloudProfiles profiles = new CloudProfiles(Cloud.store());
        List<PrivateAccount.Identity> identities;
        if (!saving && Cloud.isOffline()) {
            identities = account.cachedIdentities();
        } else {
            try {
                identities = account.identities();
            } catch (com.dnd.cloud.CloudStore.CloudException e) {
                if (saving || !e.isOffline()) throw e;
                identities = account.cachedIdentities();
            }
        }
        List<PrivateAccount.Identity> result = new ArrayList<>();
        for (CampaignRecord.Member member : record.members.values()) {
            if (member.role != CampaignRecord.Role.DM) continue;
            var profile = profiles.find(member.username).orElseThrow(
                () -> new IllegalStateException("A GM profile is unavailable. Connect and sync first."));
            if (saving) requireOnline();
            var matches = identities.stream().filter(identity -> identity.uid().equals(profile.getProviderUid())
                && identity.email() != null && identity.email().equalsIgnoreCase(profile.getEmail())).toList();
            if (matches.size() != 1) {
                throw new IllegalStateException("Each campaign GM must enable private-file keys before notes can be shared.");
            }
            result.add(matches.getFirst());
        }
        if (result.stream().noneMatch(identity -> account.uid().equals(identity.uid()))) {
            throw new SecurityException("The current GM's private identity does not match campaign membership.");
        }
        return List.copyOf(result);
    }

    private static void requireOnline() {
        if (!Cloud.hasOnlineAccount() || Cloud.isOffline()) {
            throw new IllegalStateException("Connect and sign in online before saving a GM note.");
        }
    }
}
