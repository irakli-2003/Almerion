package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.ClassFeature;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.Progression;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.item.Item;
import com.dnd.model.magic.Spell;
import com.dnd.model.magic.SpellSlots;
import com.dnd.model.magic.SpellcastingType;
import com.dnd.ui.ImageStore;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import com.dnd.ui.components.ImageFolders;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Step-by-step "Create Character" wizard opened from the DM menu: race, class, subclass,
 * level &amp; ability scores, spells, starting items, identity and a final summary. The
 * validation and character assembly live in static, UI-free methods so they can be unit tested.
 */
public class CharacterCreationWizard extends BaseScene {

    public enum Step {
        RACE("Race"), CLASS("Class"), SUBCLASS("Subclass"), ABILITIES("Level & Ability Scores"),
        SPELLS("Spells"), ITEMS("Starting Items"), IDENTITY("Identity"), SUMMARY("Summary");

        private final String label;

        Step(String label) { this.label = label; }

        public String label() { return label; }
    }

    public static final String[] ABILITY_NAMES = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};
    public static final int[] STANDARD_ARRAY = {15, 14, 13, 12, 10, 8};
    public static final int MIN_SCORE = 3;
    public static final int MAX_SCORE = 20;
    public static final int MAX_CREATION_LEVEL = 20;

    /** Everything chosen so far; plain data so the wizard logic can be tested without JavaFX. */
    public static class State {
        public CharacterRace race;
        public CharacterClass cls;
        public String subclass;
        public int level = 1;
        public int[] scores = {10, 10, 10, 10, 10, 10};
        public final Set<String> spellIds = new LinkedHashSet<>();
        public final Set<String> itemIds = new LinkedHashSet<>();
        public String name;
        public String playerName;
        public String ownerUsername;
        public String backstory;
        public String description;
        public String imagePath;
    }

    // ── Pure logic ──────────────────────────────────────────────────────────

    public static boolean hasSubclasses(CharacterClass cls) {
        return cls != null && cls.getSubclasses() != null && !cls.getSubclasses().isEmpty();
    }

    /** What each ability score is for, in plain words (same order as {@link #ABILITY_NAMES}). */
    public static final String[] ABILITY_NOTES = {
        "Strength - lifting, pushing, melee hits with heavy weapons, carrying capacity.",
        "Dexterity - agility and aim: ranged and finesse attacks, dodging (armor class), stealth.",
        "Constitution - toughness: extra hit points every level, resisting poison and exhaustion.",
        "Intelligence - learning and reasoning: wizards' and artificers' magic, investigation, lore.",
        "Wisdom - perception and willpower: clerics', druids' and rangers' magic, spotting danger.",
        "Charisma - force of personality: persuasion, deception, bards', sorcerers' and warlocks' magic."
    };

    /**
     * Picks a class. Switching to a different class resets the subclass, replaces the spell
     * choices with the new class's default spells and swaps the old class's default items for
     * the new one's (items the player picked by hand stay). Everything remains editable.
     */
    public static void chooseClass(State s, CharacterClass cls) {
        if (cls == null) return;
        boolean changed = s.cls == null || !cls.getId().equals(s.cls.getId());
        if (changed) {
            if (s.cls != null) s.itemIds.removeAll(s.cls.getDefaultItems());
            s.subclass = null;
            s.spellIds.clear();
            if (isCaster(cls)) s.spellIds.addAll(cls.getDefaultSpells());
            s.itemIds.addAll(cls.getDefaultItems());
        }
        s.cls = cls;
    }

    public static boolean isCaster(CharacterClass cls) {
        return cls != null && cls.getSpellcasting() != null && cls.getSpellcasting() != SpellcastingType.NONE;
    }

    /** Whether the wizard may move past {@code step} with the current choices. */
    public static boolean canAdvance(Step step, State s) {
        if (s == null) return false;
        return switch (step) {
            case RACE -> s.race != null;
            case CLASS -> s.cls != null;
            case SUBCLASS -> s.cls != null && (!hasSubclasses(s.cls) || !isBlank(s.subclass));
            case ABILITIES -> s.level >= 1 && s.level <= MAX_CREATION_LEVEL && s.scores != null && s.scores.length == 6;
            case SPELLS, ITEMS -> true;
            case IDENTITY -> !isBlank(s.name);
            case SUMMARY -> {
                for (Step earlier : Step.values()) {
                    if (earlier != Step.SUMMARY && !canAdvance(earlier, s)) yield false;
                }
                yield true;
            }
        };
    }

    public static int hitDieSides(CharacterClass cls) {
        return cls != null && cls.getHitDie() != null && cls.getHitDie().getSides() > 0 ? cls.getHitDie().getSides() : 8;
    }

    /** Max HP: full hit die + CON at level 1, then average die + CON per extra level (min 1 each). */
    public static int maxHitPoints(int hitDieSides, int conScore, int level) {
        int con = Progression.modifier(conScore);
        int hp = Math.max(1, hitDieSides + con);
        int perLevel = Math.max(1, Progression.averageHitDie(hitDieSides) + con);
        return hp + Math.max(0, level - 1) * perLevel;
    }

    public static String slug(String text) {
        if (text == null) return "";
        String s = text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    /** {@code player-<slug of name>}, suffixed with -2, -3, ... until it clashes with none of {@code existingIds}. */
    public static String uniqueId(String name, Collection<String> existingIds) {
        String s = slug(name);
        String base = "player-" + (s.isEmpty() ? "character" : s);
        Set<String> taken = existingIds == null ? Set.of() : new HashSet<>(existingIds);
        String candidate = base;
        int n = 2;
        while (taken.contains(candidate)) candidate = base + "-" + n++;
        return candidate;
    }

    /** Assembles the new character from the wizard state; {@code existing} is used for id uniqueness. */
    public static PlayerCharacter buildCharacter(State s, List<PlayerCharacter> existing) {
        if (!canAdvance(Step.SUMMARY, s)) throw new IllegalStateException("Character choices are incomplete");
        List<String> ids = new ArrayList<>();
        if (existing != null) for (PlayerCharacter pc : existing) if (pc != null) ids.add(pc.getId());

        PlayerCharacter pc = new PlayerCharacter();
        pc.setId(uniqueId(s.name, ids));
        pc.setName(s.name.trim());
        pc.setClassId(s.cls.getId());
        pc.setRaceId(s.race.getId());
        pc.setSubclass(hasSubclasses(s.cls) ? s.subclass : null);
        pc.setLevel(s.level);
        pc.setXp(0);
        int[] sc = finalScores(s);
        pc.setStats(new CoreStats(sc[0], sc[1], sc[2], sc[3], sc[4], sc[5]));

        List<PlayerCharacter.PlayerItem> items = new ArrayList<>();
        for (String id : s.itemIds) items.add(new PlayerCharacter.PlayerItem(id, new PlayerCharacter.ItemCondition(100), false));
        pc.setItems(items);
        List<PlayerCharacter.PlayerSpell> spells = new ArrayList<>();
        if (isCaster(s.cls)) for (String id : s.spellIds) spells.add(new PlayerCharacter.PlayerSpell(id, 1, true));
        pc.setSpells(spells);

        pc.setPlayerName(blankToNull(s.playerName));
        pc.setOwnerUsername(blankToNull(s.ownerUsername));
        pc.setBackstory(blankToNull(s.backstory));
        pc.setDescription(blankToNull(s.description));
        pc.setImagePath(blankToNull(s.imagePath));

        int hp = maxHitPoints(hitDieSides(s.cls), sc[2], s.level);
        pc.setMaxHitPoints(hp);
        pc.setCurrentHitPoints(hp);
        Progression.ensureSlots(pc, s.cls);
        return pc;
    }

    private static final String[] BONUS_KEYS = {"strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"};

    /** The chosen scores with the race's ability bonuses added (STR, DEX, CON, INT, WIS, CHA). */
    public static int[] finalScores(State s) {
        int[] out = s.scores.clone();
        java.util.Map<String, Integer> bonuses = s.race == null ? null : s.race.getAbilityBonuses();
        if (bonuses == null) return out;
        for (int i = 0; i < 6; i++) {
            Integer bonus = bonuses.get(BONUS_KEYS[i]);
            if (bonus == null) bonus = bonuses.get(ABILITY_NAMES[i].toLowerCase(java.util.Locale.ROOT));
            if (bonus != null) out[i] = Math.max(CoreStats.MIN_SCORE, Math.min(CoreStats.MAX_SCORE, out[i] + bonus));
        }
        return out;
    }

    /** Case-insensitive alphabetical copy of {@code list} by {@code nameFn}. */
    public static <T> List<T> sortedByName(Collection<T> list, Function<T, String> nameFn) {
        List<T> out = new ArrayList<>(list == null ? List.of() : list);
        out.sort(Comparator.comparing(t -> {
            String n = nameFn.apply(t);
            return n == null ? "" : n;
        }, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    // ── UI ──────────────────────────────────────────────────────────────────

    private final CampaignRepositories repos;
    private final State state = new State();
    private Step step = Step.RACE;
    private File portraitFile;

    private Stage stage;
    private final Label stepLabel = new Label();
    private final VBox page = new VBox(10);
    private Button backBtn;
    private Button nextBtn;
    private Button createBtn;

    private List<CharacterRace> races;
    private List<CharacterClass> classes;
    private List<Spell> spellList;
    private List<Item> itemList;

    public CharacterCreationWizard(UiSession uiSession, CampaignRepositories repos) {
        super(uiSession);
        this.repos = repos;
    }

    State state() { return state; }

    /** Opens the wizard as a modal window. */
    public void show() {
        show(null);
    }

    /** Opens the wizard and runs {@code onClosed} once it closes (created or cancelled). */
    public void show(Runnable onClosed) {
        stage = new Stage();
        com.dnd.ui.WindowOrder.adopt(stage);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Create Character");
        stage.setScene(build());
        if (onClosed != null) stage.setOnHidden(e -> onClosed.run());
        stage.show();
    }

    @Override
    public Scene build() {
        races = sortedByName(repos.races().list(), CharacterRace::getName);
        classes = sortedByName(repos.classes().list(), CharacterClass::getName);
        spellList = sortedByName(repos.spells().list(), Spell::getName);
        itemList = sortedByName(repos.items().list(), Item::getName);

        stepLabel.getStyleClass().add("section-label");

        backBtn = btn("◀ Back", this::goBack);
        nextBtn = btn("Next ▶", this::goNext);
        createBtn = btn("Create", this::create);
        Button cancelBtn = btn("Cancel", () -> { if (stage != null) stage.close(); });
        HBox nav = new HBox(10, cancelBtn, hSpacer(), backBtn, nextBtn, createBtn);
        nav.setAlignment(Pos.CENTER_RIGHT);

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #1a1a2e; -fx-background-color: #1a1a2e;");
        page.setPadding(new Insets(6));

        VBox root = new VBox(14, stepLabel, scroll, nav);
        root.getStyleClass().add("root");
        root.setPadding(new Insets(20));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        showStep();
        return themedScene(root, 720, 600);
    }

    private void showStep() {
        stepLabel.setText("Step " + (step.ordinal() + 1) + " of " + Step.values().length + " – " + step.label());
        page.getChildren().clear();
        switch (step) {
            case RACE -> buildRacePage();
            case CLASS -> buildClassPage();
            case SUBCLASS -> buildSubclassPage();
            case ABILITIES -> buildAbilitiesPage();
            case SPELLS -> buildSpellsPage();
            case ITEMS -> buildItemsPage();
            case IDENTITY -> buildIdentityPage();
            case SUMMARY -> buildSummaryPage();
        }
        refreshNav();
    }

    private void refreshNav() {
        boolean last = step == Step.SUMMARY;
        backBtn.setDisable(step == Step.RACE);
        nextBtn.setVisible(!last);
        nextBtn.setManaged(!last);
        nextBtn.setDisable(!canAdvance(step, state));
        createBtn.setVisible(last);
        createBtn.setManaged(last);
        createBtn.setDisable(!canAdvance(Step.SUMMARY, state));
    }

    private void goNext() {
        if (!canAdvance(step, state) || step == Step.SUMMARY) return;
        step = Step.values()[step.ordinal() + 1];
        showStep();
    }

    private void goBack() {
        if (step == Step.RACE) return;
        step = Step.values()[step.ordinal() - 1];
        showStep();
    }

    private void buildRacePage() {
        Label details = body("");
        details.setWrapText(true);
        ToggleGroup group = new ToggleGroup();
        VBox options = new VBox(6);
        for (CharacterRace race : races) {
            RadioButton rb = new RadioButton(race.getName() != null ? race.getName() : race.getId());
            rb.setToggleGroup(group);
            rb.setSelected(state.race != null && race.getId() != null && race.getId().equals(state.race.getId()));
            rb.setOnAction(e -> {
                state.race = race;
                details.setText(describeRace(race));
                refreshNav();
            });
            options.getChildren().add(rb);
        }
        if (races.isEmpty()) options.getChildren().add(body("No races defined in this campaign."));
        if (state.race != null) details.setText(describeRace(state.race));
        page.getChildren().addAll(guide("Your race is the people you were born into. It adds bonuses to some "
            + "ability scores and sets how fast you move. Click a race to read about it."),
            options, sectionLabel("Details"), details);
    }

    private static String describeRace(CharacterRace race) {
        StringBuilder sb = new StringBuilder();
        if (race.getDescription() != null) sb.append(race.getDescription()).append("\n");
        sb.append("Speed: ").append(race.getSpeed()).append(" ft");
        if (race.getAbilityBonuses() != null && !race.getAbilityBonuses().isEmpty()) {
            sb.append("\nAbility bonuses (added to your scores): ");
            List<String> keys = sortedByName(race.getAbilityBonuses().keySet(), k -> k);
            List<String> parts = new ArrayList<>();
            for (String k : keys) parts.add(k + " +" + race.getAbilityBonuses().get(k));
            sb.append(String.join(", ", parts));
        }
        return sb.toString();
    }

    private void buildClassPage() {
        Label details = body("");
        details.setWrapText(true);
        ToggleGroup group = new ToggleGroup();
        VBox options = new VBox(6);
        for (CharacterClass cls : classes) {
            RadioButton rb = new RadioButton(cls.getName() != null ? cls.getName() : cls.getId());
            rb.setToggleGroup(group);
            rb.setSelected(state.cls != null && cls.getId() != null && cls.getId().equals(state.cls.getId()));
            rb.setOnAction(e -> {
                chooseClass(state, cls);
                details.setText(describeClass(cls));
                refreshNav();
            });
            options.getChildren().add(rb);
        }
        if (classes.isEmpty()) options.getChildren().add(body("No classes defined in this campaign."));
        if (state.cls != null) details.setText(describeClass(state.cls));
        page.getChildren().addAll(guide("Your class is your training and role in the party: who fights in front, "
            + "who heals, who casts. It decides your hit points, your features and whether you use magic. "
            + "Picking one also pre-selects its usual spells and starting gear - you can change those later."),
            options, sectionLabel("Details"), details);
    }

    private static String describeClass(CharacterClass cls) {
        StringBuilder sb = new StringBuilder();
        if (cls.getDescription() != null) sb.append(cls.getDescription()).append("\n");
        sb.append("Hit die: d").append(hitDieSides(cls))
            .append("  (rolled for hit points each level - bigger means tougher)");
        sb.append("\nSpellcasting: ").append(cls.getSpellcasting());
        String casting = com.dnd.ui.components.FieldHelp.option(cls.getSpellcasting());
        if (casting != null) sb.append("\n   ").append(casting);
        if (cls.getPrimaryAbilities() != null && !cls.getPrimaryAbilities().isEmpty()) {
            sb.append("\nMain abilities: ").append(String.join(", ", cls.getPrimaryAbilities()))
                .append("  (put your best scores here)");
        }
        if (!cls.getDefaultSpells().isEmpty() || !cls.getDefaultItems().isEmpty()) {
            sb.append("\nStarts with ").append(cls.getDefaultSpells().size()).append(" default spell(s) and ")
                .append(cls.getDefaultItems().size()).append(" item(s), pre-ticked on the next pages.");
        }
        return sb.toString();
    }

    private void buildSubclassPage() {
        if (!hasSubclasses(state.cls)) {
            page.getChildren().add(body("No subclass options for this class."));
            return;
        }
        Label features = body("");
        features.setWrapText(true);
        ToggleGroup group = new ToggleGroup();
        VBox options = new VBox(6);
        for (String sub : sortedByName(state.cls.getSubclasses(), s -> s)) {
            RadioButton rb = new RadioButton(sub);
            rb.setToggleGroup(group);
            rb.setSelected(sub.equals(state.subclass));
            rb.setOnAction(e -> {
                state.subclass = sub;
                features.setText(describeSubclass(state.cls, sub));
                refreshNav();
            });
            options.getChildren().add(rb);
        }
        if (state.subclass != null) features.setText(describeSubclass(state.cls, state.subclass));
        page.getChildren().addAll(guide("A subclass is a specialisation inside your class (a barbarian's path, "
            + "a wizard's school...). It adds its own features as you level up - listed below once you pick one."),
            options, sectionLabel("Subclass features"), features);
    }

    private static String describeSubclass(CharacterClass cls, String sub) {
        List<String> lines = new ArrayList<>();
        if (cls.getFeatures() != null) {
            for (ClassFeature f : cls.getFeatures()) {
                if (f != null && f.getSubclass() != null && f.getSubclass().equalsIgnoreCase(sub)) {
                    lines.add("Lvl " + f.getLevel() + " – " + f.getName());
                }
            }
        }
        return lines.isEmpty() ? "(no subclass-specific features listed)" : String.join("\n", lines);
    }

    private void buildAbilitiesPage() {
        Spinner<Integer> levelSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, MAX_CREATION_LEVEL, state.level));
        levelSpinner.setEditable(true);
        levelSpinner.valueProperty().addListener((o, a, b) -> {
            if (b != null) state.level = b;
            refreshNav();
        });
        HBox levelRow = new HBox(10, body("Level"), levelSpinner);
        levelRow.setAlignment(Pos.CENTER_LEFT);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        List<Spinner<Integer>> spinners = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int idx = i;
            Spinner<Integer> sp = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(MIN_SCORE, MAX_SCORE, state.scores[i]));
            sp.setEditable(true);
            sp.setPrefWidth(90);
            Label mod = body(modText(state.scores[i]));
            sp.valueProperty().addListener((o, a, b) -> {
                if (b != null) {
                    state.scores[idx] = b;
                    mod.setText(modText(b));
                }
            });
            spinners.add(sp);
            grid.addRow(i, body(ABILITY_NAMES[i]), sp, mod, hint(ABILITY_NOTES[i]));
        }
        Button standard = btn("Standard array", () -> {
            for (int i = 0; i < 6; i++) spinners.get(i).getValueFactory().setValue(STANDARD_ARRAY[i]);
        });
        page.getChildren().addAll(levelRow,
            hint("Level 1 is a fresh adventurer; higher levels start with more hit points, features and spells."),
            sectionLabel("Ability scores"),
            guide("Six numbers describe what the character is good at. 10 is average, 15 is excellent, 8 is weak. "
                + "The number in brackets is the modifier added to dice rolls: every 2 points above 10 is +1. "
                + "Race bonuses are added on top at the end."),
            grid, standard,
            hint("Standard array fills in 15, 14, 13, 12, 10, 8 - a balanced set. Rearrange them to suit the class."));
    }

    /** A short explanatory paragraph at the top of a page. */
    private Label guide(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(640);
        label.setStyle("-fx-text-fill: #b8a070; -fx-font-size: 12px; -fx-font-style: italic;"
            + " -fx-padding: 6 10; -fx-background-color: #ffffff0a; -fx-border-color: #c9a84c44;");
        return label;
    }

    /** A small grey note next to or under a control. */
    private Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(460);
        label.setStyle(com.dnd.ui.components.EntityForm.HINT_STYLE.replace("#6a5a3a", "#8a7a5a"));
        return label;
    }

    private static String modText(int score) {
        int m = Progression.modifier(score);
        return "(" + (m >= 0 ? "+" : "") + m + ")";
    }

    private void buildSpellsPage() {
        if (!isCaster(state.cls)) {
            page.getChildren().add(body("This class doesn't cast spells."));
            return;
        }
        boolean energy = state.cls.getSpellcasting() == SpellcastingType.ENERGY;
        String slots = energy
            ? com.dnd.model.magic.EnergyCharges.describe(SpellSlots.fresh(SpellSlots.maxSlots(state.cls.getSpellcasting(), state.level)))
            : SpellSlots.describe(SpellSlots.fresh(SpellSlots.maxSlots(state.cls.getSpellcasting(), state.level)));
        page.getChildren().add(guide("Cantrips are free and can be used every turn. Levelled spells cost "
            + (energy ? "an energy charge" : "a spell slot") + " of their level or higher each cast. "
            + "The class's usual spells are already ticked; tick or untick freely."
            + (slots.isBlank() ? "\nAt level " + state.level + " this class has no "
                + (energy ? "charges" : "slots") + " yet - only cantrips are usable."
                : "\nAt level " + state.level + ": " + slots + ".")));
        List<String[]> entries = new ArrayList<>();
        for (Spell sp : spellList) {
            String label = (sp.getName() != null ? sp.getName() : sp.getId())
                + (sp.getLevel() > 0 ? "  (lvl " + sp.getLevel() + ")" : "  (cantrip)");
            entries.add(new String[]{sp.getId(), label});
        }
        page.getChildren().add(multiSelect(entries, state.spellIds));
    }

    private void buildItemsPage() {
        page.getChildren().add(guide("Starting equipment. The class's usual kit is already ticked - add or remove "
            + "anything. Blueprints ticked here are also copied into an artificer's blueprint book."));
        List<String[]> entries = new ArrayList<>();
        for (Item it : itemList) entries.add(new String[]{it.getId(), it.getName() != null ? it.getName() : it.getId()});
        page.getChildren().add(multiSelect(entries, state.itemIds));
    }

    /** Filterable list of checkboxes; entries are {id, label} and already alphabetical. */
    private VBox multiSelect(List<String[]> entries, Set<String> selected) {
        TextField filter = textField("Filter...");
        VBox boxes = new VBox(4);
        List<CheckBox> all = new ArrayList<>();
        for (String[] e : entries) {
            CheckBox cb = checkBox(e[1]);
            cb.setSelected(selected.contains(e[0]));
            cb.selectedProperty().addListener((o, a, b) -> {
                if (b) selected.add(e[0]); else selected.remove(e[0]);
            });
            all.add(cb);
            boxes.getChildren().add(cb);
        }
        if (entries.isEmpty()) boxes.getChildren().add(body("Nothing available."));
        filter.textProperty().addListener((o, a, b) -> {
            String q = b == null ? "" : b.trim().toLowerCase(Locale.ROOT);
            for (CheckBox cb : all) {
                boolean show = q.isEmpty() || cb.getText().toLowerCase(Locale.ROOT).contains(q);
                cb.setVisible(show);
                cb.setManaged(show);
            }
        });
        ScrollPane sp = new ScrollPane(boxes);
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(320);
        sp.setStyle("-fx-background: #1a1a2e; -fx-background-color: #1a1a2e;");
        return new VBox(8, filter, sp);
    }

    private void buildIdentityPage() {
        TextField name = textField("Character name (required)");
        name.setText(state.name == null ? "" : state.name);
        name.textProperty().addListener((o, a, b) -> { state.name = b; refreshNav(); });
        TextField player = textField("Player name (optional)");
        player.setText(state.playerName == null ? "" : state.playerName);
        player.textProperty().addListener((o, a, b) -> state.playerName = b);
        TextField owner = textField("Owner username (optional)");
        owner.setText(state.ownerUsername == null ? "" : state.ownerUsername);
        owner.textProperty().addListener((o, a, b) -> state.ownerUsername = b);
        TextArea backstory = new TextArea(state.backstory == null ? "" : state.backstory);
        backstory.setPromptText("Backstory (optional)");
        backstory.setWrapText(true);
        backstory.setPrefRowCount(4);
        backstory.textProperty().addListener((o, a, b) -> state.backstory = b);
        TextArea description = new TextArea(state.description == null ? "" : state.description);
        description.setPromptText("Description (optional)");
        description.setWrapText(true);
        description.setPrefRowCount(3);
        description.textProperty().addListener((o, a, b) -> state.description = b);

        ImageView preview = new ImageView();
        preview.setFitWidth(64);
        preview.setFitHeight(64);
        preview.setPreserveRatio(true);
        Label portraitName = body(portraitFile == null ? "No portrait selected" : portraitFile.getName());
        if (portraitFile != null) preview.setImage(new javafx.scene.image.Image(portraitFile.toURI().toString()));
        Button choose = btn("Choose Portrait...", () -> {
            FileChooser fc = ImageFolders.imageChooser("Select Portrait");
            File chosen = fc.showOpenDialog(stage);
            if (chosen != null) {
                portraitFile = chosen;
                portraitName.setText(chosen.getName());
                preview.setImage(new javafx.scene.image.Image(chosen.toURI().toString()));
            }
        });
        HBox portraitRow = new HBox(10, preview, choose, portraitName);
        portraitRow.setAlignment(Pos.CENTER_LEFT);

        page.getChildren().addAll(guide("Who the character is. Only the name is required. The owner username "
            + "links the character to a player's login so they can see their own sheet."),
            body("Name"), name, body("Player name"), player, body("Owner username"), owner,
            body("Backstory"), backstory, body("Description"), description, body("Portrait"), portraitRow);
    }

    private void buildSummaryPage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(nz(state.name)).append("\n");
        sb.append("Race: ").append(state.race == null ? "-" : state.race.getName()).append("\n");
        sb.append("Class: ").append(state.cls == null ? "-" : state.cls.getName()).append("\n");
        if (hasSubclasses(state.cls)) sb.append("Subclass: ").append(nz(state.subclass)).append("\n");
        sb.append("Level: ").append(state.level).append("\n");
        List<String> stats = new ArrayList<>();
        int[] finalScores = finalScores(state);
        for (int i = 0; i < 6; i++) stats.add(ABILITY_NAMES[i] + " " + finalScores[i]);
        sb.append("Abilities (race bonuses included): ").append(String.join("  ", stats)).append("\n");
        sb.append("Max HP: ").append(maxHitPoints(hitDieSides(state.cls), finalScores[2], state.level)).append("\n");
        if (isCaster(state.cls)) {
            List<com.dnd.model.magic.SpellSlot> fresh = SpellSlots.fresh(
                SpellSlots.maxSlots(state.cls.getSpellcasting(), state.level));
            if (state.cls.getSpellcasting() == SpellcastingType.ENERGY) {
                sb.append("Energy charges: ").append(com.dnd.model.magic.EnergyCharges.describe(fresh)).append("\n");
            } else {
                sb.append("Spell slots: ").append(SpellSlots.describe(fresh)).append("\n");
            }
            sb.append("Spells: ").append(names(state.spellIds, spellList, Spell::getId, Spell::getName)).append("\n");
        }
        sb.append("Items: ").append(names(state.itemIds, itemList, Item::getId, Item::getName)).append("\n");
        sb.append("Player: ").append(nz(state.playerName)).append("\n");
        sb.append("Owner username: ").append(nz(state.ownerUsername)).append("\n");
        sb.append("Portrait: ").append(portraitFile == null ? "-" : portraitFile.getName()).append("\n");
        if (!isBlank(state.backstory)) sb.append("Backstory: ").append(state.backstory.trim()).append("\n");
        if (!isBlank(state.description)) sb.append("Description: ").append(state.description.trim()).append("\n");
        Label summary = body(sb.toString());
        summary.setWrapText(true);
        page.getChildren().add(summary);
    }

    private static <T> String names(Set<String> ids, List<T> all, Function<T, String> idFn, Function<T, String> nameFn) {
        if (ids.isEmpty()) return "none";
        List<String> out = new ArrayList<>();
        for (T t : all) if (ids.contains(idFn.apply(t))) out.add(nameFn.apply(t) != null ? nameFn.apply(t) : idFn.apply(t));
        return String.join(", ", sortedByName(out, s -> s));
    }

    private static String nz(String s) {
        return isBlank(s) ? "-" : s.trim();
    }

    private void create() {
        try {
            PlayerCharacter pc = buildCharacter(state, repos.players().list());
            for (Item it : itemList) {
                if (it instanceof com.dnd.model.item.crafting.Blueprint && state.itemIds.contains(it.getId())) {
                    com.dnd.model.crafting.Workshop.learn(pc, it.getId());
                }
            }
            if (portraitFile != null && uiSession.campaignRoot() != null) {
                pc.setImagePath(ImageStore.copyImage(uiSession.campaignRoot(), "players", pc.getId(), portraitFile));
            }
            repos.players().add(pc);
            if (stage != null) stage.close();
            uiSession.getRouter().goTo(SceneType.DM_MENU);
        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to create character: " + ex.getMessage());
            styleDialog(alert);
            alert.showAndWait();
        }
    }
}
