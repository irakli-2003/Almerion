package com.dnd.ui.scenes.player;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.PlayerProfileStore;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import com.dnd.ui.scenes.BaseScene;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public class PlayerHomeScene extends BaseScene {
    public PlayerHomeScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        CampaignRepositories repos = new CampaignRepositories(
            uiSession.getSession().getCampaignContext().getPath());
        PlayerCharacter pc = repos.players().getById(uiSession.getSession().getActivePlayerCharacterId());
        // The DM's sheet is the mechanical truth; the player's own name, portrait and
        // description are laid over it from local storage.
        if (pc != null && uiSession.isLoggedIn()) {
            new PlayerProfileStore(uiSession.getCurrentUser().getUsername()).applyTo(pc);
        }

        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(SceneType.PLAYER_ONLINE_SESSION));
        root.getChildren().add(com.dnd.ui.scenes.PlayerModeUi.banner(this));

        VBox content = new VBox(20);
        content.setPadding(new Insets(20, 60, 40, 60));

        String name = pc != null && pc.getName() != null ? pc.getName() : "Unknown";
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().add(title("Player: " + name));
        if (uiSession.getSession().isOnline()) {
            Label badge = new Label("● LIVE");
            badge.getStyleClass().add("success-badge");
            header.getChildren().add(badge);
        }
        content.getChildren().add(header);

        if (pc != null) {
            content.getChildren().add(buildStatsBox(pc, repos));
        }

        content.getChildren().addAll(
            btn("View Inventory", () -> showInventory(pc, repos)),
            btn("View Abilities", () -> showAbilities(pc, repos)),
            btn("My Profile", () -> uiSession.getRouter().goTo(SceneType.CHARACTER_PROFILE)),
            com.dnd.ui.scenes.SessionChatUi.button(this),
            btn("Online Session" + (uiSession.getSession().isOnline() ? "  ●" : ""),
                () -> uiSession.getRouter().goTo(SceneType.PLAYER_ONLINE_SESSION))
        );

        root.getChildren().add(content);
        return wrapInScene(root);
    }

    private VBox buildStatsBox(PlayerCharacter pc, CampaignRepositories repos) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: #0f0f1e; -fx-border-color: #3a3a5a; -fx-border-width: 1px;");

        String className = resolve(repos.classes().getById(pc.getClassId()));
        String raceName  = resolve(repos.races().getById(pc.getRaceId()));
        box.getChildren().add(body("Class: " + className + "  |  Race: " + raceName + "  |  Level: " + pc.getLevel()));

        CoreStats s = pc.getStats();
        if (s != null) {
            box.getChildren().add(body(s.schema().getAbilities().stream()
                .map(a -> a.abbreviation() + " " + s.score(a.id()))
                .collect(java.util.stream.Collectors.joining("  "))));
        }
        return box;
    }

    private void showInventory(PlayerCharacter pc, CampaignRepositories repos) {
        if (pc == null || pc.getItems() == null || pc.getItems().isEmpty()) {
            info("Your inventory is empty."); return;
        }
        StringBuilder sb = new StringBuilder();
        for (var pi : pc.getItems()) {
            var item = repos.items().getById(pi.getItemId());
            String itemName = item != null ? item.getName() : pi.getItemId();
            sb.append("• ").append(itemName).append(pi.isEquipped() ? " (equipped)" : "").append("\n");
        }
        info(sb.toString());
    }

    private void showAbilities(PlayerCharacter pc, CampaignRepositories repos) {
        if (pc == null || pc.getSpells() == null || pc.getSpells().isEmpty()) {
            info("No spells or abilities."); return;
        }
        StringBuilder sb = new StringBuilder();
        for (var ps : pc.getSpells()) {
            var spell = repos.spells().getById(ps.getSpellId());
            String spellName = spell != null ? spell.getName() : ps.getSpellId();
            sb.append("• ").append(spellName).append(" (Rank ").append(ps.getRank()).append(")\n");
        }
        info(sb.toString());
    }

    private String resolve(Object entity) {
        if (entity == null) return "Unknown";
        try { return entity.getClass().getMethod("getName").invoke(entity).toString(); }
        catch (Exception e) { return "Unknown"; }
    }

    private void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setHeaderText(null);
        styleDialog(a);
        a.showAndWait();
    }
}
