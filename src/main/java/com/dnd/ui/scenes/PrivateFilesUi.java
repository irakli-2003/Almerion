package com.dnd.ui.scenes;

import com.dnd.cloud.*;
import com.dnd.data.StorylineService;
import com.dnd.security.*;
import com.dnd.ui.i18n.UiTranslator;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.layout.GridPane;
import com.dnd.security.PrivateSessionFiles.Access;
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
        if (owner.uiSession.getCurrentUser().getProviderUid() != null) {
            owner.showInfo("Private files",
                "Private-file keys are loaded at sign-in. If they are unavailable, sign out and sign in again online.");
            return;
        }
        unlockDialog(owner).showAndWait().ifPresent(request -> {
            if (request.verifyEmail()) {
                var user = owner.uiSession.getCurrentUser();
                owner.background(() -> {
                    FirebasePrivateStore.sendVerification(Cloud.config(), user, request.password());
                    return true;
                }, ignored -> owner.showInfo("Verify your Firebase email",
                    "Open the verification link in your email, then choose Unlock private files again with the same password."
                        + " This uses your existing Almerion profile; it does not create another campaign account."),
                    error -> owner.showError("Could not send Firebase verification email", error));
                return;
            }
            openAccount(owner, request.password(), action);
        });
    }

    static void openAtSignIn(BaseScene owner, String password, Runnable done) {
        var user = owner.uiSession.getCurrentUser();
        if (user == null || user.getProviderUid() == null) {
            done.run();
            return;
        }
        openAccount(owner, password, done, done);
    }

    private static void openAccount(BaseScene owner, String password, Runnable action) {
        openAccount(owner, password, action, () -> {});
    }

    private static void openAccount(BaseScene owner, String password, Runnable action, Runnable unavailable) {
            var user = owner.uiSession.getCurrentUser();
            owner.background(() -> prepare(user, password), prepared -> {
                if (owner.uiSession.getCurrentUser() != user) {
                    if (prepared.account() != null) prepared.account().close();
                    else if (prepared.store() != null) prepared.store().close();
                    return;
                }
                if (prepared.account() != null) {
                    finish(owner, user, prepared.account(), prepared.vault(), action, unavailable);
                } else if (prepared.setup() != null) {
                    if (!saveRecovery(owner, prepared.setup().recoveryKey())) {
                        prepared.store().close();
                        unavailable.run();
                        return;
                    }
                    owner.background(() -> PrivateAccount.finishSetup(prepared.store(), prepared.setup(), password),
                        account -> finish(owner, user, account, prepared.setup().vault(), action, unavailable), error -> {
                            prepared.store().close();
                            privateAccountError(owner, user, "Could not create private-file keys", error, unavailable);
                        });
                } else {
                    password(owner, "Recover private-file keys",
                        "Your account password changed. Enter the recovery key saved when private files were enabled.")
                        .ifPresentOrElse(recovery -> owner.background(() -> {
                            PrivateAccount recovered = PrivateAccount.recover(prepared.store(),
                                prepared.vault(), recovery, password);
                            return new Prepared(prepared.store(), PrivateAccount.load(prepared.store(), recovered.uid()),
                                null, recovered);
                        }, result -> finish(owner, user, result.account(), result.vault(), action, unavailable),
                            error -> {
                                prepared.store().close();
                                privateAccountError(owner, user, "Could not recover private-file keys", error, unavailable);
                            }), () -> {
                                prepared.store().close();
                                unavailable.run();
                            });
                }
            }, error -> privateAccountError(owner, user, "Could not load private-file keys", error, unavailable));
    }

    private static void privateAccountError(BaseScene owner, com.dnd.auth.User user, String title,
                                            Throwable error, Runnable unavailable) {
        if (owner.uiSession.getCurrentUser() != user) return;
        owner.showError(title, error);
        unavailable.run();
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

    private static Prepared prepare(com.dnd.auth.User user, String password) {
        if (Cloud.isOffline()) {
            if (user.getProviderUid() == null) {
                throw new SecurityException("Sign in online and unlock private files on this device first.");
            }
            PrivateAccount account = OfflinePrivateAccounts.unlock(offlineCache(), user.getProviderUid(),
                user.getEmail(), password);
            return new Prepared(null, null, null, account);
        }
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

    private static Path offlineCache() {
        return com.dnd.data.AppHome.resolve("data", "auth", "private-account-cache");
    }

    private static void finish(BaseScene owner, com.dnd.auth.User user, PrivateAccount account,
                               PrivateAccount.Vault vault, Runnable action, Runnable unavailable) {
        owner.background(() -> {
            var identities = account.identities();
            if (vault != null) OfflinePrivateAccounts.save(offlineCache(), vault, identities);
            return identities;
        }, ignored -> {
            if (owner.uiSession.getCurrentUser() != user) {
                account.close();
                return;
            }
            try {
                owner.uiSession.setPrivateAccount(account);
                runAction(owner, action);
            } catch (RuntimeException error) {
                account.close();
                privateAccountError(owner, user, "Could not load private-file keys", error, unavailable);
            }
        }, error -> {
            account.close();
            privateAccountError(owner, user, "Could not load private-file identities", error, unavailable);
        });
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

    record NewSessionAccess(List<PrivateAccount.Identity> editors, List<String> missing, boolean offline) {
        NewSessionAccess {
            editors = List.copyOf(editors);
            missing = List.copyOf(missing);
        }
    }

    static NewSessionAccess newSessionAccess(Path campaignRoot, String username, PrivateAccount account) {
        String id = CampaignMeta.idOf(campaignRoot).orElse(null);
        if (id == null) return new NewSessionAccess(List.of(), List.of(), false);
        if (Cloud.isOffline()) return new NewSessionAccess(List.of(), List.of(), true);
        CampaignDirectory directory = CampaignDirectory.shared();
        boolean registered = directory.isRegistered(id);
        if (Cloud.isOffline()) return new NewSessionAccess(List.of(), List.of(), true);
        if (!registered) return new NewSessionAccess(List.of(), List.of(), false);
        CampaignRecord campaign = directory.findForMember(id, username).filter(record -> record.isDm(username))
            .orElseThrow(() -> new SecurityException("Only campaign GMs can manage session files."));
        CloudProfiles profiles = new CloudProfiles(Cloud.store());
        var creator = profiles.find(username).orElseThrow(() -> new SecurityException("Cannot verify the file creator."));
        if (!account.uid().equals(creator.getProviderUid()) || !account.identity().email().equalsIgnoreCase(creator.getEmail())) {
            throw new SecurityException("The private-file account does not match the signed-in campaign GM.");
        }
        Map<String, PrivateAccount.Identity> identities = new HashMap<>();
        List<PrivateAccount.Identity> editors = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        var coGms = campaign.members.values().stream()
            .filter(member -> member.role == CampaignRecord.Role.DM
                && !CloudKeys.of(member.username).equals(CloudKeys.of(username)))
            .sorted(Comparator.comparing(member -> member.username, String.CASE_INSENSITIVE_ORDER))
            .toList();
        if (!coGms.isEmpty()) account.identities().forEach(identity -> identities.put(identity.uid(), identity));
        for (var member : coGms) {
            var profile = profiles.find(member.username).orElse(null);
            var identity = profile == null ? null : identities.get(profile.getProviderUid());
            if (identity == null || !identity.email().equalsIgnoreCase(profile.getEmail())) missing.add(member.username);
            else editors.add(identity);
        }
        if (Cloud.isOffline()) return new NewSessionAccess(List.of(), List.of(), true);
        return new NewSessionAccess(editors, missing, false);
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
            PrivateAccount account = owner.uiSession.getPrivateAccount();
            var header = service.verifiedPermissions(file);
            if (!header.owners().contains(account.uid())) {
                if (header.grants().containsKey(account.uid())) {
                    owner.showInfo("Session-file access", header.access(account.uid()) == Access.READ
                        ? "You have read-only access to this file."
                        : "You can read and edit this file. Only its owner can manage access, move, or delete it.");
                } else {
                    request(owner, service, file);
                }
                return;
            }
            owner.background(() -> gmIdentities(owner, account), identities -> {
                Dialog<Map<PrivateAccount.Identity, Access>> dialog = new Dialog<>();
                dialog.setTitle("Session-file access");
                dialog.setHeaderText("Owners can change access, move, or delete the file. At least one owner must remain."
                    + " Removing access does not erase existing copies.");
                GridPane choices = new GridPane();
                choices.setHgap(16);
                choices.setVgap(8);
                choices.addRow(0, owner.body("Account"), owner.body("Read"), owner.body("Write"), owner.body("Owner"));
                record Selection(CheckBox read, CheckBox write, CheckBox owns) { }
                Map<PrivateAccount.Identity, Selection> selected = new LinkedHashMap<>();
                int row = 1;
                var displayed = new LinkedHashMap<String, PrivateAccount.Identity>();
                displayed.put(account.uid(), account.identity());
                identities.forEach(identity -> displayed.putIfAbsent(identity.uid(), identity));
                for (PrivateAccount.Identity identity : displayed.values()) {
                    CheckBox read = owner.checkBox("");
                    CheckBox write = owner.checkBox("");
                    CheckBox owns = owner.checkBox("");
                    read.setSelected(header.access(identity.uid()) != null);
                    owns.setSelected(header.owners().contains(identity.uid()));
                    write.setSelected(owns.isSelected() || header.access(identity.uid()) == Access.WRITE);
                    read.setAccessibleText(UiTranslator.text("Read") + " " + identity);
                    write.setAccessibleText(UiTranslator.text("Write") + " " + identity);
                    owns.setAccessibleText(UiTranslator.text("Owner") + " " + identity);
                    read.disableProperty().bind(owns.selectedProperty());
                    write.disableProperty().bind(owns.selectedProperty().or(read.selectedProperty().not()));
                    read.selectedProperty().addListener((o, old, enabled) -> { if (!enabled) write.setSelected(false); });
                    owns.selectedProperty().addListener((o, old, enabled) -> {
                        if (enabled) { read.setSelected(true); write.setSelected(true); }
                    });
                    choices.addRow(row++, UiTranslator.skip(owner.body(identity.toString())), read, write, owns);
                    selected.put(identity, new Selection(read, write, owns));
                }
                if (selected.size() == 1) choices.add(owner.body(
                    "Other campaign GMs must enable their private-file account before they can receive access."), 0, row, 4, 1);
                ScrollPane scroll = new ScrollPane(choices);
                scroll.setFitToWidth(true);
                scroll.setPrefViewportHeight(240);
                dialog.getDialogPane().setContent(scroll);
                dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                var apply = dialog.getDialogPane().lookupButton(ButtonType.OK);
                Runnable updateApply = () -> apply.setDisable(selected.values().stream().noneMatch(choice -> choice.owns().isSelected()));
                selected.values().forEach(choice -> choice.owns().selectedProperty().addListener((o, old, value) -> updateApply.run()));
                updateApply.run();
                dialog.setResultConverter(button -> {
                    if (button != ButtonType.OK) return null;
                    Map<PrivateAccount.Identity, Access> grants = new LinkedHashMap<>();
                    selected.forEach((identity, choice) -> {
                        if (choice.read().isSelected()) grants.put(identity, choice.owns().isSelected() ? Access.OWNER
                            : choice.write().isSelected() ? Access.WRITE : Access.READ);
                    });
                    return grants;
                });
                owner.styleDialog(dialog);
                dialog.showAndWait().ifPresent(recipients -> owner.background(() -> {
                    if (owner.uiSession.getPrivateAccount() != account) throw new IllegalStateException("The private-file account changed.");
                    service.replaceAccess(file, recipients, header);
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
            PrivateAccount account = owner.uiSession.getPrivateAccount();
            owner.background(() -> {
                var policy = service.verifiedPermissions(file);
                String recipient = policy.owners().contains(policy.ownerUid()) ? policy.ownerUid() : policy.owners().iterator().next();
                account.requestAccess(recipient, policy.fileId(), campaignId(owner), file.getFileName().toString());
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
            if (owner.uiSession.isDm()) content.getChildren().add(owner.btn("Level-up requests", () -> UpgradeRequestsUi.show(owner)));
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
            content.getChildren().add(owner.body("Approved access arrives with the next campaign sync."));
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
            if (!service.verifiedPermissions(file).owners().contains(account.uid())) {
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
                var header = approval.service().permissions(approval.file());
                Map<PrivateAccount.Identity, Access> recipients = new LinkedHashMap<>();
                for (String uid : header.grants().keySet()) recipients.put(account.identity(uid), header.access(uid));
                recipients.put(approval.requester(), Access.WRITE);
                approval.service().setAccess(approval.file(), recipients, header);
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
