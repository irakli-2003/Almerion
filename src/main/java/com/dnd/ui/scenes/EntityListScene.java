package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.creature.Beast;
import com.dnd.model.creature.ChallengeRating;
import com.dnd.model.creature.Monster;
import com.dnd.model.creature.Npc;
import com.dnd.model.item.Item;
import com.dnd.model.magic.Spell;
import com.dnd.ui.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EntityListScene extends BaseScene {

    /** Id to display name, so player rows can show class/race names instead of raw ids. */
    private final Map<String, String> classNames = new LinkedHashMap<>();
    private final Map<String, String> raceNames = new LinkedHashMap<>();

    public EntityListScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        EntityCategory cat = uiSession.getActiveEntityCategory();

        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        SceneType backTarget = uiSession.isDm() ? SceneType.DM_MENU : SceneType.PLAYER_HOME;
        root.getChildren().add(backBar(backTarget));

        VBox content = new VBox(12);
        content.setPadding(new Insets(20, 40, 30, 40));

        String catName = cat != null ? cat.name() : "Entities";
        content.getChildren().addAll(title(catName), subtitle("Campaign: " + campaignName()));

        ListView<HBox> listView = new ListView<>();
        listView.getStyleClass().add("dnd-list-view");
        listView.setPrefHeight(350);

        CampaignRepositories repos = new CampaignRepositories(uiSession.campaignRoot());
        if (cat == EntityCategory.PLAYER) {
            for (CharacterClass characterClass : repos.classes().list()) {
                classNames.put(characterClass.getId(), characterClass.getName());
            }
            for (CharacterRace race : repos.races().list()) {
                raceNames.put(race.getId(), race.getName());
            }
        }
        List<?> entities = getEntities(repos, cat);

        for (Object entity : entities) {
            listView.getItems().add(buildRow(entity, cat));
        }

        content.getChildren().add(listView);

        HBox actions = new HBox(10);
        Button viewBtn = btn("View / Edit", () -> {
            int idx = listView.getSelectionModel().getSelectedIndex();
            if (idx < 0 || idx >= entities.size()) return;
            Object selected = entities.get(idx);
            uiSession.setActiveEntityId(getEntityId(selected));
            if (cat == EntityCategory.MAP) {
                uiSession.setActiveMapId(getEntityId(selected));
                uiSession.getRouter().goTo(uiSession.isDm() ? SceneType.MAP_EDITOR : SceneType.MAP_VIEW);
            } else {
                uiSession.getRouter().goTo(SceneType.ENTITY_DETAIL);
            }
        });
        actions.getChildren().add(viewBtn);

        if (uiSession.isDm()) {
            Button newBtn = btn("New", () -> {
                if (cat == EntityCategory.PLAYER) {
                    new CharacterCreationWizard(uiSession, repos).show(() -> uiSession.getRouter().goTo(SceneType.ENTITY_LIST));
                    return;
                }
                uiSession.setActiveEntityId(null);
                uiSession.getRouter().goTo(SceneType.ENTITY_DETAIL);
            });
            Button deleteBtn = dangerBtn("Delete", () -> {
                int idx = listView.getSelectionModel().getSelectedIndex();
                if (idx < 0 || idx >= entities.size()) return;
                Object selected = entities.get(idx);
                String id = getEntityId(selected);
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete " + catName + " '" + id + "'?", ButtonType.OK, ButtonType.CANCEL);
                styleDialog(confirm);
                confirm.showAndWait().ifPresent(bt -> {
                    if (bt == ButtonType.OK) {
                        deleteEntity(repos, cat, id);
                        uiSession.getRouter().goTo(SceneType.ENTITY_LIST);
                    }
                });
            });
            actions.getChildren().addAll(newBtn, deleteBtn);
        }

        content.getChildren().add(actions);
        root.getChildren().add(content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return wrapInScene(root);
    }

    private HBox buildRow(Object entity, EntityCategory cat) {
        HBox row = new HBox(12);
        row.setStyle("-fx-padding: 4 8 4 8;");

        String imgPath = getImagePath(entity);
        ImageView thumb = new ImageView();
        thumb.setFitWidth(40);
        thumb.setFitHeight(40);
        thumb.setPreserveRatio(true);
        if (imgPath != null && uiSession.campaignRoot() != null) {
            Image img = ImageStore.load(uiSession.campaignRoot(), imgPath);
            if (img != null) thumb.setImage(img);
        }
        if (thumb.getImage() == null) {
            thumb.setImage(createColorPlaceholder(cat));
        }

        Label name = new Label(getEntityName(entity));
        name.setStyle("-fx-text-fill: #d0c5a8; -fx-font-size: 13px;");
        Label id = new Label("  [" + getEntityId(entity) + "]");
        id.setStyle("-fx-text-fill: #6a5a3a; -fx-font-size: 11px;");

        VBox text = new VBox(2, new HBox(0, name, id));
        text.setAlignment(Pos.CENTER_LEFT);
        String summary = summarize(entity);
        if (!summary.isBlank()) {
            Label stats = new Label(summary);
            stats.setStyle("-fx-text-fill: #8a7a52; -fx-font-size: 11px;");
            text.getChildren().add(stats);
        }

        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(thumb, text);
        return row;
    }

    /**
     * One-line stat digest so the list is browsable without opening each entry - the campaign
     * data carries full stat blocks, and a list of bare names hides all of it.
     */
    private String summarize(Object entity) {
        List<String> parts = new ArrayList<>();
        if (entity instanceof Monster monster) {
            addIfPresent(parts, monster.getType());
            addIfPresent(parts, challengeRating(monster.getChallengeRating()));
            addIfPresent(parts, abilityLine(monster.getStats()));
        } else if (entity instanceof Beast beast) {
            addIfPresent(parts, beast.getHabitat() == null ? null : beast.getHabitat().getValue());
            addIfPresent(parts, challengeRating(beast.getChallengeRating()));
            addIfPresent(parts, abilityLine(beast.getStats()));
        } else if (entity instanceof Npc npc) {
            addIfPresent(parts, npc.getRole());
            if (npc.getLevel() > 0) parts.add("level " + npc.getLevel());
            addIfPresent(parts, abilityLine(npc.getStats()));
        } else if (entity instanceof PlayerCharacter player) {
            if (player.getLevel() > 0) parts.add("level " + player.getLevel());
            addIfPresent(parts, lookupName(player.getRaceId(), raceNames));
            addIfPresent(parts, lookupName(player.getClassId(), classNames));
            addIfPresent(parts, abilityLine(player.getStats()));
        } else if (entity instanceof Spell spell) {
            parts.add("level " + spell.getLevel());
            addIfPresent(parts, spell.getSchool() == null ? null : spell.getSchool().getValue());
            if (spell.getRange() > 0) parts.add((int) spell.getRange() + " ft");
        } else if (entity instanceof Item item) {
            addIfPresent(parts, item.getType());
            if (item.getWeight() > 0) parts.add(item.getWeight() + " lb");
            if (item.getValueGold() > 0) parts.add(item.getValueGold() + " gp");
            if (item.getDamage() != null && item.getDamage().getDice() != null) {
                parts.add(item.getDamage().getDice() + " damage");
            }
        }
        return String.join("  •  ", parts);
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value);
        }
    }

    private static String challengeRating(ChallengeRating rating) {
        return rating == null ? null : "CR " + rating.getValue();
    }

    /** Compact "STR 16 DEX 12 ..." digest of an ability block. */
    private static String abilityLine(CoreStats stats) {
        if (stats == null) {
            return null;
        }
        return "STR " + stats.getStrength() + " DEX " + stats.getDexterity()
            + " CON " + stats.getConstitution() + " INT " + stats.getIntelligence()
            + " WIS " + stats.getWisdom() + " CHA " + stats.getCharisma();
    }

    private String lookupName(String id, Map<String, String> names) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return names.getOrDefault(id, id);
    }

    private Image createColorPlaceholder(EntityCategory cat) {
        javafx.scene.canvas.Canvas c = new javafx.scene.canvas.Canvas(40, 40);
        javafx.scene.canvas.GraphicsContext gc = c.getGraphicsContext2D();
        Color color = switch (cat) {
            case PLAYER   -> Color.web("#4466aa");
            case NPC      -> Color.web("#66aa44");
            case MONSTER  -> Color.web("#aa4444");
            case BEAST    -> Color.web("#aa7744");
            case ITEM     -> Color.web("#aaaa44");
            case MAP      -> Color.web("#44aaaa");
            case SPELL    -> Color.web("#aa44aa");
            default       -> Color.web("#666666");
        };
        gc.setFill(color);
        gc.fillRoundRect(2, 2, 36, 36, 8, 8);
        gc.setStroke(Color.web("#ffffff44"));
        gc.setLineWidth(1);
        gc.strokeRoundRect(2, 2, 36, 36, 8, 8);
        return c.snapshot(null, null);
    }

    private List<?> getEntities(CampaignRepositories repos, EntityCategory cat) {
        if (cat == null) return List.of();
        return switch (cat) {
            case PLAYER            -> repos.players().list();
            case NPC               -> repos.npcs().list();
            case MONSTER           -> repos.monsters().list();
            case BEAST             -> repos.beasts().list();
            case ITEM              -> repos.items().list();
            case SPELL             -> repos.spells().list();
            case PLACE             -> repos.places().list();
            case MAP               -> repos.maps().list();
            case CLASS             -> repos.classes().list();
            case RACE              -> repos.races().list();
            case DAMAGE_TYPE       -> repos.damageTypes().list();
            case EFFECT            -> repos.effects().list();
            case LANGUAGE          -> repos.languages().list();
            case ALCHEMY_INGREDIENT-> repos.alchemyIngredients().list();
            case BOOK              -> repos.books().list();
            case DICE              -> repos.dice().list();
        };
    }

    private String getEntityId(Object e) {
        try { return (String) e.getClass().getMethod("getId").invoke(e); }
        catch (Exception ex) { return "?"; }
    }
    private String getEntityName(Object e) {
        try { Object n = e.getClass().getMethod("getName").invoke(e); return n != null ? n.toString() : getEntityId(e); }
        catch (Exception ex) { return getEntityId(e); }
    }
    private String getImagePath(Object e) {
        try { return (String) e.getClass().getMethod("getImagePath").invoke(e); }
        catch (Exception ex) { return null; }
    }
    private void deleteEntity(CampaignRepositories repos, EntityCategory cat, String id) {
        switch (cat) {
            case PLAYER             -> repos.players().delete(id);
            case NPC                -> repos.npcs().delete(id);
            case MONSTER            -> repos.monsters().delete(id);
            case BEAST              -> repos.beasts().delete(id);
            case ITEM               -> repos.items().delete(id);
            case SPELL              -> repos.spells().delete(id);
            case PLACE              -> repos.places().delete(id);
            case MAP                -> repos.maps().delete(id);
            case CLASS              -> repos.classes().delete(id);
            case RACE               -> repos.races().delete(id);
            case DAMAGE_TYPE        -> repos.damageTypes().delete(id);
            case EFFECT             -> repos.effects().delete(id);
            case LANGUAGE           -> repos.languages().delete(id);
            case ALCHEMY_INGREDIENT -> repos.alchemyIngredients().delete(id);
            case BOOK               -> repos.books().delete(id);
            case DICE               -> repos.dice().delete(id);
        }
    }

    private String campaignName() {
        return uiSession.getSession().getCampaignContext() != null
            ? uiSession.getSession().getCampaignContext().getName() : "(none)";
    }
}
