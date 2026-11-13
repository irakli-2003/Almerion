package com.dnd.ui.scenes;

import com.dnd.cloud.*;
import com.dnd.data.StorylineService;
import com.dnd.security.*;
import com.dnd.ui.i18n.UiTranslator;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.*;

/** Account-key setup, owner-controlled access, and the GM's access-request inbox. */
final class PrivateFilesUi {
    private record Prepared(FirebasePrivateStore store, PrivateAccount.Vault vault,
                            PrivateAccount.Setup setup, PrivateAccount account) { }
    record UnlockRequest(String password, boolean verifyEmail) { }

    private PrivateFilesUi() { }

    static void unlocked(BaseScene owner, Runnable action) {
        PrivateAccount existing = owner.uiSession.getPrivateAccount();
        if (existing != null && existing.isUnlocked()) {
            runAction(owner, action);
            return;
        }
        if (owner.uiSession.getCurrentUser() == null) {
            owner.showInfo("Private files", "Sign in with your own verified account first.");
            return;
        }
        unlockDialog(owner).showAndWait().ifPresent(request -> {
            String password = request.password();
            if (request.verifyEmail()) {
                var user = owner.uiSession.getCurrentUser();
                owner.background(() -> {
                    FirebasePrivateStore.sendVerification(Cloud.config(), user, password);
                    return true;
                }, ignored -> owner.showInfo("Verify your Firebase email",
                    "Open the verification link in your email, then choose Unlock private files again with the same password."
                        + " This uses your existing Almerion profile; it does not create another campaign account."),
                    error -> owner.showError("Could not send Firebase verification email", error));
                return;
            }
            owner.background(() -> prepare(owner, password), prepared -> {
                if (prepared.account() != null) {
                    finish(owner, prepared.account(), action);
                } else if (prepared.setup() != null) {
                    if (!saveRecovery(owner, prepared.setup().recoveryKey())) {
                        prepared.store().close();
                        return;
                    }
                    owner.background(() -> PrivateAccount.finishSetup(prepared.store(), prepared.setup(), password),
                        account -> finish(owner, account, action), error -> {
                            prepared.store().close();
                            owner.showError("Could not create private-file keys", error);
                        });
                } else {
                    password(owner, "Recover private-file keys",
                        "Your account password changed. Enter the recovery key saved when private files were enabled.")
                        .ifPresentOrElse(recovery -> owner.background(() -> PrivateAccount.recover(prepared.store(),
                            prepared.vault(), recovery, password), account -> finish(owner, account, action),
                            error -> {
                                prepared.store().close();
                                owner.showError("Could not recover private-file keys", error);
                            }), prepared.store()::close);
                }
            }, error -> owner.showError("Could not unlock private files", error));
        });
    }

    static Dialog<UnlockRequest> unlockDialog(BaseScene owner) {
        Dialog<UnlockRequest> dialog = new Dialog<>();
        dialog.setTitle("Unlock private files");
        dialog.setHeaderText("Enter your account password. If you have not used Firebase sign-in before,"
            + " choose Set up / verify email first.");
        PasswordField field = owner.passwordField("Enter your account password.");
        ButtonType verify = new ButtonType("Set up / verify email", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().setContent(field);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, verify, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(field.textProperty().isEmpty());
        dialog.getDialogPane().lookupButton(verify).disableProperty().bind(field.textProperty().isEmpty());
        dialog.setResultConverter(button -> button == ButtonType.CANCEL ? null
            : new UnlockRequest(field.getText(), button == verify));
        owner.styleDialog(dialog);
        return dialog;
    }

    private static Prepared prepare(BaseScene owner, String password) {
        var user = owner.uiSession.getCurrentUser();
        FirebasePrivateStore store = FirebasePrivateStore.connect(Cloud.config(), user, password);
        try {
            PrivateAccount.Vault vault = PrivateAccount.load(store, store.uid());
            if (vault == null) {
                return new Prepared(store, null, PrivateAccount.prepare(store.uid(), store.email(), user.getUsername(), password), null);
            }
            try {
                return new Prepared(store, vault, null, PrivateAccount.unlock(store, vault, password));
            } catch (SessionCipher.CipherException e) {
                return new Prepared(store, vault, null, null);
            }
        } catch (RuntimeException e) {
            store.close();
            throw e;
        }
    }

    private static void finish(BaseScene owner, PrivateAccount account, Runnable action) {
        try {
            owner.uiSession.setPrivateAccount(account);
        } catch (RuntimeException error) {
            owner.showError("Could not unlock private files", error);
            return;
        }
        owner.background(account::identities, ignored -> runAction(owner, action),
            error -> owner.showError("Could not load private-file identities", error));
    }

    private static void runAction(BaseScene owner, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            owner.showError("Private files", error);
        }
    }

    private static Optional<String> password(BaseScene owner, String title, String prompt) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(prompt);
        PasswordField field = owner.passwordField(prompt);
        dialog.getDialogPane().setContent(field);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK ? field.getText() : null);
        owner.styleDialog(dialog);
        return dialog.showAndWait();
    }

    private static boolean saveRecovery(BaseScene owner, String recovery) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Save your private-file recovery key");
        dialog.setHeaderText("Save this key outside the campaign and GitHub. You need it after a password reset."
            + " Without it or help from another reader, encrypted files cannot be recovered.");
        TextArea key = UiTranslator.skip(new TextArea(recovery));
        key.setEditable(false);
        key.setWrapText(true);
        key.setPrefRowCount(3);
        CheckBox saved = owner.checkBox("I saved the recovery key somewhere safe.");
        dialog.getDialogPane().setContent(new VBox(12, key, saved));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(saved.selectedProperty().not());
        dialog.setResultConverter(button -> button == ButtonType.OK);
        owner.styleDialog(dialog);
        return dialog.showAndWait().orElse(false);
    }

    static void access(BaseScene owner, StorylineService service, Path file, Runnable changed) {
        unlocked(owner, () -> {
            if (!service.isPrivate(file)) {
                Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                    "Make this shared session file private and become its owner? Existing Git history, exports, and copies"
                        + " will still contain the old plaintext. The campaign author will not receive automatic access.",
                    ButtonType.OK, ButtonType.CANCEL);
                owner.styleDialog(confirmation);
                if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                    owner.background(() -> { service.protect(file); return true; }, ignored -> changed.run(),
                        error -> owner.showError("Could not protect session file", error));
                }
                return;
            }
            var header = service.permissions(file);
            PrivateAccount account = owner.uiSession.getPrivateAccount();
            if (!account.uid().equals(header.ownerUid())) {
                if (header.grants().containsKey(account.uid())) {
                    owner.showInfo("Session-file access", "You can read and edit this file. Only its owner can manage access, move, or delete it.");
                } else {
                    request(owner, service, file);
                }
                return;
            }
            owner.background(() -> gmIdentities(owner, account), identities -> {
                Dialog<List<PrivateAccount.Identity>> dialog = new Dialog<>();
                dialog.setTitle("Session-file access");
                dialog.setHeaderText("Only selected GMs may read and edit this file. Removing access protects future versions,"
                    + " not copies already read. Verify each GM's email with them before granting access."
                    + " Only you can manage access, move, or delete it.");
                VBox choices = new VBox(8);
                Map<PrivateAccount.Identity, CheckBox> selected = new LinkedHashMap<>();
                for (PrivateAccount.Identity identity : identities) {
                    if (identity.uid().equals(account.uid())) continue;
                    CheckBox choice = owner.checkBox(identity.toString());
                    UiTranslator.skip(choice);
                    choice.setSelected(header.grants().containsKey(identity.uid()));
                    choices.getChildren().add(choice);
                    selected.put(identity, choice);
                }
                if (choices.getChildren().isEmpty()) choices.getChildren().add(owner.body(
                    "Other campaign GMs must enable their private-file account before they can receive access."));
                ScrollPane scroll = new ScrollPane(choices);
                scroll.setFitToWidth(true);
                scroll.setPrefViewportHeight(240);
                dialog.getDialogPane().setContent(scroll);
                dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                dialog.setResultConverter(button -> button == ButtonType.OK
                    ? selected.entrySet().stream().filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).toList() : null);
                owner.styleDialog(dialog);
                dialog.showAndWait().ifPresent(recipients -> owner.background(() -> {
                    service.setAccess(file, recipients, header);
                    return true;
                }, ignored -> changed.run(), error -> owner.showError("Could not change session-file access", error)));
            }, error -> owner.showError("Could not load campaign GMs", error));
        });
    }

    private static List<PrivateAccount.Identity> gmIdentities(BaseScene owner, PrivateAccount account) {
        String id = campaignId(owner);
        CampaignRecord campaign = CampaignDirectory.shared().find(id).orElseThrow(
            () -> new IllegalStateException("The campaign is not registered."));
        if (!campaign.isDm(owner.uiSession.getCurrentUser().getUsername())) {
            throw new SecurityException("Only campaign GMs can manage session files.");
        }
        CloudProfiles users = new CloudProfiles(Cloud.store());
        Set<String> emails = new HashSet<>();
        for (CampaignRecord.Member member : campaign.members.values()) {
            if (member.role == CampaignRecord.Role.DM) {
                var user = users.find(member.username).orElseThrow(
                    () -> new IllegalStateException("Cannot verify campaign GM " + member.username));
                emails.add(user.getEmail().toLowerCase(Locale.ROOT));
            }
        }
        return account.identities().stream().filter(identity -> emails.contains(identity.email().toLowerCase(Locale.ROOT))).toList();
    }

    static void request(BaseScene owner, StorylineService service, Path file) {
        unlocked(owner, () -> {
            var header = service.permissions(file);
            PrivateAccount account = owner.uiSession.getPrivateAccount();
            owner.background(() -> {
                account.requestAccess(header.ownerUid(), header.fileId(), campaignId(owner), file.getFileName().toString());
                return true;
            }, ignored -> owner.showInfo("Access requested", "The session-file owner will see your request in Notifications."
                + " After approval, sync the campaign again to receive the updated encrypted file."),
                error -> owner.showError("Could not request access", error));
        });
    }

    static void notifications(BaseScene owner) {
        unlocked(owner, () -> {
            Stage stage = new Stage();
            stage.setTitle("Notifications");
            com.dnd.ui.WindowOrder.adopt(stage);
            VBox content = new VBox(12);
            content.setPadding(new Insets(20));
            ScrollPane scroll = new ScrollPane(content);
            scroll.setFitToWidth(true);
            stage.setScene(owner.themedScene(scroll, 650, 560));
            Runnable reload = () -> loadNotifications(owner, content);
            content.getChildren().add(owner.btn("Refresh", reload));
            stage.show();
            reload.run();
        });
    }

    private record Inbox(List<PrivateAccount.AccessRequest> incoming, Map<PrivateAccount.AccessRequest, String> outgoing) { }
    private static void loadNotifications(BaseScene owner, VBox content) {
        PrivateAccount account = owner.uiSession.getPrivateAccount();
        owner.background(() -> new Inbox(account.requests(), account.outgoing()), inbox -> {
            content.getChildren().setAll(owner.btn("Refresh", () -> loadNotifications(owner, content)),
                owner.btn("Session chat", () -> SessionChatUi.show(owner)));
            content.getChildren().add(owner.sectionLabel("Session-file access requests"));
            for (var request : inbox.incoming()) {
                VBox row = new VBox(6, UiTranslator.skip(owner.body(request.fileName() + " - " + request.status())));
                row.getChildren().add(UiTranslator.skip(owner.body("Account: " + request.requesterUid())));
                if ("pending".equals(request.status())) {
                    row.getChildren().addAll(owner.btn("Review and approve", () -> approve(owner, account, request,
                        () -> loadNotifications(owner, content))),
                        owner.dangerBtn("Deny", () -> owner.background(() -> {
                            account.answer(request, false);
                            return true;
                        }, ignored -> loadNotifications(owner, content), error -> owner.showError("Could not answer access request", error))));
                }
                content.getChildren().add(row);
            }
            if (inbox.incoming().isEmpty()) content.getChildren().add(owner.body("No access requests."));
            content.getChildren().add(owner.sectionLabel("Your requests"));
            for (var request : inbox.outgoing().keySet()) {
                content.getChildren().add(UiTranslator.skip(owner.body(request.fileName() + " - " + request.status())));
            }
            content.getChildren().add(owner.body("Approved access arrives with the next campaign sync. Chat notifications are not available yet."));
        }, error -> owner.showError("Could not load notifications", error));
    }

    private static void approve(BaseScene owner, PrivateAccount account, PrivateAccount.AccessRequest request, Runnable changed) {
        owner.background(() -> {
            if (!campaignId(owner).equals(request.campaignId())) {
                throw new IllegalStateException("Open the campaign containing the requested file first.");
            }
            StorylineService service = new StorylineService(owner.uiSession.campaignRoot(), owner.uiSession::getPrivateAccount);
            Path file = service.findPrivateFile(request.fileId());
            if (file == null) throw new IllegalStateException("Sync this campaign first; the requested file is not on this device.");
            if (!service.permissions(file).ownerUid().equals(account.uid())) {
                throw new SecurityException("You are not the owner of the requested file.");
            }
            PrivateAccount.Identity requester = gmIdentities(owner, account).stream()
                .filter(identity -> identity.uid().equals(request.requesterUid())).findFirst().orElseThrow(
                    () -> new SecurityException("The requesting account is not a current campaign GM."));
            return new Approval(service, file, requester);
        }, approval -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Give read and edit access to "
                + approval.requester().email() + " for " + approval.file().getFileName() + "?", ButtonType.OK, ButtonType.CANCEL);
            owner.styleDialog(confirm);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            owner.background(() -> {
                List<PrivateAccount.Identity> recipients = new ArrayList<>();
                for (String uid : approval.service().permissions(approval.file()).grants().keySet()) recipients.add(account.identity(uid));
                recipients.add(approval.requester());
                approval.service().setAccess(approval.file(), recipients);
                account.answer(request, true);
                return true;
            }, ignored -> changed.run(), error -> owner.showError("Could not approve access. Check the file before retrying.", error));
        }, error -> owner.showError("Could not review access request", error));
    }
    private record Approval(StorylineService service, Path file, PrivateAccount.Identity requester) { }

    private static String campaignId(BaseScene owner) {
        return CampaignMeta.idOf(owner.uiSession.campaignRoot()).orElseThrow(
            () -> new IllegalStateException("Claim/register the campaign before requesting or granting access."));
    }
}
