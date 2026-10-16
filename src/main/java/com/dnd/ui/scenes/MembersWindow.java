package com.dnd.ui.scenes;

import com.dnd.cloud.CampaignDirectory;
import com.dnd.cloud.CampaignMeta;
import com.dnd.cloud.CampaignRecord;
import com.dnd.cloud.CampaignRecord.Role;
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
 * (only the author manages Dungeon Masters); buttons the current DM can't use are hidden.
 */
public final class MembersWindow {
    private final BaseScene owner;
    private final String campaignId;
    private final String user;
    private final CampaignDirectory directory = CampaignDirectory.shared();
    private final VBox content = new VBox(14);
    private Stage stage;

    private MembersWindow(BaseScene owner, String campaignId, String user) {
        this.owner = owner;
        this.campaignId = campaignId;
        this.user = user;
    }

    public static void open(BaseScene owner, Path campaignRoot, String user) {
        String id = campaignRoot == null ? null : CampaignMeta.idOf(campaignRoot).orElse(null);
        if (id == null) {
            owner.showInfo("This campaign isn't registered",
                "Go back to Campaign Selection and claim it first; then you can invite players.");
            return;
        }
        new MembersWindow(owner, id, user).show();
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
            content.getChildren().add(owner.body("You're no longer a Dungeon Master of this campaign."));
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
            codeRow);

        content.getChildren().add(owner.sectionLabel("Waiting to join (" + record.applications.size() + ")"));
        if (record.applications.isEmpty()) content.getChildren().add(owner.body("Nobody is waiting."));
        for (CampaignRecord.Application application : record.applications.values()) {
            HBox row = row(owner.body(application.username),
                owner.btn("Accept as Player", () -> act("Couldn't accept " + application.username,
                    () -> directory.accept(campaignId, user, application.username, Role.PLAYER))));
            if (author) {
                row.getChildren().add(owner.btn("Accept as DM", () -> act("Couldn't accept " + application.username,
                    () -> directory.accept(campaignId, user, application.username, Role.DM))));
            }
            row.getChildren().add(owner.dangerBtn("Reject", () -> act("Couldn't reject " + application.username,
                () -> directory.reject(campaignId, user, application.username))));
            content.getChildren().add(row);
        }

        content.getChildren().add(owner.sectionLabel("Members (" + record.members.size() + ")"));
        for (CampaignRecord.Member member : record.members.values()) {
            boolean isAuthor = record.isAuthor(member.username);
            String label = member.username + "  -  " + (isAuthor ? "Author DM" : member.role == Role.DM ? "DM" : "Player");
            HBox row = row(owner.body(label));
            if (author && !isAuthor) {
                Role other = member.role == Role.DM ? Role.PLAYER : Role.DM;
                row.getChildren().add(owner.btn(other == Role.DM ? "Make DM" : "Make Player",
                    () -> act("Couldn't change " + member.username + "'s role",
                        () -> directory.setRole(campaignId, user, member.username, other))));
            }
            boolean canRemove = !isAuthor && (author || member.role == Role.PLAYER);
            if (canRemove) {
                row.getChildren().add(owner.dangerBtn("Remove", () -> act("Couldn't remove " + member.username,
                    () -> directory.remove(campaignId, user, member.username))));
            }
            content.getChildren().add(row);
        }

        if (!author) {
            content.getChildren().add(owner.body("Only " + record.author + " (the author) can add or remove Dungeon Masters."));
        }
        content.getChildren().add(owner.btn("Refresh", this::reload));
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
