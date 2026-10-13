package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.CharacterRace;
import com.dnd.ui.*;
import com.dnd.ui.components.EntityForm;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import com.dnd.ui.components.ImageFolders;

import java.io.File;
import java.util.*;

public class EntityDetailScene extends BaseScene {

    public EntityDetailScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        EntityCategory cat = uiSession.getActiveEntityCategory();
        String entityId = uiSession.getActiveEntityId();
        boolean isNew = (entityId == null);

        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        // Players are opened straight from the DM Menu's own card row (never via the generic
        // Entity List), so Back must return there too - otherwise it lands on the redundant
        // bare player list, which is otherwise unreachable and thus indistinguishable from a bug.
        SceneType backTarget = cat == EntityCategory.PLAYER ? SceneType.DM_MENU : SceneType.ENTITY_LIST;
        root.getChildren().add(backBar(backTarget));

        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: #1a1a2e;");

        VBox content = new VBox(16);
        content.setPadding(new Insets(20, 60, 40, 60));

        String catLabel = cat != null ? cat.name() : "";
        content.getChildren().add(title(isNew ? "New " + catLabel : "Edit " + catLabel));

        CampaignRepositories repos = new CampaignRepositories(uiSession.campaignRoot());
        Object entity = isNew ? null : getEntity(repos, cat, entityId);

        ImageView imageView = new ImageView();
        imageView.setFitWidth(120);
        imageView.setFitHeight(120);
        imageView.setPreserveRatio(true);
        imageView.setStyle("-fx-border-color: #c9a84c; -fx-border-width: 1px;");
        String currentImgPath = entity != null ? getImagePath(entity) : null;
        if (currentImgPath != null && uiSession.campaignRoot() != null) {
            Image img = ImageStore.load(uiSession.campaignRoot(), currentImgPath);
            if (img != null) imageView.setImage(img);
        }

        final String[] newImagePath = {currentImgPath};
        Button uploadImageBtn = btn("Upload Image", () -> {
            FileChooser fc = ImageFolders.imageChooser("Select Image");
            File chosen = fc.showOpenDialog(null);
            if (chosen != null && uiSession.campaignRoot() != null) {
                try {
                    String id = isNew ? "new_" + System.currentTimeMillis() : entityId;
                    String relative = ImageStore.copyImage(uiSession.campaignRoot(),
                        (cat != null ? cat.name().toLowerCase() : "misc") + "s", id, chosen);
                    newImagePath[0] = relative;
                    imageView.setImage(ImageStore.load(uiSession.campaignRoot(), relative));
                } catch (Exception ex) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to upload: " + ex.getMessage());
                    styleDialog(alert);
                    alert.showAndWait();
                }
            }
        });

        HBox imageSection = new HBox(16, imageView, uploadImageBtn);
        imageSection.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        content.getChildren().add(imageSection);

        if (uiSession.isDm() && entity instanceof com.dnd.model.character.PlayerCharacter pc) {
            content.getChildren().add(ProgressionPanel.build(this, repos, pc,
                () -> uiSession.getRouter().goTo(SceneType.ENTITY_DETAIL)));
        }

        Map<String, List<EntityForm.Ref>> catalogs = catalogsFor(repos);
        final EntityForm[] form = new EntityForm[1];
        final com.dnd.ui.components.ItemKinds.Kind[] itemKind = {null};
        VBox formHolder = new VBox();
        if (cat == EntityCategory.ITEM) {
            // Each item kind has its own fields (a blueprint's materials, a fuel's value...), so
            // the form is built from the concrete class, and new items pick their kind first.
            itemKind[0] = com.dnd.ui.components.ItemKinds.of((com.dnd.model.item.Item) entity);
            Label kindNote = body(itemKind[0].note());
            kindNote.setWrapText(true);
            kindNote.setStyle(EntityForm.HINT_STYLE);
            Runnable rebuild = () -> {
                Class<?> type = entity != null ? entity.getClass() : itemKind[0].javaClass();
                form[0] = EntityForm.of(type, entity, skippedProperties(cat), catalogs);
                formHolder.getChildren().setAll(form[0].getNode());
                kindNote.setText(itemKind[0].note());
            };
            if (isNew) {
                ComboBox<com.dnd.ui.components.ItemKinds.Kind> kinds = new ComboBox<>(
                    javafx.collections.FXCollections.observableArrayList(com.dnd.ui.components.ItemKinds.ALL));
                kinds.setValue(itemKind[0]);
                kinds.valueProperty().addListener((obs, o, n) -> {
                    if (n == null) return;
                    itemKind[0] = n;
                    rebuild.run();
                });
                HBox kindRow = new HBox(10, body("Item kind:"), kinds);
                kindRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                content.getChildren().add(kindRow);
            } else {
                content.getChildren().add(body("Item kind: " + itemKind[0].label()));
            }
            content.getChildren().add(kindNote);
            rebuild.run();
        } else {
            form[0] = EntityForm.of(getEntityClass(cat), entity, skippedProperties(cat), catalogs);
            formHolder.getChildren().setAll(form[0].getNode());
        }
        content.getChildren().add(formHolder);

        Label errorLabel = body("");
        errorLabel.setStyle("-fx-text-fill: #e07070;");
        errorLabel.setWrapText(true);

        Button saveBtn = null;
        if (uiSession.isDm()) {
            saveBtn = btn("Save", () -> {
                try {
                    Object target = isNew
                        ? (itemKind[0] != null ? itemKind[0].create() : createNewEntity(cat)) : entity;
                    form[0].writeTo(target);
                    setImagePath(target, newImagePath[0]);
                    // Width/height are edited here as plain fields, but only GameMap's own
                    // constructor builds a matching grid - keep grid dimensions in sync so the
                    // map can actually be opened afterwards in the Map Editor/View.
                    if (target instanceof com.dnd.model.world.map.GameMap gm) {
                        gm.ensureGridSize();
                    }
                    saveEntity(repos, cat, target);
                    uiSession.getRouter().goTo(backTarget);
                } catch (Exception ex) {
                    errorLabel.setText("Save failed: " + ex.getMessage());
                }
            });
        }

        scroll.setContent(content);
        root.getChildren().add(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        if (saveBtn != null) {
            // Kept outside the scroll pane so Save stays at the bottom of the window on long forms.
            HBox saveBar = new HBox(16, saveBtn, errorLabel);
            saveBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            saveBar.setPadding(new Insets(10, 60, 10, 60));
            saveBar.setStyle("-fx-background-color: #14142a; -fx-border-color: #4a3c1c; -fx-border-width: 1 0 0 0;");
            HBox.setHgrow(errorLabel, Priority.ALWAYS);
            root.getChildren().add(saveBar);
        }
        return wrapInScene(root);
    }

    private Object getEntity(CampaignRepositories repos, EntityCategory cat, String id) {
        if (cat == null) return null;
        return switch (cat) {
            case PLAYER             -> repos.players().getById(id);
            case NPC                -> repos.npcs().getById(id);
            case MONSTER            -> repos.monsters().getById(id);
            case BEAST              -> repos.beasts().getById(id);
            case ITEM               -> repos.items().getById(id);
            case SPELL              -> repos.spells().getById(id);
            case PLACE              -> repos.places().getById(id);
            case MAP                -> repos.maps().getById(id);
            case CLASS              -> repos.classes().getById(id);
            case RACE               -> repos.races().getById(id);
            case DAMAGE_TYPE        -> repos.damageTypes().getById(id);
            case EFFECT             -> repos.effects().getById(id);
            case LANGUAGE           -> repos.languages().getById(id);
            case ALCHEMY_INGREDIENT -> repos.alchemyIngredients().getById(id);
            case BOOK               -> repos.books().getById(id);
            case DICE               -> repos.dice().getById(id);
        };
    }

    @SuppressWarnings("unchecked")
    private void saveEntity(CampaignRepositories repos, EntityCategory cat, Object entity) {
        if (cat == null) throw new IllegalArgumentException("No category");
        switch (cat) {
            case PLAYER             -> repos.players().save((com.dnd.model.character.PlayerCharacter) entity);
            case NPC                -> repos.npcs().save((com.dnd.model.creature.Npc) entity);
            case MONSTER            -> repos.monsters().save((com.dnd.model.creature.Monster) entity);
            case BEAST              -> repos.beasts().save((com.dnd.model.creature.Beast) entity);
            case ITEM               -> repos.items().save((com.dnd.model.item.Item) entity);
            case SPELL              -> repos.spells().save((com.dnd.model.magic.Spell) entity);
            case PLACE              -> repos.places().save((com.dnd.model.world.Place) entity);
            case MAP                -> repos.maps().save((com.dnd.model.world.map.GameMap) entity);
            case CLASS              -> repos.classes().save((CharacterClass) entity);
            case RACE               -> repos.races().save((CharacterRace) entity);
            case DAMAGE_TYPE        -> repos.damageTypes().save((com.dnd.model.combat.DamageType) entity);
            case EFFECT             -> repos.effects().save((com.dnd.model.combat.Effect) entity);
            case LANGUAGE           -> repos.languages().save((com.dnd.model.world.Language) entity);
            case ALCHEMY_INGREDIENT -> repos.alchemyIngredients().save((com.dnd.model.alchemy.AlchemyIngredient) entity);
            case BOOK               -> repos.books().save((com.dnd.model.item.books.Book) entity);
            case DICE               -> repos.dice().save((com.dnd.model.world.Dice) entity);
        }
    }

    private Object createNewEntity(EntityCategory cat) throws Exception {
        if (cat == null) throw new IllegalArgumentException("No category");
        if (cat == EntityCategory.ITEM) {
            return com.dnd.model.item.books.Book.class.getDeclaredConstructor().newInstance();
        }
        Class<?> cls = switch (cat) {
            case PLAYER             -> com.dnd.model.character.PlayerCharacter.class;
            case NPC                -> com.dnd.model.creature.Npc.class;
            case MONSTER            -> com.dnd.model.creature.Monster.class;
            case BEAST              -> com.dnd.model.creature.Beast.class;
            case SPELL              -> com.dnd.model.magic.Spell.class;
            case PLACE              -> com.dnd.model.world.Place.class;
            case MAP                -> com.dnd.model.world.map.GameMap.class;
            case CLASS              -> CharacterClass.class;
            case RACE               -> CharacterRace.class;
            case DAMAGE_TYPE        -> com.dnd.model.combat.DamageType.class;
            case EFFECT             -> com.dnd.model.combat.Effect.class;
            case LANGUAGE           -> com.dnd.model.world.Language.class;
            case ALCHEMY_INGREDIENT -> com.dnd.model.alchemy.AlchemyIngredient.class;
            case BOOK               -> com.dnd.model.item.books.Book.class;
            case DICE               -> com.dnd.model.world.Dice.class;
            default                 -> throw new IllegalArgumentException("Cannot create: " + cat);
        };
        return cls.getDeclaredConstructor().newInstance();
    }

    /**
     * Properties the generic form must not try to edit.
     *
     * <p>A map's grid, layers, drawings and groups are the Map Editor's job - rendering them as
     * a reflective form would produce thousands of controls. Password material is never shown,
     * and the portrait has its own uploader above the form.</p>
     */
    static Set<String> skippedProperties(EntityCategory cat) {
        Set<String> skipped = new LinkedHashSet<>(Set.of("imagePath", "passwordHash", "passwordSalt"));
        if (cat == EntityCategory.MAP) {
            skipped.addAll(Set.of("grid", "layers", "drawings", "groups"));
        }
        return skipped;
    }

    /**
     * Id options per property name, so reference fields render as name drop-downs (or tick lists
     * for id collections) instead of asking the DM to type a raw id.
     */
    static Map<String, List<EntityForm.Ref>> catalogsFor(CampaignRepositories repos) {
        Map<String, List<EntityForm.Ref>> catalogs = new LinkedHashMap<>();
        catalogs.put("classId", refs(repos.classes().list()));
        catalogs.put("raceId", refs(repos.races().list()));
        catalogs.put("mapId", refs(repos.maps().list()));
        catalogs.put("itemId", refs(repos.items().list()));
        catalogs.put("spellId", refs(repos.spells().list()));
        catalogs.put("typeId", refs(repos.damageTypes().list()));
        catalogs.put("diceId", refs(repos.dice().list()));
        catalogs.put("languages", refs(repos.languages().list()));
        catalogs.put("effects", refs(repos.effects().list()));
        catalogs.put("triggeredEffects", refs(repos.effects().list()));
        catalogs.put("productId", refs(repos.items().list()));
        catalogs.put("toolIds", refs(repos.items().list().stream()
            .filter(i -> i instanceof com.dnd.model.item.crafting.Tool).toList()));
        catalogs.put("knownBlueprints", refs(repos.items().list().stream()
            .filter(i -> i instanceof com.dnd.model.item.crafting.Blueprint).toList()));
        catalogs.put("defaultSpells", refs(repos.spells().list()));
        catalogs.put("defaultItems", refs(repos.items().list()));
        return catalogs;
    }

    private static List<EntityForm.Ref> refs(List<?> entities) {
        List<EntityForm.Ref> refs = new ArrayList<>();
        for (Object entity : entities) {
            String id = invokeString(entity, "getId");
            if (id != null) {
                refs.add(new EntityForm.Ref(id, invokeString(entity, "getName")));
            }
        }
        return refs;
    }

    private static String invokeString(Object entity, String method) {
        try {
            Object value = entity.getClass().getMethod(method).invoke(entity);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Class<?> getEntityClass(EntityCategory cat) {
        if (cat == null) return null;
        return switch (cat) {
            case PLAYER             -> com.dnd.model.character.PlayerCharacter.class;
            case NPC                -> com.dnd.model.creature.Npc.class;
            case MONSTER            -> com.dnd.model.creature.Monster.class;
            case BEAST              -> com.dnd.model.creature.Beast.class;
            case ITEM               -> com.dnd.model.item.Item.class;
            case SPELL              -> com.dnd.model.magic.Spell.class;
            case PLACE              -> com.dnd.model.world.Place.class;
            case MAP                -> com.dnd.model.world.map.GameMap.class;
            case CLASS              -> CharacterClass.class;
            case RACE               -> CharacterRace.class;
            case DAMAGE_TYPE        -> com.dnd.model.combat.DamageType.class;
            case EFFECT             -> com.dnd.model.combat.Effect.class;
            case LANGUAGE           -> com.dnd.model.world.Language.class;
            case ALCHEMY_INGREDIENT -> com.dnd.model.alchemy.AlchemyIngredient.class;
            case BOOK               -> com.dnd.model.item.books.Book.class;
            case DICE               -> com.dnd.model.world.Dice.class;
        };
    }

    private String getImagePath(Object entity) {
        try { return (String) entity.getClass().getMethod("getImagePath").invoke(entity); }
        catch (Exception e) { return null; }
    }

    private void setImagePath(Object entity, String path) {
        try { entity.getClass().getMethod("setImagePath", String.class).invoke(entity, path); }
        catch (Exception ignored) {}
    }

}
