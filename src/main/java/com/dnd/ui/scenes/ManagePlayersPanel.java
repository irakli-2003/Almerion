package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.SessionRosterStore;
import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.combat.Effect;
import com.dnd.model.item.Item;
import com.dnd.model.rules.FeatureRules;
import com.dnd.model.session.TrackedCreature;
import com.dnd.model.world.map.ActiveEffect;
import com.dnd.model.world.map.CombatState;
import com.dnd.model.world.map.TokenSupport;
import com.dnd.ui.ImageStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import com.dnd.model.crafting.Workshop;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The text editor's right-hand "Manage Players" panel: a vertical list of cards - every
 * player character plus the monsters, NPCs and beasts the DM added to this session file -
 * showing HP and mana at a glance with quick damage / heal buttons. Clicking a card opens
 * that creature's details in the same panel; "Back" returns to the list.
 */
final class ManagePlayersPanel {

    static final double WIDTH = 380;

    private final BaseScene owner;
    private final CampaignRepositories repos;
    private final Path sessionFile;
    private final Consumer<String> status;

    private final VBox root = new VBox();
    private final List<TrackedCreature> creatures;
    private Spinner<Integer> amount;
    private double listScroll;
    private VBox cardsBox;
    private Node listPane;
    private Runnable xpToggle = () -> { };
    private int lastXp = 100;

    ManagePlayersPanel(BaseScene owner, CampaignRepositories repos, Path sessionFile, Consumer<String> status) {
        this.owner = owner;
        this.repos = repos;
        this.sessionFile = sessionFile;
        this.status = status;
        this.creatures = SessionRosterStore.load(sessionFile);
        root.getStyleClass().add("side-panel");
        root.setPrefWidth(WIDTH);
        root.setMinWidth(WIDTH);
        root.setMaxWidth(WIDTH);
    }

    Node node() {
        return root;
    }

    List<TrackedCreature> creatures() {
        return creatures;
    }

    private void saveCreatures() {
        try {
            SessionRosterStore.save(sessionFile, creatures);
        } catch (RuntimeException ex) {
            status.accept("Couldn't save the session's creatures: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ list

    void showList() {
        Label heading = owner.sectionLabel("Party & Creatures");
        VBox addHost = new VBox();
        VBox xpHost = new VBox();
        Button add = smallBtn("+ Add", () -> { xpHost.getChildren().clear(); toggle(addHost, () -> creaturePicker(addHost)); });
        add.setTooltip(new Tooltip("Add a player, monster, NPC or beast to this session"));
        Button xp = smallBtn("★ XP", () -> { addHost.getChildren().clear(); toggle(xpHost, () -> awardXpForm(xpHost)); });
        xp.setTooltip(new Tooltip("Award experience to one or more players"));
        xpToggle = () -> {
            addHost.getChildren().clear();
            if (xpHost.getChildren().isEmpty()) xpHost.getChildren().setAll(awardXpForm(xpHost));
        };
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, heading, spacer, xp, add);
        header.setAlignment(Pos.CENTER_LEFT);

        int previous = amount == null ? 5 : amount.getValue();
        amount = new Spinner<>(1, 999, previous);
        amount.setEditable(true);
        amount.setPrefWidth(80);
        Label amountLabel = owner.body("Amount for − / + :");
        HBox amountRow = new HBox(8, amountLabel, amount);
        amountRow.setAlignment(Pos.CENTER_LEFT);

        cardsBox = new VBox(8);
        refreshCards();

        ScrollPane scroll = scroll(cardsBox);
        listPane = scroll;
        double restore = listScroll;
        scroll.vvalueProperty().addListener((o, old, v) -> listScroll = v.doubleValue());
        javafx.application.Platform.runLater(() -> scroll.setVvalue(restore));

        VBox top = new VBox(8, header, addHost, xpHost, amountRow);
        top.setPadding(new Insets(10, 10, 6, 10));
        root.getChildren().setAll(top, scroll);
    }

    /** Opens the list with the Award XP form showing (used by the editor's toolbar button). */
    void showAwardXp() {
        showList();
        xpToggle.run();
    }

    private void refreshCards() {
        VBox cards = cardsBox;
        cards.getChildren().clear();
        cards.getChildren().add(subLabel("Players"));
        List<PlayerCharacter> players = repos.players().list();
        if (players.isEmpty()) cards.getChildren().add(hint("No player characters yet - use + Add."));
        for (PlayerCharacter pc : players) cards.getChildren().add(playerCard(pc));

        cards.getChildren().add(subLabel("Creatures in this session"));
        if (creatures.isEmpty()) cards.getChildren().add(hint("Add monsters, NPCs or beasts with + Add."));
        List<TrackedCreature> sorted = new ArrayList<>(creatures);
        sorted.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(nameOf(a), nameOf(b)));
        for (TrackedCreature c : sorted) cards.getChildren().add(creatureCard(c));
    }

    /** True while the card list (rather than a detail view) is what the panel shows. */
    private boolean listShowing() {
        return listPane != null && root.getChildren().contains(listPane);
    }

    /** One searchable list of every monster, NPC and beast in the catalog; each click adds one more. */
    private Node creaturePicker(VBox host) {
        record Entry(TrackedCreature.Kind kind, String id, String name, int hp, int mana, int ac, String image) { }
        List<Entry> all = new ArrayList<>();
        repos.monsters().list().forEach(m -> all.add(new Entry(TrackedCreature.Kind.MONSTER, m.getId(), m.getName(),
            m.getMaxHitPoints(), m.getMaxMana(), m.getArmorClass(), m.getImagePath())));
        repos.npcs().list().forEach(n -> all.add(new Entry(TrackedCreature.Kind.NPC, n.getId(), n.getName(),
            n.getMaxHitPoints(), n.getMaxMana(), n.getArmorClass(), n.getImagePath())));
        repos.beasts().list().forEach(b -> all.add(new Entry(TrackedCreature.Kind.BEAST, b.getId(), b.getName(),
            b.getMaxHitPoints(), b.getMaxMana(), b.getArmorClass(), b.getImagePath())));
        all.sort((x, y) -> String.CASE_INSENSITIVE_ORDER.compare(x.name() == null ? "" : x.name(), y.name() == null ? "" : y.name()));
        return picker(host, "Search monsters, NPCs, beasts... (Enter adds)", all,
            e -> e.name() + "   · " + kindLabel(e.kind()) + "  · HP " + e.hp(),
            e -> addCreature(new TrackedCreature(e.kind(), e.id(), e.name(), e.hp(), e.mana(), e.ac(), e.image())),
            "✚ New player...", () -> new CharacterCreationWizard(owner.uiSession, repos).show(this::showList));
    }

    private static String kindLabel(TrackedCreature.Kind kind) {
        return switch (kind) {
            case MONSTER -> "Monster";
            case NPC -> "NPC";
            case BEAST -> "Beast";
        };
    }

    /** Amount + a tick box per player: XP for the whole party (or just some) in one go. */
    private Node awardXpForm(VBox host) {
        Spinner<Integer> xp = new Spinner<>(1, 1_000_000, lastXp, 50);
        xp.setEditable(true);
        xp.setPrefWidth(110);
        HBox amountRow = new HBox(8, owner.body("XP each:"), xp);
        amountRow.setAlignment(Pos.CENTER_LEFT);

        List<PlayerCharacter> players = repos.players().list();
        List<CheckBox> boxes = new ArrayList<>();
        FlowPane who = new FlowPane(10, 6);
        for (PlayerCharacter pc : players) {
            CheckBox cb = new CheckBox(pc.getName());
            cb.setUserData(pc.getId());
            cb.setSelected(true);
            cb.setStyle("-fx-text-fill: #e8dcc0;");
            boxes.add(cb);
            who.getChildren().add(cb);
        }
        CheckBox everyone = new CheckBox("Everyone");
        everyone.setSelected(true);
        everyone.setStyle("-fx-text-fill: #c9a84c;");
        everyone.setOnAction(e -> boxes.forEach(cb -> cb.setSelected(everyone.isSelected())));

        Button award = smallBtn("Award", () -> {
            try {
                xp.getValueFactory().setValue(Integer.parseInt(xp.getEditor().getText().trim()));
            } catch (NumberFormatException ignored) {
                xp.getEditor().setText(String.valueOf(xp.getValue()));
            }
            int amountXp = xp.getValue();
            lastXp = amountXp;
            List<String> given = new ArrayList<>();
            for (CheckBox cb : boxes) {
                if (!cb.isSelected()) continue;
                PlayerCharacter pc = repos.players().getById((String) cb.getUserData());
                if (pc == null) continue;
                pc.addXp(amountXp);
                repos.players().save(pc);
                given.add(pc.getName() + " (" + pc.getXp() + ")");
            }
            if (given.isEmpty()) {
                status.accept("Tick at least one player.");
                return;
            }
            status.accept("+" + amountXp + " XP → " + String.join(", ", given));
            host.getChildren().clear();
            refreshCards();
        });
        Button cancel = smallBtn("Cancel", () -> host.getChildren().clear());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(6, award, spacer, cancel);
        VBox box = new VBox(6, subLabel("Award XP"), amountRow, everyone, who, actions);
        if (players.isEmpty()) box.getChildren().setAll(subLabel("Award XP"), hint("No player characters yet."), cancel);
        box.getStyleClass().add("picker-box");
        javafx.application.Platform.runLater(xp.getEditor()::requestFocus);
        return box;
    }

    void addCreature(TrackedCreature creature) {
        String base = creature.getName() == null ? "Creature" : creature.getName();
        long same = creatures.stream().filter(c -> base.equals(c.getName())
            || (c.getName() != null && c.getName().matches(java.util.regex.Pattern.quote(base) + " \\d+"))).count();
        if (same > 0) creature.setName(base + " " + (same + 1));
        creatures.add(creature);
        saveCreatures();
        status.accept(creature.getName() + " added to this session.");
        if (listShowing()) refreshCards(); else showList();
    }

    private Node playerCard(PlayerCharacter pc) {
        CharacterClass cls = pc.getClassId() == null ? null : repos.classes().getById(pc.getClassId());
        String subtitle = "Lvl " + pc.getLevel() + (cls != null ? " " + cls.getName() : "")
            + (pc.getPlayerName() != null && !pc.getPlayerName().isBlank() ? "  ·  " + pc.getPlayerName() : "");
        return card(pc.getName(), subtitle, pc.getImagePath(),
            pc.getCurrentHitPoints(), pc.getMaxHitPoints(), pc.getCurrentMana(), pc.getMaxMana(),
            pc.getActiveEffects(),
            delta -> {
                pc.setCurrentHitPoints(Math.max(0, Math.min(pc.getMaxHitPoints(), pc.getCurrentHitPoints() + delta)));
                repos.players().save(pc);
                showList();
            },
            () -> showPlayer(pc));
    }

    private Node creatureCard(TrackedCreature c) {
        String kind = c.getKind() == null ? "Creature" : switch (c.getKind()) {
            case MONSTER -> "Monster";
            case NPC -> "NPC";
            case BEAST -> "Beast";
        };
        return card(nameOf(c), kind + (c.getArmorClass() > 0 ? "  ·  AC " + c.getArmorClass() : ""), c.getImagePath(),
            c.getCurrentHitPoints(), c.getMaxHitPoints(), c.getCurrentMana(), c.getMaxMana(),
            c.getActiveEffects(),
            delta -> {
                c.setCurrentHitPoints(Math.max(0, Math.min(c.getMaxHitPoints(), c.getCurrentHitPoints() + delta)));
                saveCreatures();
                showList();
            },
            () -> showCreature(c));
    }

    private Node card(String name, String subtitle, String imagePath, int hp, int maxHp, int mana, int maxMana,
                      List<ActiveEffect> effects, IntConsumer adjustHp, Runnable open) {
        ImageView img = new ImageView(ImageStore.loadOrPlaceholder(owner.uiSession == null ? null : owner.uiSession.campaignRoot(), imagePath));
        img.setFitWidth(44);
        img.setFitHeight(44);
        img.setPreserveRatio(true);

        Label nameLabel = new Label(name == null ? "?" : name);
        nameLabel.getStyleClass().add("card-name");
        Label sub = new Label(subtitle);
        sub.getStyleClass().add("muted-label");

        VBox info = new VBox(3, nameLabel, sub, bar("HP", hp, maxHp, hp * 4 <= maxHp ? "#c03a3a" : "#4a9a3a"));
        if (maxMana > 0) info.getChildren().add(bar("MP", mana, maxMana, "#3a6ac0"));
        if (effects != null && !effects.isEmpty()) {
            StringBuilder sb = new StringBuilder("✦ ");
            for (int i = 0; i < effects.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(effects.get(i).getName()).append(" (").append(effects.get(i).getRemainingRounds()).append(")");
            }
            Label fx = new Label(sb.toString());
            fx.getStyleClass().add("card-effects");
            fx.setWrapText(true);
            info.getChildren().add(fx);
        }
        HBox.setHgrow(info, Priority.ALWAYS);

        Button hurt = quick("−", "Damage by the amount above", () -> adjustHp.accept(-amount.getValue()));
        Button heal = quick("+", "Heal by the amount above", () -> adjustHp.accept(amount.getValue()));
        VBox quick = new VBox(4, hurt, heal);
        quick.setAlignment(Pos.CENTER);

        HBox card = new HBox(10, img, info, quick);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("creature-card");
        if (maxHp > 0 && hp <= 0) card.getStyleClass().add("creature-card-down");
        card.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getTarget() instanceof Node n && isInside(n, quick)) return;
            open.run();
        });
        return card;
    }

    private static boolean isInside(Node node, Node container) {
        for (Node n = node; n != null; n = n.getParent()) if (n == container) return true;
        return false;
    }

    private Node bar(String label, int value, int max, String color) {
        ProgressBar bar = new ProgressBar(max <= 0 ? 0 : Math.max(0, Math.min(1, value / (double) max)));
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(12);
        bar.setStyle("-fx-accent: " + color + ";");
        HBox.setHgrow(bar, Priority.ALWAYS);
        Label text = new Label(label + " " + value + "/" + max);
        text.getStyleClass().add("bar-label");
        text.setMinWidth(78);
        HBox row = new HBox(6, text, bar);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button quick(String text, String tip, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add(text.equals("−") ? "danger-button" : "dnd-button");
        b.setStyle("-fx-min-width: 30; -fx-pref-width: 30; -fx-padding: 2 0 2 0; -fx-font-size: 14px;");
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> { action.run(); e.consume(); });
        return b;
    }

    // --------------------------------------------------------------- details

    private VBox detailFrame(String name, Node... body) {
        Button back = new Button("← Back");
        back.getStyleClass().add("dnd-button");
        back.setStyle("-fx-min-width: 0; -fx-padding: 5 12 5 12;");
        back.setOnAction(e -> showList());
        Label title = owner.sectionLabel(name);
        title.setWrapText(true);
        HBox header = new HBox(10, back, title);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 10, 6, 10));

        VBox content = new VBox(12, body);
        root.getChildren().setAll(header, scroll(content));
        return content;
    }

    void showPlayer(PlayerCharacter pc) {
        Runnable persist = () -> repos.players().save(pc);

        GridPane vitals = vitalsGrid(
            pc.getCurrentHitPoints(), v -> { pc.setCurrentHitPoints(v); persist.run(); },
            pc.getMaxHitPoints(), v -> { pc.setMaxHitPoints(v); persist.run(); },
            pc.getCurrentMana(), v -> { pc.setCurrentMana(v); persist.run(); },
            pc.getMaxMana(), v -> { pc.setMaxMana(v); persist.run(); });

        VBox itemsBox = new VBox(6);
        VBox itemPicker = new VBox();
        Button addItem = smallBtn("+ Add Item", () -> toggle(itemPicker, () -> itemPicker(itemPicker, item -> {
            List<PlayerCharacter.PlayerItem> items = pc.getItems();
            if (items == null) {
                items = new ArrayList<>();
                pc.setItems(items);
            }
            PlayerCharacter.PlayerItem stack = items.stream()
                .filter(i -> i != null && item.getId().equals(i.getItemId())).findFirst().orElse(null);
            if (stack != null) stack.setQuantity(stack.getQuantity() + 1);
            else items.add(new PlayerCharacter.PlayerItem(item.getId(), new PlayerCharacter.ItemCondition(100), false));
            persist.run();
            refreshItems(itemsBox, pc, persist);
            status.accept(item.getName() + " given to " + pc.getName() + ".");
        })));

        VBox effectsBox = new VBox(6);
        Runnable refreshEffects = new Runnable() {
            @Override
            public void run() {
                fillEffects(effectsBox, pc.getActiveEffects(), effect -> {
                    pc.clearEffect(effect);
                    persist.run();
                    this.run();
                }, persist);
            }
        };
        VBox effectPicker = new VBox();
        Button addEffect = smallBtn("+ Add Effect", () -> toggle(effectPicker, () -> effectPicker(effectPicker, effect -> {
            pc.addEffect(effect);
            persist.run();
            refreshEffects.run();
        })));

        refreshItems(itemsBox, pc, persist);
        refreshEffects.run();

        detailFrame(pc.getName(),
            ProgressionPanel.build(owner, repos, pc, () -> showPlayer(pc)),
            subLabel("Vitals"), vitals,
            subLabel("Class & Race Features"), featuresBox(pc, persist),
            workshopBox(pc, persist),
            headerRow("Items", addItem), itemPicker, itemsBox,
            headerRow("Active Effects", addEffect), effectPicker, effectsBox);
    }

    /**
     * The artificer's workshop: energy charges, the furnace (burn fuel, make charges) and the
     * blueprint book (learn carried blueprints, build from known ones). Hidden for characters
     * with no charges, no blueprints and no workshop tools.
     */
    private Node workshopBox(PlayerCharacter pc, Runnable persist) {
        java.util.function.Function<String, Item> items = repos.items()::getById;
        boolean energy = pc.getCastingResource() == com.dnd.model.magic.CastingResource.ENERGY_CHARGES;
        int tier = Workshop.furnaceTier(pc, items);
        List<com.dnd.model.item.crafting.Blueprint> carriedPlans = new ArrayList<>();
        List<com.dnd.model.item.crafting.Fuel> fuels = new ArrayList<>();
        if (pc.getItems() != null) {
            for (PlayerCharacter.PlayerItem carried : pc.getItems()) {
                Item item = carried == null ? null : items.apply(carried.getItemId());
                if (item instanceof com.dnd.model.item.crafting.Blueprint bp && !carriedPlans.contains(bp)) carriedPlans.add(bp);
                if (item instanceof com.dnd.model.item.crafting.Fuel f && !fuels.contains(f)) fuels.add(f);
            }
        }
        if (!energy && tier == 0 && carriedPlans.isEmpty() && pc.getKnownBlueprints().isEmpty()) return new VBox();

        Runnable redraw = () -> {
            persist.run();
            showPlayer(pc);
        };
        VBox box = new VBox(6);
        box.getChildren().add(subLabel("Workshop & Blueprint Book"));

        // Charges
        String charges = com.dnd.model.magic.EnergyCharges.describe(pc.getSpellSlots());
        box.getChildren().add(owner.body("⚡ Energy charges: " + (charges.isBlank() ? "none yet" : charges)));

        // Furnace
        box.getChildren().add(hint(tier == 0
            ? "No workshop furnace in the pack - carry a tool with a furnace tier to make charges."
            : "🔥 Furnace makes charges up to " + com.dnd.model.magic.EnergyCharges.name(tier)
                + " · fuel burning: " + pc.getFurnaceFuel() + " units"));
        if (tier > 0) {
            javafx.scene.layout.FlowPane burnRow = new javafx.scene.layout.FlowPane(6, 6);
            for (com.dnd.model.item.crafting.Fuel fuel : fuels) {
                Button burn = smallBtn("Burn " + fuel.getName() + " (+" + fuel.getFuelValue() + ")", () -> {
                    int units = Workshop.burn(pc, fuel.getId(), 1, items);
                    status.accept(pc.getName() + " feeds " + fuel.getName() + " into the furnace (+" + units + " units).");
                    redraw.run();
                });
                burn.setTooltip(new Tooltip("Uses one " + fuel.getName() + " from the pack"));
                burnRow.getChildren().add(burn);
            }
            if (fuels.isEmpty()) burnRow.getChildren().add(hint("No fuel carried (wood, charcoal, coal, oil...)."));
            box.getChildren().add(burnRow);

            javafx.scene.layout.FlowPane chargeRow = new javafx.scene.layout.FlowPane(6, 6);
            for (com.dnd.model.magic.SpellSlot slot : pc.getSpellSlots()) {
                if (slot == null || slot.getMax() <= 0) continue;
                int level = slot.getLevel();
                String chargeName = com.dnd.model.magic.EnergyCharges.name(level);
                Button make = smallBtn("+1 " + chargeName + " (" + com.dnd.model.magic.EnergyCharges.fuelCost(level) + " fuel)", () -> {
                    String problem = Workshop.makeCharge(pc, level, items);
                    status.accept(problem == null ? pc.getName() + " charges a " + chargeName + " cell." : problem);
                    redraw.run();
                });
                String problem = Workshop.chargeProblem(pc, level, items);
                make.setDisable(problem != null);
                make.setTooltip(new Tooltip((problem == null ? "" : problem + "\n") + com.dnd.model.magic.EnergyCharges.note(level)));
                chargeRow.getChildren().add(make);
            }
            box.getChildren().add(chargeRow);
        }

        // Learn carried blueprints
        for (com.dnd.model.item.crafting.Blueprint bp : carriedPlans) {
            if (Workshop.knows(pc, bp.getId())) continue;
            Button learn = smallBtn("📖 Copy \"" + bp.getName() + "\" into the book", () -> {
                Workshop.learn(pc, bp.getId());
                status.accept(pc.getName() + " copies " + bp.getName() + " into the blueprint book.");
                redraw.run();
            });
            box.getChildren().add(learn);
        }

        // Known blueprints
        if (pc.getKnownBlueprints().isEmpty()) {
            box.getChildren().add(hint("The blueprint book is empty. Carry a blueprint and copy it in."));
        }
        for (String id : new ArrayList<>(pc.getKnownBlueprints())) {
            Item item = items.apply(id);
            if (!(item instanceof com.dnd.model.item.crafting.Blueprint bp)) {
                box.getChildren().add(hint("(missing blueprint " + id + ")"));
                continue;
            }
            List<String> problems = Workshop.problems(pc, bp, items);
            Label name = owner.body((problems.isEmpty() ? "✔ " : "• ") + bp.getName()
                + (bp.getCategory() == null || bp.getCategory().isBlank() ? "" : "   · " + bp.getCategory()));
            name.setWrapText(true);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            Button build = quick("🔧", "Build " + bp.getName(), () -> {
                try {
                    List<String> log = Workshop.craft(pc, bp, items);
                    status.accept(pc.getName() + " " + String.join(", ", log) + ".");
                } catch (IllegalStateException ex) {
                    status.accept(ex.getMessage());
                }
                redraw.run();
            });
            build.setDisable(!problems.isEmpty());
            Button forget = quick("✖", "Tear out of the book", () -> {
                Workshop.forget(pc, id);
                redraw.run();
            });
            HBox row = new HBox(6, name, build, forget);
            row.setAlignment(Pos.CENTER_LEFT);
            Label needs = hint(blueprintNeeds(bp) + (problems.isEmpty() ? "" : "\n⚠ " + String.join(" ", problems)));
            box.getChildren().addAll(row, needs);
        }
        return box;
    }

    /** "Needs: 2× Brass Scrap, Tinker's Tools, 1× Thermal charge → 6× Steam Bolt". */
    private String blueprintNeeds(com.dnd.model.item.crafting.Blueprint bp) {
        List<String> parts = new ArrayList<>();
        for (var m : bp.getMaterials()) {
            Item it = m == null ? null : repos.items().getById(m.getItemId());
            if (m != null) parts.add(m.getQuantity() + "× " + (it == null ? m.getItemId() : it.getName()));
        }
        for (String t : bp.getToolIds()) {
            Item it = repos.items().getById(t);
            parts.add(it == null ? t : it.getName());
        }
        for (var c : bp.getCharges()) if (c != null) parts.add(c.toString() + " charge" + (c.getCount() == 1 ? "" : "s"));
        Item product = bp.getProductId() == null ? null : repos.items().getById(bp.getProductId());
        return "Needs: " + (parts.isEmpty() ? "nothing" : String.join(", ", parts))
            + " → " + bp.getProductQuantity() + "× " + (product == null ? bp.getProductId() : product.getName())
            + (bp.getMinLevel() > 1 ? "  (level " + bp.getMinLevel() + "+)" : "");
    }

    /**
     * AC, resistances, and every class/race feature with its uses left. Features that only
     * touch their user can be used right here; targeted ones are used on the battle map.
     */
    private Node featuresBox(PlayerCharacter pc, Runnable persist) {
        var cls = pc.getClassId() == null ? null : repos.classes().getById(pc.getClassId());
        var race = pc.getRaceId() == null ? null : repos.races().getById(pc.getRaceId());
        FeatureRules.Kit kit = FeatureRules.kit(pc, cls, race);
        int effectAc = 0;
        if (pc.getActiveEffects() != null) for (ActiveEffect e : pc.getActiveEffects()) effectAc += e.getAcBonus();
        FeatureRules.ArmorClass ac = FeatureRules.armorClass(pc, kit, repos.items()::getById, effectAc);

        VBox box = new VBox(4);
        Label acLabel = owner.body("🛡 AC " + ac.total() + "  (" + ac.breakdown() + ")");
        acLabel.setWrapText(true);
        box.getChildren().add(acLabel);
        if (!kit.getResistances().isEmpty()) {
            box.getChildren().add(owner.body("Resists: " + String.join(", ", kit.getResistances())));
        }
        for (FeatureRules.Action action : kit.getActions()) {
            int left = action.usesLeft(pc.getFeatureUses());
            Label label = new Label(action.getName() + "  ·  " + action.usesLabel(pc.getFeatureUses()));
            label.getStyleClass().add(left > 0 ? "body-label" : "muted-label");
            label.setWrapText(true);
            label.setMaxWidth(Double.MAX_VALUE);
            label.setTooltip(new Tooltip(action.getDescription()));
            HBox.setHgrow(label, Priority.ALWAYS);
            HBox row = new HBox(6, label);
            row.setAlignment(Pos.CENTER_LEFT);
            boolean automatic = action.getId().equals(kit.getEnduranceFeature());
            if (action.needsTarget() || automatic) {
                row.getChildren().add(hint(automatic ? "automatic" : "on battle map"));
            } else {
                Button use = smallBtn("Use", () -> {
                    CombatState state = new CombatState();
                    TokenSupport.pullVitals(pc, state);
                    String message = FeatureRules.useOnSelf(action, state, pc.getName(), new java.util.Random());
                    TokenSupport.pushVitals(state, pc);
                    persist.run();
                    status.accept(message);
                    showPlayer(pc);
                });
                use.setDisable(left <= 0);
                row.getChildren().add(use);
            }
            box.getChildren().add(row);
        }
        for (FeatureRules.Passive passive : kit.getPassives()) {
            Label p = new Label("• " + passive.name());
            p.getStyleClass().add("muted-label");
            p.setWrapText(true);
            if (passive.description() != null && !passive.description().isBlank()) {
                p.setTooltip(new Tooltip(passive.description()));
            }
            box.getChildren().add(p);
        }
        return box;
    }

    void showCreature(TrackedCreature c) {
        TextField name = new TextField(c.getName());
        name.getStyleClass().add("dnd-text-field");
        name.textProperty().addListener((o, old, v) -> { c.setName(v); saveCreatures(); });

        GridPane vitals = vitalsGrid(
            c.getCurrentHitPoints(), v -> { c.setCurrentHitPoints(v); saveCreatures(); },
            c.getMaxHitPoints(), v -> { c.setMaxHitPoints(v); saveCreatures(); },
            c.getCurrentMana(), v -> { c.setCurrentMana(v); saveCreatures(); },
            c.getMaxMana(), v -> { c.setMaxMana(v); saveCreatures(); });
        Spinner<Integer> ac = spinner(c.getArmorClass(), v -> { c.setArmorClass(v); saveCreatures(); });
        vitals.addRow(2, owner.body("AC:"), ac);

        VBox effectsBox = new VBox(6);
        Runnable refreshEffects = new Runnable() {
            @Override
            public void run() {
                fillEffects(effectsBox, c.getActiveEffects(), effect -> {
                    c.getActiveEffects().remove(effect);
                    saveCreatures();
                    this.run();
                }, ManagePlayersPanel.this::saveCreatures);
            }
        };
        VBox effectPicker = new VBox();
        Button addEffect = smallBtn("+ Add Effect", () -> toggle(effectPicker, () -> effectPicker(effectPicker, effect -> {
            c.getActiveEffects().add(effect);
            saveCreatures();
            refreshEffects.run();
        })));
        refreshEffects.run();

        TextArea notes = new TextArea(c.getNotes() == null ? "" : c.getNotes());
        notes.setWrapText(true);
        notes.setPrefRowCount(4);
        notes.textProperty().addListener((o, old, v) -> { c.setNotes(v); saveCreatures(); });

        Button remove = owner.dangerBtn("Remove from this session", () -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove " + nameOf(c) + " from this session's list?", ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText(null);
            owner.styleDialog(confirm);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            creatures.remove(c);
            saveCreatures();
            showList();
        });
        remove.setMaxWidth(Double.MAX_VALUE);

        detailFrame(nameOf(c),
            subLabel("Name"), name,
            subLabel("Vitals"), vitals,
            headerRow("Active Effects", addEffect), effectPicker, effectsBox,
            subLabel("Notes"), notes,
            remove);
    }

    private GridPane vitalsGrid(int curHp, IntConsumer setCurHp, int maxHp, IntConsumer setMaxHp,
                                int curMana, IntConsumer setCurMana, int maxMana, IntConsumer setMaxMana) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, owner.body("HP:"), spinner(curHp, setCurHp), owner.body("of"), spinner(maxHp, setMaxHp));
        grid.addRow(1, owner.body("Mana:"), spinner(curMana, setCurMana), owner.body("of"), spinner(maxMana, setMaxMana));
        return grid;
    }

    private Spinner<Integer> spinner(int value, IntConsumer onChange) {
        Spinner<Integer> s = new Spinner<>(0, 9999, Math.max(0, value));
        s.setEditable(true);
        s.setPrefWidth(90);
        s.valueProperty().addListener((o, old, n) -> { if (n != null) onChange.accept(n); });
        return s;
    }

    private void refreshItems(VBox box, PlayerCharacter pc, Runnable persist) {
        box.getChildren().clear();
        List<PlayerCharacter.PlayerItem> items = pc.getItems();
        if (items == null || items.isEmpty()) {
            box.getChildren().add(hint("No items."));
            return;
        }
        for (PlayerCharacter.PlayerItem item : new ArrayList<>(items)) {
            Item catalogItem = repos.items().getById(item.getItemId());
            Label label = owner.body((catalogItem != null ? catalogItem.getName() : item.getItemId())
                + (item.getQuantity() > 1 ? "  ×" + item.getQuantity() : ""));
            label.setWrapText(true);
            HBox.setHgrow(label, Priority.ALWAYS);
            label.setMaxWidth(Double.MAX_VALUE);
            Button less = quick("−", "Use up / lose one", () -> {
                if (item.getQuantity() > 1) item.setQuantity(item.getQuantity() - 1);
                else items.remove(item);
                persist.run();
                refreshItems(box, pc, persist);
            });
            Button more = quick("+", "Found or crafted one more", () -> {
                item.setQuantity(item.getQuantity() + 1);
                persist.run();
                refreshItems(box, pc, persist);
            });
            HBox row = new HBox(6, label, less, more);
            row.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(row);
        }
    }

    private void fillEffects(VBox box, List<ActiveEffect> effects, Consumer<ActiveEffect> clear, Runnable persist) {
        box.getChildren().clear();
        if (effects == null || effects.isEmpty()) {
            box.getChildren().add(hint("No active effects."));
            return;
        }
        for (ActiveEffect effect : new ArrayList<>(effects)) {
            Label label = owner.body(effect.label());
            label.setWrapText(true);
            label.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(label, Priority.ALWAYS);
            Button fewer = quick("−", "One round less", () -> {
                if (effect.getRemainingRounds() <= 1) {
                    clear.accept(effect);
                    return;
                }
                effect.setRemainingRounds(effect.getRemainingRounds() - 1);
                persist.run();
                label.setText(effect.label());
            });
            Button more = quick("+", "One round more", () -> {
                effect.setRemainingRounds(effect.getRemainingRounds() + 1);
                persist.run();
                label.setText(effect.label());
            });
            Button x = new Button("✖");
            x.getStyleClass().add("danger-button");
            x.setStyle("-fx-min-width: 0; -fx-padding: 2 7 2 7;");
            x.setTooltip(new Tooltip("Clear this effect"));
            x.setOnAction(e -> clear.accept(effect));
            HBox row = new HBox(5, label, fewer, more, x);
            row.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(row);
        }
    }

    // ------------------------------------------------- inline pickers & forms

    /** Opens {@code content} inside {@code host}, or closes it if it is already open. */
    private void toggle(VBox host, Supplier<Node> content) {
        if (!host.getChildren().isEmpty()) {
            host.getChildren().clear();
            return;
        }
        host.getChildren().setAll(content.get());
    }

    /**
     * A searchable list shown right in the panel: type to filter, click an entry (or press
     * Enter for the first match) to use it. It stays open so several can be added in a row.
     */
    private <T> Node picker(VBox host, String prompt, List<T> catalog, Function<T, String> label,
                            Consumer<T> onPick, String createText, Runnable onCreate) {
        TextField search = new TextField();
        search.setPromptText(prompt);
        search.getStyleClass().add("dnd-text-field");

        ListView<T> list = new ListView<>();
        list.getStyleClass().add("dnd-list-view");
        list.setPrefHeight(170);
        list.setPlaceholder(hint(catalog.isEmpty() ? "Nothing in the catalog yet - create one below." : "No match."));
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : label.apply(item));
            }
        });
        Runnable filter = () -> {
            String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
            list.getItems().setAll(catalog.stream()
                .filter(t -> q.isEmpty() || label.apply(t).toLowerCase(Locale.ROOT).contains(q))
                .toList());
        };
        search.textProperty().addListener((o, old, v) -> filter.run());
        filter.run();

        Consumer<T> pick = t -> {
            if (t == null) return;
            list.getSelectionModel().clearSelection();
            onPick.accept(t);
        };
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) pick.accept(list.getSelectionModel().getSelectedItem());
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) pick.accept(list.getSelectionModel().getSelectedItem());
        });
        search.setOnAction(e -> {
            if (!list.getItems().isEmpty()) pick.accept(list.getItems().get(0));
        });

        Button create = smallBtn(createText, onCreate);
        Button done = smallBtn("Done", () -> host.getChildren().clear());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(6, create, spacer, done);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, search, list, actions);
        box.getStyleClass().add("picker-box");
        javafx.application.Platform.runLater(search::requestFocus);
        return box;
    }

    private Node itemPicker(VBox host, Consumer<Item> onPick) {
        return picker(host, "Search items... (Enter adds the first match)", repos.items().list(),
            i -> i.getName() + (i.getType() != null && !i.getType().isBlank() ? "   · " + i.getType() : ""),
            onPick, "✚ New item...", () -> host.getChildren().setAll(newItemForm(host, onPick)));
    }

    private Node effectPicker(VBox host, Consumer<ActiveEffect> onPick) {
        return picker(host, "Search effects... (Enter adds the first match)", repos.effects().list(),
            ManagePlayersPanel::describe,
            e -> onPick.accept(toActive(e)),
            "✚ New effect...", () -> host.getChildren().setAll(newEffectForm(host, onPick)));
    }

    private static String describe(Effect e) {
        StringBuilder sb = new StringBuilder(e.getName() == null ? "?" : e.getName());
        sb.append("   · ").append(Math.max(1, e.getDurationRounds())).append(" rnd");
        if (e.getDamageAmount() > 0) sb.append(", ").append(e.getDamageAmount()).append(" dmg/rnd");
        if (e.getHealingAmount() > 0) sb.append(", ").append(e.getHealingAmount()).append(" heal/rnd");
        return sb.toString();
    }

    static ActiveEffect toActive(Effect e) {
        return new ActiveEffect(e.getId(), e.getName(), Math.max(1, e.getDurationRounds()),
            e.getDamageAmount(), e.getHealingAmount(), "DM");
    }

    /** Creates a catalog item right here and gives it to the character in one step. */
    private Node newItemForm(VBox host, Consumer<Item> onCreated) {
        TextField name = formField("Name");
        ToggleGroup kinds = new ToggleGroup();
        javafx.scene.layout.FlowPane kindRow = new javafx.scene.layout.FlowPane(4, 4);
        for (com.dnd.ui.components.ItemKinds.Kind k : com.dnd.ui.components.ItemKinds.ALL) {
            ToggleButton t = new ToggleButton(k.label());
            t.setUserData(k);
            t.setToggleGroup(kinds);
            t.getStyleClass().add("tool-toggle-button");
            kindRow.getChildren().add(t);
        }
        Label kindNote = hint("");
        // The kind's own fields (a blueprint's materials and charges, a fuel's value...) come
        // from the same reflective form the DM panel uses, so both places can build anything.
        VBox fieldsHost = new VBox();
        java.util.Map<String, List<com.dnd.ui.components.EntityForm.Ref>> catalogs = EntityDetailScene.catalogsFor(repos);
        com.dnd.ui.components.EntityForm[] fields = new com.dnd.ui.components.EntityForm[1];
        Runnable rebuild = () -> {
            var kind = (com.dnd.ui.components.ItemKinds.Kind) kinds.getSelectedToggle().getUserData();
            kindNote.setText(kind.note());
            fields[0] = com.dnd.ui.components.EntityForm.of(kind.javaClass(), null,
                java.util.Set.of("id", "name", "type", "imagePath"), catalogs);
            fieldsHost.getChildren().setAll(fields[0].getNode());
        };
        kinds.selectToggle(kinds.getToggles().get(0));
        kinds.selectedToggleProperty().addListener((o, old, v) -> {
            if (v == null) kinds.selectToggle(old);
            else rebuild.run();
        });
        rebuild.run();

        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        Button create = smallBtn("Create & Add", () -> {
            if (name.getText() == null || name.getText().isBlank()) {
                error.setText("Give the item a name.");
                return;
            }
            var kind = (com.dnd.ui.components.ItemKinds.Kind) kinds.getSelectedToggle().getUserData();
            Item item = kind.create();
            try {
                fields[0].writeTo(item);
            } catch (Exception ex) {
                error.setText(ex.getMessage());
                return;
            }
            item.setId(newId("item", name.getText(), repos.items().list().stream().map(Item::getId).toList()));
            item.setName(name.getText().trim());
            item.setType(kind.type());
            if (item.getDurability() == null || item.getDurability().getMax() == 0) {
                item.setDurability(new Item.ItemDurability(100, 100));
            }
            repos.items().save(item);
            onCreated.accept(item);
            host.getChildren().setAll(itemPicker(host, onCreated));
        });
        Button cancel = smallBtn("Cancel", () -> host.getChildren().setAll(itemPicker(host, onCreated)));
        name.setOnAction(e -> create.fire());
        return form("New item", error, create, cancel, name, kindRow, kindNote, fieldsHost);
    }

    /** Creates a catalog effect right here and applies it in one step. */
    private Node newEffectForm(VBox host, Consumer<ActiveEffect> onCreated) {
        TextField name = formField("Name, e.g. Burning");
        Spinner<Integer> rounds = new Spinner<>(1, 999, 3);
        Spinner<Integer> damage = new Spinner<>(0, 999, 0);
        Spinner<Integer> healing = new Spinner<>(0, 999, 0);
        for (Spinner<Integer> s : List.of(rounds, damage, healing)) {
            s.setEditable(true);
            s.setPrefWidth(80);
        }
        GridPane numbers = new GridPane();
        numbers.setHgap(8);
        numbers.setVgap(6);
        numbers.addRow(0, owner.body("Rounds:"), rounds);
        numbers.addRow(1, owner.body("Damage / round:"), damage);
        numbers.addRow(2, owner.body("Healing / round:"), healing);
        TextArea description = new TextArea();
        description.setPromptText("Description (optional)");
        description.setWrapText(true);
        description.setPrefRowCount(2);

        Label error = new Label();
        error.getStyleClass().add("error-label");
        Button create = smallBtn("Create & Apply", () -> {
            if (name.getText() == null || name.getText().isBlank()) {
                error.setText("Give the effect a name.");
                return;
            }
            int dmg = damage.getValue();
            int heal = healing.getValue();
            Effect effect = new Effect(newId("effect", name.getText(), repos.effects().list().stream().map(Effect::getId).toList()),
                name.getText().trim(), description.getText(), dmg > 0, heal > 0, dmg, heal);
            effect.setDurationRounds(rounds.getValue());
            repos.effects().save(effect);
            onCreated.accept(toActive(effect));
            host.getChildren().setAll(effectPicker(host, onCreated));
        });
        Button cancel = smallBtn("Cancel", () -> host.getChildren().setAll(effectPicker(host, onCreated)));
        name.setOnAction(e -> create.fire());
        return form("New effect", error, create, cancel, name, numbers, description);
    }

    /** Builds the right {@link Item} subtype for {@code type} the same way the catalog JSON is read. */
    static Item newItem(String id, String name, String type, String description) {
        java.util.Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("id", id);
        raw.put("name", name);
        raw.put("type", type);
        raw.put("description", description == null ? "" : description);
        Item item = new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .convertValue(raw, Item.class);
        item.setDurability(new Item.ItemDurability(100, 100));
        return item;
    }

    private Node form(String title, Label error, Button create, Button cancel, Node... fields) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(6, create, spacer, cancel);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6);
        box.getChildren().add(subLabel(title));
        box.getChildren().addAll(fields);
        box.getChildren().addAll(error, actions);
        box.getStyleClass().add("picker-box");
        if (fields.length > 0 && fields[0] instanceof TextField first) {
            javafx.application.Platform.runLater(first::requestFocus);
        }
        return box;
    }

    private TextField formField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.getStyleClass().add("dnd-text-field");
        return f;
    }

    /** {@code <prefix>-<slug>}, made unique against {@code taken}; non-Latin names get a short random id. */
    static String newId(String prefix, String name, java.util.Collection<String> taken) {
        String slug = CharacterCreationWizard.slug(name);
        String base = prefix + "-" + (slug.isEmpty() ? java.util.UUID.randomUUID().toString().substring(0, 8) : slug);
        String candidate = base;
        int n = 2;
        while (taken.contains(candidate)) candidate = base + "-" + n++;
        return candidate;
    }

    // ---------------------------------------------------------------- helpers

    private ScrollPane scroll(Node content) {
        VBox padded = new VBox(content);
        padded.setPadding(new Insets(4, 10, 12, 10));
        ScrollPane scroll = new ScrollPane(padded);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: #141428; -fx-background-color: #141428;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private Node headerRow(String text, Button action) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, subLabel(text), spacer, action);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button smallBtn(String text, Runnable action) {
        Button b = owner.btn(text, action);
        b.setStyle("-fx-min-width: 0; -fx-padding: 3 10 3 10; -fx-font-size: 12px;");
        return b;
    }

    private Label subLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sub-label");
        return l;
    }

    private Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted-label");
        l.setWrapText(true);
        return l;
    }

    private static String nameOf(TrackedCreature c) {
        return c.getName() == null || c.getName().isBlank() ? "(unnamed)" : c.getName();
    }
}
