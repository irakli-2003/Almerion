package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.ui.EntityCategory;
import com.dnd.ui.ImageStore;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import com.dnd.ui.i18n.LanguagePicker;
import com.dnd.ui.i18n.UiTranslator;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.util.List;

public class DmMenuScene extends BaseScene {

    /** Every entity category managed in the Worldbuilding section (everything except players). */

    private static final double CARD_WIDTH = 170;
    private static final double PORTRAIT_SIZE = 150;

    public DmMenuScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(SceneType.CAMPAIGN_SELECTION));

        VBox content = new VBox(20);
        content.setPadding(new Insets(20, 60, 40, 60));
        content.setAlignment(Pos.TOP_LEFT);

        String campaignName = uiSession.getSession().getCampaignContext() != null
            ? uiSession.getSession().getCampaignContext().getName() : "(none)";

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(title("Game Master of campaign:"), UiTranslator.skip(title(campaignName)));
        if (uiSession.getSession().isOnline()) {
            Label badge = new Label("● LIVE");
            badge.getStyleClass().add("success-badge");
            header.getChildren().add(badge);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(spacer, new LanguagePicker());
        content.getChildren().add(header);

        content.getChildren().add(sessionSection());
        content.getChildren().add(musicSection());
        content.getChildren().add(playersSection());
        content.getChildren().add(worldbuildingSection());

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: #1a1a2e; -fx-background-color: #1a1a2e;");
        root.getChildren().add(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapInScene(root);
    }

    /** A boxed card with a title, so each area of the DM page reads as its own section. */
    private VBox card(String title, Node... children) {
        VBox card = new VBox(10);
        card.getStyleClass().add("dm-card");
        card.getChildren().add(sectionLabel(title));
        card.getChildren().addAll(children);
        return card;
    }

    /** "Run the Session": everything used while actually playing at the table. */
    private VBox sessionSection() {
        FlowPane row = new FlowPane(10, 10,
            btn("📜 Open Storyline", () -> uiSession.getRouter().goTo(SceneType.STORYLINE)),
            btn("👥 Members", () -> MembersWindow.open(this, uiSession.campaignRoot(),
                uiSession.getCurrentUser() == null ? "" : uiSession.getCurrentUser().getUsername())),
            btn("🖨 Print Character Sheets", () -> {
                if (uiSession.campaignRoot() == null) return;
                var ctx = uiSession.getSession().getCampaignContext();
                com.dnd.ui.print.CharacterSheetPrintDialog.show(
                    com.dnd.ui.WindowOrder.current(), new CampaignRepositories(uiSession.campaignRoot()), ctx != null ? ctx.getName() : null);
            }),
            btn("🌐 Online Session" + (uiSession.getSession().isOnline() ? "  ●" : ""),
                () -> uiSession.getRouter().goTo(SceneType.DM_ONLINE_SESSION)));
        return card("▶ Run the Session", row);
    }

    /** "Music": playback buttons usable without opening the music panel. */
    private VBox musicSection() {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(com.dnd.ui.components.MusicQuickControls.create());
        row.getChildren().add(btn("Music Panel", () -> MusicPanel.open(this, uiSession.campaignRoot())));
        return card("🎵 Music", row);
    }

    /** Worldbuilding categories, grouped by what they describe. */
    private static final String[] WORLDBUILDING_GROUP_NAMES = {
        "Creatures", "Places", "Items & Lore", "Magic & Rules", "Peoples"
    };
    private static final EntityCategory[][] WORLDBUILDING_GROUPS = {
        {EntityCategory.NPC, EntityCategory.MONSTER, EntityCategory.BEAST},
        {EntityCategory.PLACE, EntityCategory.MAP},
        {EntityCategory.ITEM, EntityCategory.ALCHEMY_INGREDIENT, EntityCategory.BOOK},
        {EntityCategory.SPELL, EntityCategory.EFFECT, EntityCategory.DAMAGE_TYPE, EntityCategory.DICE},
        {EntityCategory.CLASS, EntityCategory.RACE, EntityCategory.LANGUAGE}
    };

    /**
     * "Worldbuilding": one compact button per non-player entity category, in labeled groups.
     * Groups and buttons wrap with the window width, so the section never forces horizontal
     * scrolling and stays only a couple of rows high.
     */
    private VBox worldbuildingSection() {
        FlowPane groups = new FlowPane(10, 10);
        for (int i = 0; i < WORLDBUILDING_GROUPS.length; i++) {
            Label caption = new Label(WORLDBUILDING_GROUP_NAMES[i]);
            caption.getStyleClass().add("sub-label");
            HBox buttons = new HBox(6);
            for (EntityCategory cat : WORLDBUILDING_GROUPS[i]) {
                buttons.getChildren().add(btn(categoryLabel(cat), () -> {
                    uiSession.setActiveEntityCategory(cat);
                    uiSession.getRouter().goTo(SceneType.ENTITY_LIST);
                }));
            }
            VBox group = new VBox(5, caption, buttons);
            group.getStyleClass().addAll("dm-subgroup", "tool-group");
            groups.getChildren().add(group);
        }
        VBox card = card("🏰 Worldbuilding", groups);
        groups.prefWrapLengthProperty().bind(card.widthProperty().subtract(30));
        return card;
    }

    /** "Players": horizontally scrollable row of mini character cards, with scroll arrows. */
    private VBox playersSection() {
        VBox section = new VBox(10);

        HBox cardRow = new HBox(10);
        cardRow.setPadding(new Insets(4));

        if (uiSession.campaignRoot() != null) {
            CampaignRepositories repos = new CampaignRepositories(uiSession.campaignRoot());
            List<PlayerCharacter> players = repos.players().list();
            for (PlayerCharacter pc : players) {
                cardRow.getChildren().add(playerCard(pc, repos));
            }
            if (players.isEmpty()) {
                cardRow.getChildren().add(body("No player characters yet."));
            }
        }

        ScrollPane scroll = new ScrollPane(cardRow);
        scroll.setFitToHeight(true);
        scroll.setPrefViewportHeight(340);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background: #1a1a2e; -fx-background-color: #1a1a2e;");

        Button left = btn("◀", () -> scroll.setHvalue(Math.max(0, scroll.getHvalue() - 0.2)));
        Button right = btn("▶", () -> scroll.setHvalue(Math.min(1, scroll.getHvalue() + 0.2)));
        left.setStyle("-fx-min-width: 44px; -fx-padding: 8 12 8 12;");
        right.setStyle("-fx-min-width: 44px; -fx-padding: 8 12 8 12;");

        HBox scrollRow = new HBox(8, left, scroll, right);
        scrollRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(scroll, Priority.ALWAYS);

        Button createCharacterBtn = btn("Create Character", () -> {
            if (uiSession.campaignRoot() != null) {
                new CharacterCreationWizard(uiSession, new CampaignRepositories(uiSession.campaignRoot())).show();
            }
        });

        Label heading = sectionLabel("☻ Players");
        HBox headingRow = new HBox(14, heading, createCharacterBtn);
        headingRow.setAlignment(Pos.CENTER_LEFT);
        section.getChildren().add(scrollRow);
        VBox card = new VBox(10, headingRow, section);
        card.getStyleClass().add("dm-card");
        return card;
    }

    private VBox playerCard(PlayerCharacter pc, CampaignRepositories repos) {
        ImageView img = new ImageView(ImageStore.loadOrPlaceholder(uiSession.campaignRoot(), pc.getImagePath()));
        img.setFitWidth(PORTRAIT_SIZE);
        img.setFitHeight(PORTRAIT_SIZE);
        img.setSmooth(true);
        img.setPreserveRatio(true);

        String className = lookupName(repos.classes().list(), pc.getClassId(), CharacterClass::getId, CharacterClass::getName);
        String raceName = lookupName(repos.races().list(), pc.getRaceId(), CharacterRace::getId, CharacterRace::getName);

        Label name = UiTranslator.skip(smallLabel(pc.getName() != null ? pc.getName() : pc.getId(), true));
        Label level = smallLabel("Lvl " + pc.getLevel(), false);
        Label cls = smallLabel(className, false);
        Label race = smallLabel(raceName, false);
        Label player = smallLabel(pc.getPlayerName() != null && !pc.getPlayerName().isBlank()
            ? "Player: " + pc.getPlayerName() : "Player: -", false);

        Label hp = smallLabel("HP " + pc.getCurrentHitPoints() + "/" + pc.getMaxHitPoints(), false);
        VBox card = new VBox(3, img, name, level, cls, race, hp, player);
        card.setAlignment(Pos.TOP_CENTER);
        card.setPadding(new Insets(8));
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        card.getStyleClass().add("token-box");
        card.setStyle(card.getStyle() + " -fx-cursor: hand;");
        card.setOnMouseClicked(e -> {
            uiSession.setActiveEntityCategory(EntityCategory.PLAYER);
            uiSession.setActiveEntityId(pc.getId());
            uiSession.getRouter().goTo(SceneType.ENTITY_DETAIL);
        });
        return card;
    }

    private Label smallLabel(String text, boolean emphasis) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(CARD_WIDTH - 8);
        l.setStyle("-fx-text-fill: " + (emphasis ? "#c9a84c" : "#a89060") + "; -fx-font-size: "
            + (emphasis ? "13px" : "11px") + "; -fx-text-alignment: center;");
        return l;
    }

    private <T> String lookupName(List<T> options, String id, java.util.function.Function<T, String> idFn, java.util.function.Function<T, String> nameFn) {
        if (id == null) return "-";
        return options.stream()
            .filter(o -> id.equals(idFn.apply(o)))
            .map(nameFn)
            .findFirst()
            .orElse(id);
    }

    private String categoryLabel(EntityCategory cat) {
        return switch (cat) {
            case NPC -> "NPCs";
            case MONSTER -> "Monsters";
            case BEAST -> "Beasts";
            case ITEM -> "Items";
            case SPELL -> "Spells";
            case PLACE -> "Places";
            case MAP -> "Maps";
            case CLASS -> "Classes";
            case RACE -> "Races";
            case DAMAGE_TYPE -> "Damage Types";
            case EFFECT -> "Effects";
            case LANGUAGE -> "Languages";
            case ALCHEMY_INGREDIENT -> "Alchemy Ingredients";
            case BOOK -> "Books";
            case DICE -> "Dice";
            default -> cat.name();
        };
    }
}

