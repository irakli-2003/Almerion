package com.dnd.ui.scenes;

import com.dnd.cloud.CampaignDirectory;
import com.dnd.cloud.CampaignMeta;
import com.dnd.cloud.CampaignRecord;
import com.dnd.cloud.CampaignRecord.Role;
import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.PlayerCharacter;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The DM's "Members" window: the join code to hand to players, who is in the campaign, and
 * who is waiting to be let in. What each button may do follows {@link CampaignDirectory}'s rules
 * (only the author manages Game Masters); buttons the current DM can't use are hidden.
 */
public final class MembersWindow {
    private final BaseScene owner;
    private final String campaignId;
    private final String user;
    private final Path campaignRoot;
    private final CampaignDirectory directory = CampaignDirectory.shared();
    private final VBox content = new VBox(14);
    private Stage stage;

    private MembersWindow(BaseScene owner, String campaignId, String user, Path campaignRoot) {
        this.owner = owner;
        this.campaignId = campaignId;
        this.user = user;
        this.campaignRoot = campaignRoot;
    }

    public static void open(BaseScene owner, Path campaignRoot, String user) {
        String id = campaignRoot == null ? null : CampaignMeta.idOf(campaignRoot).orElse(null);
        if (id == null) {
            owner.showInfo("This campaign isn't registered",
                "Go back to Campaign Selection and claim it first; then you can invite players.");
            return;
        }
        new MembersWindow(owner, id, user, campaignRoot).show();
    }

    static void openRegistered(BaseScene owner, String campaignId, String user) {
        new MembersWindow(owner, campaignId, user, null).show();
    }

    static void invite(BaseScene owner, CampaignRecord record, String user, Runnable finished) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Invite to campaign");
        dialog.setHeaderText(record.name);
        TextField code = owner.textField("Recipient's account code");
        ComboBox<Role> role = new ComboBox<>();
        role.getItems().add(Role.PLAYER);
        if (record.isAuthor(user)) role.getItems().add(Role.DM);
        role.setValue(Role.PLAYER);
        role.setConverter(new javafx.util.StringConverter<>() {
            public String toString(Role value) { return value == Role.DM ? "Game Master" : "Player"; }
            public Role fromString(String value) { throw new UnsupportedOperationException(); }
        });
        ButtonType send = new ButtonType("Send invitation", ButtonBar.ButtonData.OK_DONE);
        ButtonType revoke = new ButtonType("Withdraw invitation", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().setContent(new VBox(10, owner.body("Ask the recipient for the code in Join Campaign."),
            code, owner.body("Invite as"), role));
        dialog.getDialogPane().getButtonTypes().setAll(send, revoke, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(send).disableProperty().bind(code.textProperty().isEmpty());
        dialog.getDialogPane().lookupButton(revoke).disableProperty().bind(code.textProperty().isEmpty());
        owner.styleDialog(dialog);
        dialog.showAndWait().ifPresent(button -> {
            String recipient = code.getText();
            Role selectedRole = role.getValue();
            if (button == send) {
                owner.background(() -> CampaignDirectory.shared().invite(record.id, user, recipient, selectedRole), result -> {
                    owner.showInfo("Invitation sent", "The invitation appears in the recipient's My Campaigns screen.");
                    finished.run();
                }, error -> owner.showError("Couldn't send invitation", error));
            } else if (button == revoke) {
                owner.background(() -> {
                    CampaignDirectory.shared().revokeInvitation(record.id, user, recipient);
                    return true;
                }, result -> {
                    owner.showInfo("Invitation withdrawn", "Any pending invitation for that account was cleared.");
                    finished.run();
                }, error -> owner.showError("Couldn't withdraw invitation", error));
            }
        });
    }

    private void show() {
        stage = new Stage();
        stage.setTitle("Campaign Members");
        stage.initOwner(com.dnd.ui.WindowOrder.current());
        content.setPadding(new Insets(20));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        stage.setScene(owner.themedScene(scroll, 620, 560));
        stage.show();
        reload();
    }

    private void reload() {
        content.getChildren().setAll(owner.body("Loading..."));
        owner.background(() -> directory.find(campaignId).orElse(null), this::render,
            error -> content.getChildren().setAll(owner.body(BaseScene.friendlyError(error))));
    }

    /** Runs a membership change in the background, then reloads (or shows why it failed). */
    private void act(String failureHeader, Supplier<?> change) {
        owner.background(change::get, ignored -> reload(), error -> owner.showError(failureHeader, error));
    }

    private void render(CampaignRecord record) {
        content.getChildren().clear();
        if (record == null) {
            content.getChildren().add(owner.body("This campaign is no longer registered online."));
            return;
        }
        boolean author = record.isAuthor(user);
        if (!record.isDm(user)) {
            content.getChildren().add(owner.body("You're no longer a Game Master of this campaign."));
            return;
        }

        Label code = new Label(record.joinCode);
        code.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: #f2d16b; -fx-font-family: 'Consolas', monospace;");
        Button copy = owner.btn("Copy", () -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(record.joinCode);
            Clipboard.getSystemClipboard().setContent(c);
        });
        Button regenerate = owner.btn("New Code", () -> act("Couldn't change the join code",
            () -> directory.regenerateJoinCode(campaignId, user)));
        regenerate.setTooltip(new Tooltip("Make a new code; the old one stops working"));
        HBox codeRow = new HBox(12, code, copy, regenerate);
        codeRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().addAll(owner.sectionLabel("Join code"),
            owner.body("Players sign in with their own account, choose Player → Join a Campaign and enter this code. You then accept them below."),
            codeRow, owner.btn("Invite to campaign", () -> invite(owner, record, user, this::reload)));

        content.getChildren().add(owner.sectionLabel("Waiting to join (" + record.applications.size() + ")"));
        if (record.applications.isEmpty()) content.getChildren().add(owner.body("Nobody is waiting."));
        for (CampaignRecord.Application application : record.applications.values()) {
            HBox row = row(owner.body(application.username),
                owner.btn("Accept as Player", () -> act("Couldn't accept " + application.username,
                    () -> directory.accept(campaignId, user, application.username, Role.PLAYER))));
            if (author) {
                row.getChildren().add(owner.btn("Accept as GM", () -> act("Couldn't accept " + application.username,
                    () -> directory.accept(campaignId, user, application.username, Role.DM))));
            }
            row.getChildren().add(owner.dangerBtn("Reject", () -> act("Couldn't reject " + application.username,
                () -> directory.reject(campaignId, user, application.username))));
            content.getChildren().add(row);
        }

        content.getChildren().add(owner.sectionLabel("Members (" + record.members.size() + ")"));
        content.getChildren().add(owner.body(campaignRoot == null
            ? "Open Members from a local copy of this campaign to link existing characters."
            : "After a player accepts, use Characters beside their account to link their existing sheet."));
        for (CampaignRecord.Member member : record.members.values().stream()
                .sorted(java.util.Comparator.comparing(m -> m.username, String.CASE_INSENSITIVE_ORDER)).toList()) {
            boolean isAuthor = record.isAuthor(member.username);
            String label = member.username + "  -  " + (isAuthor ? "Author GM" : member.role == Role.DM ? "GM" : "Player");
            HBox row = row(owner.body(label));
            if (campaignRoot != null && member.role == Role.PLAYER) {
                row.getChildren().add(owner.btn("Characters", () -> chooseAssignment(member.username)));
            }
            if (author && !isAuthor) {
                Role other = member.role == Role.DM ? Role.PLAYER : Role.DM;
                row.getChildren().add(owner.btn(other == Role.DM ? "Make GM" : "Make Player",
                    () -> act("Couldn't change " + member.username + "'s role",
                        () -> directory.setRole(campaignId, user, member.username, other))));
                if (member.role == Role.DM) {
                    row.getChildren().add(owner.btn("Transfer ownership", () -> {
                        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                            "Make " + member.username + " the campaign owner? You will stay a GM but lose owner permissions.",
                            ButtonType.OK, ButtonType.CANCEL);
                        owner.styleDialog(confirm);
                        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK)
                            act("Couldn't transfer ownership", () -> directory.transferOwnership(campaignId, user, member.username));
                    }));
                }
            }
            boolean canRemove = !isAuthor && (author || member.role == Role.PLAYER);
            if (canRemove) {
                row.getChildren().add(owner.dangerBtn("Remove", () -> act("Couldn't remove " + member.username,
                    () -> directory.remove(campaignId, user, member.username))));
            }
            content.getChildren().add(row);
        }

        if (!author) {
            content.getChildren().add(owner.body("Only " + record.author + " (the author) can add or remove Game Masters."));
        }
        content.getChildren().add(owner.btn("Refresh", this::reload));
    }

    private void chooseAssignment(String username) {
        owner.background(() -> new CampaignRepositories(campaignRoot).players().list().stream()
            .filter(pc -> pc.isOwnedBy(username) || pc.getOwnerUsername() == null || pc.getOwnerUsername().isBlank())
            .toList(), choices -> {
            if (choices.isEmpty()) {
                owner.showInfo("No available characters", "Create a character or unlink it from its current player first.");
                return;
            }
            record Choice(PlayerCharacter pc, boolean linked) {
                @Override public String toString() {
                    return (pc.getName() == null ? pc.getId() : pc.getName()) + " [" + pc.getId() + "]"
                        + (linked ? " (linked)" : " (unassigned)");
                }
            }
            ChoiceDialog<Choice> dialog = new ChoiceDialog<>(null, choices.stream()
                .map(pc -> new Choice(pc, pc.isOwnedBy(username))).toList());
            dialog.setTitle("Link a character");
            dialog.setHeaderText("Characters for " + username);
            dialog.setContentText("Choose an unassigned character to link, or a linked character to unlink:");
            owner.styleDialog(dialog);
            dialog.showAndWait().ifPresent(choice -> {
                boolean assign = !choice.linked();
                if (!assign) {
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Unlink this character from " + username + "?", ButtonType.OK, ButtonType.CANCEL);
                    owner.styleDialog(confirm);
                    if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                }
                owner.background(() -> {
                    CampaignRecord current = directory.find(campaignId).orElse(null);
                    if (com.dnd.cloud.Cloud.isOffline() || current == null || !current.isDm(user)
                        || current.roleOf(username) != Role.PLAYER
                        || CampaignMeta.idOf(campaignRoot).filter(campaignId::equals).isEmpty()) {
                        throw new IllegalStateException("Could not verify current campaign membership. Refresh and try again online.");
                    }
                    new CampaignRepositories(campaignRoot).setPlayerAssignment(choice.pc().getId(), username, assign);
                    return assign;
                }, linked -> owner.showInfo(linked ? "Character linked" : "Character unlinked",
                    linked ? username + " can open the character from the live session." : "The character is now unassigned."),
                    failure -> owner.showError("Couldn't change character assignment", failure));
            });
        }, failure -> owner.showError("Couldn't load characters", failure));
    }

    private static HBox row(Node... nodes) {
        HBox row = new HBox(10, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        if (nodes.length > 0 && nodes[0] instanceof Region r) {
            r.setMinWidth(220);
            r.setPrefWidth(220);
        }
        return row;
    }
}
