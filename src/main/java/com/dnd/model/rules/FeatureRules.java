package com.dnd.model.rules;

import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.ClassFeature;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.Progression;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.combat.Ability;
import com.dnd.model.combat.Damage;
import com.dnd.model.combat.DiceRoll;
import com.dnd.model.item.Item;
import com.dnd.model.item.Weapon;
import com.dnd.model.item.armors.Armor;
import com.dnd.model.world.map.ActiveEffect;
import com.dnd.model.world.map.CombatState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * Turns a character's class features and race into things that actually happen in a fight:
 * Rage that adds damage and halves physical hits, Second Wind that heals, Sneak Attack dice,
 * Unarmored Defense, a tiefling shrugging off fire, a half-orc refusing to drop.
 *
 * <p>The class and race catalogs only carry names and prose, so the mechanics live here in
 * code, keyed off the feature names the character has unlocked at their level (and their
 * subclass) and off their race id. Anything without a mechanical hook still comes back as a
 * passive note, so nothing a character has is invisible in battle.</p>
 */
public final class FeatureRules {

    private FeatureRules() {
    }

    public enum Recharge {
        SHORT_REST("short rest"), LONG_REST("long rest"), AT_WILL("at will");

        private final String label;

        Recharge(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Kind {
        /** Puts an effect on the user (Rage, Reckless Attack). */
        SELF_EFFECT,
        /** Rolls healing for the user (Second Wind, Stone's Endurance). */
        SELF_HEAL,
        /** Spends points from a pool to heal someone in reach (Lay on Hands, Healing Hands). */
        HEAL_POOL,
        /** Puts an effect on an ally in range (Bardic Inspiration). */
        ALLY_EFFECT,
        /** A blast resolved like a spell (Breath Weapon). */
        AREA_DAMAGE,
        /** Only the use is tracked; the DM narrates the result (Action Surge, Ki, Wild Shape). */
        COUNTER
    }

    /** Armor Class formula a character falls back on when not wearing body armor. */
    public enum Defense { ARMOR, BARBARIAN, MONK }

    /** Something a character can actively use, with limited uses that come back on rests. */
    public static final class Action {
        private final String id;
        private final String name;
        private final String description;
        private final Kind kind;
        private final int maxUses;
        private final Recharge recharge;
        private String healDice;
        private int healFlat;
        private ActiveEffect effect;
        private double rangeFeet;
        private double radiusFeet;
        private String damageDice;
        private String damageType;

        Action(String id, String name, String description, Kind kind, int maxUses, Recharge recharge) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.kind = kind;
            this.maxUses = Math.max(0, maxUses);
            this.recharge = recharge;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public Kind getKind() { return kind; }
        /** Uses (or pool points) per recharge; 0 means unlimited. */
        public int getMaxUses() { return maxUses; }
        public Recharge getRecharge() { return recharge; }
        public String getHealDice() { return healDice; }
        public int getHealFlat() { return healFlat; }
        public ActiveEffect getEffect() { return effect; }
        public double getRangeFeet() { return rangeFeet; }
        public double getRadiusFeet() { return radiusFeet; }
        public String getDamageDice() { return damageDice; }
        public String getDamageType() { return damageType; }

        public boolean isLimited() {
            return maxUses > 0;
        }

        public boolean isPool() {
            return kind == Kind.HEAL_POOL;
        }

        public boolean needsTarget() {
            return kind == Kind.HEAL_POOL || kind == Kind.ALLY_EFFECT || kind == Kind.AREA_DAMAGE;
        }

        public int usesLeft(Map<String, Integer> spent) {
            if (!isLimited()) return Integer.MAX_VALUE;
            Integer used = spent == null ? null : spent.get(id);
            return Math.max(0, maxUses - (used == null ? 0 : used));
        }

        /** "3/4 · long rest", "pool 20/25 · long rest", "at will". */
        public String usesLabel(Map<String, Integer> spent) {
            if (!isLimited()) return recharge.label();
            return (isPool() ? "pool " : "") + usesLeft(spent) + "/" + maxUses + " · " + recharge.label();
        }

        /** A breath-weapon style action as an ability the spell targeting can resolve. */
        public Ability toAbility() {
            Ability ability = new Ability(id, name, description, List.of(), rangeFeet, 0);
            ability.setRadius(radiusFeet);
            DiceExpr dice = DiceExpr.parse(damageDice);
            if (dice != null && dice.count > 0) {
                ability.setDamage(new Damage(List.of(new DiceRoll("d" + dice.sides, dice.count)), damageType));
            }
            return ability;
        }
    }

    /** A trait with no button: shown so the DM remembers it applies. */
    public record Passive(String name, String description) {
    }

    /** Everything a character's class and race give them in combat. */
    public static final class Kit {
        private final List<Action> actions = new ArrayList<>();
        private final Map<String, Passive> passives = new LinkedHashMap<>();
        private final List<String> resistances = new ArrayList<>();
        private Defense defense = Defense.ARMOR;
        private boolean fastMovement;
        private int unarmoredSpeedBonus;
        private int extraAttacks;
        private int sneakAttackDice;
        private String martialArtsDie;
        private String enduranceFeature;
        private int proficiency = 2;

        public List<Action> getActions() { return actions; }
        public List<Passive> getPassives() { return new ArrayList<>(passives.values()); }
        public List<String> getResistances() { return resistances; }
        public Defense getDefense() { return defense; }
        public int getExtraAttacks() { return extraAttacks; }
        public int getSneakAttackDice() { return sneakAttackDice; }
        public String getMartialArtsDie() { return martialArtsDie; }
        public String getEnduranceFeature() { return enduranceFeature; }
        public int getProficiency() { return proficiency; }

        public Action action(String id) {
            for (Action a : actions) if (a.id.equals(id)) return a;
            return null;
        }

        /** Walking speed added by Fast Movement / Unarmored Movement given what is worn. */
        public int speedBonus(boolean wearingArmor, boolean heavyArmor) {
            int bonus = 0;
            if (fastMovement && !heavyArmor) bonus += 10;
            if (!wearingArmor) bonus += unarmoredSpeedBonus;
            return bonus;
        }

        private void add(Action action) {
            actions.removeIf(a -> a.id.equals(action.id));
            actions.add(action);
        }

        private void note(String name, String description) {
            passives.put(baseName(name), new Passive(baseName(name), description == null ? "" : description));
        }

        private void resist(String... types) {
            for (String t : types) if (!resistances.contains(t)) resistances.add(t);
        }
    }

    public static final String RAGE = "rage";
    public static final String FURY_OF_THE_SMALL = "fury-of-the-small";
    public static final List<String> PHYSICAL = List.of("bludgeoning", "piercing", "slashing");

    // ── Building the kit ────────────────────────────────────────────────────

    public static Kit kit(PlayerCharacter pc, CharacterClass cls, CharacterRace race) {
        Kit kit = new Kit();
        if (pc == null) return kit;
        int level = Math.max(1, pc.getLevel());
        kit.proficiency = Progression.proficiencyBonus(level);
        CoreStats stats = pc.getStats();
        String classId = cls != null && cls.getId() != null ? cls.getId() : pc.getClassId();
        if (cls != null) {
            for (ClassFeature feature : cls.featuresUpTo(level, pc.getSubclass())) {
                applyClassFeature(kit, classId, feature, level, stats);
            }
        }
        applyRace(kit, raceKey(race != null ? race.getId() : pc.getRaceId()), level, stats);
        kit.actions.removeIf(a -> hasOwnAction(pc, a.id));
        return kit;
    }

    private static boolean hasOwnAction(PlayerCharacter pc, String id) {
        if (pc.getSpells() == null) return false;
        for (PlayerCharacter.PlayerSpell spell : pc.getSpells()) {
            if (spell != null && id.equalsIgnoreCase(spell.getSpellId())) return true;
        }
        return false;
    }

    private static void applyClassFeature(Kit kit, String classId, ClassFeature feature, int level, CoreStats stats) {
        String raw = feature.getName() == null ? "" : feature.getName();
        String name = baseName(raw).toLowerCase(Locale.ROOT);
        String desc = feature.getDescription();
        switch (name) {
            case "rage" -> {
                int bonus = rageDamageBonus(level);
                ActiveEffect effect = new ActiveEffect(RAGE, "Rage", 10, 0, 0, "Rage");
                effect.setMeleeDamageBonus(bonus);
                effect.setResistances(PHYSICAL);
                effect.setDescription("+" + bonus + " damage on Strength melee attacks, resistance to bludgeoning,"
                    + " piercing and slashing damage, advantage on Strength checks and saving throws."
                    + " Ends early if you neither attack nor take damage for a round.");
                Action rage = new Action(RAGE, "Rage", effect.getDescription(), Kind.SELF_EFFECT,
                    rageUses(level), Recharge.LONG_REST);
                rage.effect = effect;
                kit.add(rage);
            }
            case "unarmored defense" -> {
                kit.defense = "monk".equalsIgnoreCase(classId) ? Defense.MONK : Defense.BARBARIAN;
                kit.note(raw, desc);
            }
            case "reckless attack" -> {
                ActiveEffect effect = new ActiveEffect("reckless-attack", "Reckless", 1, 0, 0, "Reckless Attack");
                effect.setDescription("Advantage on Strength melee attacks this turn; attacks against you have"
                    + " advantage until your next turn.");
                Action reckless = new Action("reckless-attack", "Reckless Attack", effect.getDescription(),
                    Kind.SELF_EFFECT, 0, Recharge.AT_WILL);
                reckless.effect = effect;
                kit.add(reckless);
            }
            case "fast movement" -> {
                kit.fastMovement = true;
                kit.note(raw, "+10 ft walking speed while not in heavy armor (applied on the battle map).");
            }
            case "extra attack" -> {
                kit.extraAttacks = Math.max(kit.extraAttacks, raw.contains("(3)") ? 3 : raw.contains("(2)") ? 2 : 1);
                kit.note(raw, "Attack " + (kit.extraAttacks + 1) + " times when you take the Attack action.");
            }
            case "second wind" -> {
                Action a = new Action("second-wind", "Second Wind",
                    "Bonus action: regain 1d10 + " + level + " hit points.", Kind.SELF_HEAL, 1, Recharge.SHORT_REST);
                a.healDice = "1d10";
                a.healFlat = level;
                kit.add(a);
            }
            case "action surge" -> kit.add(new Action("action-surge", "Action Surge",
                "Take one additional action on this turn.", Kind.COUNTER, level >= 17 ? 2 : 1, Recharge.SHORT_REST));
            case "indomitable" -> kit.add(new Action("indomitable", "Indomitable",
                "Reroll a failed saving throw.", Kind.COUNTER, level >= 17 ? 3 : level >= 13 ? 2 : 1,
                Recharge.LONG_REST));
            case "martial arts" -> {
                kit.martialArtsDie = level >= 17 ? "1d10" : level >= 11 ? "1d8" : level >= 5 ? "1d6" : "1d4";
                kit.note(raw, "Unarmed strikes deal " + kit.martialArtsDie + " and may use Dexterity;"
                    + " one bonus-action unarmed strike after attacking.");
            }
            case "ki" -> kit.add(new Action("ki", "Ki points",
                "Flurry of Blows, Patient Defense, Step of the Wind, Stunning Strike - 1 point each.",
                Kind.COUNTER, level, Recharge.SHORT_REST));
            case "unarmored movement" -> {
                kit.unarmoredSpeedBonus = level >= 18 ? 30 : level >= 14 ? 25 : level >= 10 ? 20 : level >= 6 ? 15 : 10;
                kit.note(raw, "+" + kit.unarmoredSpeedBonus + " ft speed without armor or shield"
                    + " (applied on the battle map).");
            }
            case "divine sense" -> kit.add(new Action("divine-sense", "Divine Sense",
                "Sense celestials, fiends and undead within 60 ft until the end of your next turn.",
                Kind.COUNTER, 1 + Math.max(0, mod(stats, "cha")), Recharge.LONG_REST));
            case "lay on hands" -> {
                Action a = new Action("lay-on-hands", "Lay on Hands",
                    "Touch a creature and restore hit points from a pool of " + (5 * level) + ".",
                    Kind.HEAL_POOL, 5 * level, Recharge.LONG_REST);
                a.rangeFeet = 5;
                kit.add(a);
            }
            case "sneak attack" -> {
                kit.sneakAttackDice = (level + 1) / 2;
                kit.note(raw, "Once per turn +" + kit.sneakAttackDice + "d6 when you have advantage or an ally"
                    + " is next to the target (finesse or ranged weapon).");
            }
            case "bardic inspiration" -> {
                String die = level >= 15 ? "d12" : level >= 10 ? "d10" : level >= 5 ? "d8" : "d6";
                ActiveEffect effect = new ActiveEffect("bardic-inspiration", "Bardic Inspiration (" + die + ")",
                    100, 0, 0, "Bardic Inspiration");
                effect.setDescription("Add a " + die + " to one ability check, attack roll or saving throw within"
                    + " the next 10 minutes.");
                Action a = new Action("bardic-inspiration", "Bardic Inspiration (" + die + ")",
                    effect.getDescription(), Kind.ALLY_EFFECT, Math.max(1, mod(stats, "cha")),
                    level >= 5 ? Recharge.SHORT_REST : Recharge.LONG_REST);
                a.effect = effect;
                a.rangeFeet = 60;
                kit.add(a);
            }
            case "channel divinity" -> kit.add(new Action("channel-divinity", "Channel Divinity",
                "Turn Undead or your domain's Channel Divinity option.", Kind.COUNTER,
                level >= 18 ? 3 : level >= 6 ? 2 : 1, Recharge.SHORT_REST));
            case "wild shape" -> kit.add(new Action("wild-shape", "Wild Shape",
                "Turn into a beast you have seen.", Kind.COUNTER, 2, Recharge.SHORT_REST));
            case "font of magic" -> kit.add(new Action("sorcery-points", "Sorcery points",
                "Convert into spell slots or spend on Metamagic.", Kind.COUNTER, level, Recharge.LONG_REST));
            case "arcane recovery" -> kit.add(new Action("arcane-recovery", "Arcane Recovery",
                "After a short rest, recover spell slots totalling up to half your wizard level.",
                Kind.COUNTER, 1, Recharge.LONG_REST));
            case "combat fabrication" -> kit.add(new Action("combat-fabrication", "Combat Fabrication",
                "Bonus action: Emergency Patch (ally regains 1d8 + INT), Quick Cover (half cover until your next"
                    + " turn) or Special Charge (next device shot +1d6).", Kind.COUNTER, 3, Recharge.LONG_REST));
            case "flash of genius" -> kit.add(new Action("flash-of-genius", "Flash of Genius",
                "Reaction: add your Intelligence modifier to an ally's ability check or saving throw within 30 ft.",
                Kind.COUNTER, Math.max(1, mod(stats, "int")), Recharge.LONG_REST));
            case "bolt repeater" -> kit.add(new Action("bolt-repeater", "Deploy Bolt Repeater",
                "Action: unfold the turret within 5 ft (AC 8, HP " + Math.max(12, 5 * level - 13)
                    + "; Mending restores 1d6). Fire it as a bonus action. Free deployment once per long rest, later ones cost a charge.",
                Kind.COUNTER, 1, Recharge.LONG_REST));
            default -> kit.note(raw, desc);
        }
    }

    private static void applyRace(Kit kit, String race, int level, CoreStats stats) {
        if (race == null) return;
        switch (race) {
            case "dwarf" -> {
                kit.resist("poison");
                kit.note("Dwarven Resilience", "Resistance to poison damage, advantage on saves against poison.");
                kit.note("Darkvision", "See in dim light within 60 ft as if bright.");
            }
            case "elf" -> {
                kit.note("Fey Ancestry", "Advantage on saves against being charmed; magic can't put you to sleep.");
                kit.note("Darkvision", "See in dim light within 60 ft as if bright.");
            }
            case "halfling" -> {
                kit.note("Lucky", "Reroll a natural 1 on an attack, ability check or saving throw.");
                kit.note("Brave", "Advantage on saving throws against being frightened.");
            }
            case "dragonborn" -> {
                kit.resist("fire");
                int dice = level >= 16 ? 5 : level >= 11 ? 4 : level >= 6 ? 3 : 2;
                Action breath = new Action("breath-weapon", "Breath Weapon",
                    "Exhale destructive energy in a 15 ft area: " + dice + "d6 fire (DEX save for half).",
                    Kind.AREA_DAMAGE, 1, Recharge.SHORT_REST);
                breath.damageDice = dice + "d6";
                breath.damageType = "fire";
                breath.rangeFeet = 15;
                breath.radiusFeet = 10;
                kit.add(breath);
                kit.note("Draconic Resistance", "Resistance to your ancestry's damage type (fire assumed).");
            }
            case "gnome" -> kit.note("Gnome Cunning",
                "Advantage on Intelligence, Wisdom and Charisma saves against magic.");
            case "half-elf" -> kit.note("Fey Ancestry",
                "Advantage on saves against being charmed; magic can't put you to sleep.");
            case "half-orc", "orc" -> {
                kit.enduranceFeature = "relentless-endurance";
                kit.add(new Action("relentless-endurance", "Relentless Endurance",
                    "When reduced to 0 HP, drop to 1 HP instead (applied automatically).",
                    Kind.COUNTER, 1, Recharge.LONG_REST));
                kit.note("Savage Attacks", "On a melee critical hit, roll one weapon damage die an extra time.");
            }
            case "tiefling" -> {
                kit.resist("fire");
                kit.note("Hellish Resistance", "Resistance to fire damage.");
            }
            case "aasimar" -> {
                kit.resist("necrotic", "radiant");
                kit.note("Celestial Resistance", "Resistance to necrotic and radiant damage.");
                Action hands = new Action("healing-hands", "Healing Hands",
                    "Touch a creature and restore up to " + level + " hit points.", Kind.HEAL_POOL, level,
                    Recharge.LONG_REST);
                hands.rangeFeet = 5;
                kit.add(hands);
            }
            case "goliath" -> {
                kit.resist("cold");
                Action stone = new Action("stones-endurance", "Stone's Endurance",
                    "Reaction when hit: reduce the damage by 1d12 + " + mod(stats, "con") + ".",
                    Kind.SELF_HEAL, 1, Recharge.SHORT_REST);
                stone.healDice = "1d12";
                stone.healFlat = mod(stats, "con");
                kit.add(stone);
                kit.note("Mountain Born", "Resistance to cold damage; used to high altitude.");
            }
            case "goblin" -> {
                kit.add(new Action(FURY_OF_THE_SMALL, "Fury of the Small",
                    "Add " + level + " damage to a hit against a creature larger than you (tick it on an attack).",
                    Kind.COUNTER, 1, Recharge.SHORT_REST));
                kit.note("Nimble Escape", "Disengage or Hide as a bonus action.");
                kit.note("Darkvision", "See in dim light within 60 ft as if bright.");
            }
            case "revenant" -> {
                kit.resist("necrotic");
                kit.enduranceFeature = "undying";
                kit.add(new Action("undying", "Undying",
                    "Death already let go of you once: when reduced to 0 HP, drop to 1 HP instead"
                        + " (applied automatically).",
                    Kind.COUNTER, 1, Recharge.LONG_REST));
                kit.note("Grave-touched", "Resistance to necrotic damage.");
            }
            default -> {
            }
        }
    }

    /** Maps sub-races onto the race whose traits they share (hill-dwarf → dwarf). */
    public static String raceKey(String raceId) {
        if (raceId == null || raceId.isBlank()) return null;
        String id = raceId.toLowerCase(Locale.ROOT);
        for (String key : List.of("half-orc", "half-elf", "dragonborn", "tiefling", "aasimar", "goliath",
            "goblin", "revenant", "halfling", "gnome", "dwarf", "orc", "human")) {
            if (id.contains(key)) return key;
        }
        if (id.contains("elf") || id.equals("drow")) return "elf";
        return id;
    }

    public static int rageUses(int level) {
        if (level >= 17) return 6;
        if (level >= 12) return 5;
        if (level >= 6) return 4;
        if (level >= 3) return 3;
        return 2;
    }

    public static int rageDamageBonus(int level) {
        return level >= 16 ? 4 : level >= 9 ? 3 : 2;
    }

    // ── Using features ──────────────────────────────────────────────────────

    /** Marks {@code amount} uses (or pool points) spent; false when not enough are left. */
    public static boolean spend(Map<String, Integer> spent, Action action, int amount) {
        if (!action.isLimited()) return true;
        if (amount <= 0 || action.usesLeft(spent) < amount) return false;
        spent.merge(action.getId(), amount, Integer::sum);
        return true;
    }

    /**
     * Uses a feature that only affects its user (or nobody): applies the effect, rolls the
     * healing or just spends the use. Returns the log line, or a refusal when it is spent.
     */
    public static String useOnSelf(Action action, CombatState self, String selfName, Random rng) {
        if (action.needsTarget()) return action.getName() + " needs a target.";
        if (action.usesLeft(self.getFeatureUses()) <= 0) {
            return action.getName() + " is spent until a " + action.getRecharge().label() + ".";
        }
        spend(self.getFeatureUses(), action, 1);
        switch (action.getKind()) {
            case SELF_EFFECT -> {
                self.addEffect(action.getEffect().copy());
                return selfName + " uses " + action.getName() + ".";
            }
            case SELF_HEAL -> {
                int rolled = DiceExpr.roll(action.getHealDice(), rng);
                int total = Math.max(0, rolled + action.getHealFlat());
                self.heal(total);
                return selfName + " uses " + action.getName() + " and regains " + total + " HP ("
                    + action.getHealDice() + " rolled " + rolled + " + " + action.getHealFlat() + ").";
            }
            default -> {
                return selfName + " uses " + action.getName() + ".";
            }
        }
    }

    /** Heals {@code target} from a pool feature (Lay on Hands). */
    public static String useHealPool(Action action, CombatState user, String userName,
                                     CombatState target, String targetName, int points) {
        int amount = Math.min(points, action.usesLeft(user.getFeatureUses()));
        if (amount <= 0) return action.getName() + " has no points left.";
        spend(user.getFeatureUses(), action, amount);
        target.heal(amount);
        return userName + " uses " + action.getName() + ": " + targetName + " regains " + amount + " HP.";
    }

    /** Puts an ally-buff feature's effect on {@code target} (Bardic Inspiration). */
    public static String useOnAlly(Action action, CombatState user, String userName,
                                   CombatState target, String targetName) {
        if (!spend(user.getFeatureUses(), action, 1)) return action.getName() + " is spent.";
        target.addEffect(action.getEffect().copy());
        return userName + " gives " + targetName + " " + action.getName() + ".";
    }

    /** Whether a feature's uses come back on a short rest for a character of {@code level}. */
    public static boolean rechargesOnShortRest(String id, int level) {
        if (id == null) return false;
        return switch (id) {
            case "second-wind", "action-surge", "ki", "channel-divinity", "wild-shape", "breath-weapon",
                 "stones-endurance", FURY_OF_THE_SMALL -> true;
            case "bardic-inspiration" -> level >= 5;
            default -> false;
        };
    }

    /** Restores feature uses after a rest. */
    public static void recover(Map<String, Integer> spent, boolean longRest, int level) {
        if (spent == null) return;
        if (longRest) spent.clear();
        else spent.keySet().removeIf(id -> rechargesOnShortRest(id, level));
    }

    /**
     * Copies a character's innate resistances, endurance trait and feature speed bonus onto
     * their combat state. The speed bonus is applied as a difference from what was applied
     * last time, so the DM's own speed edits survive and levelling up adds just the change.
     */
    public static void seed(CombatState state, Kit kit, Worn worn) {
        state.setResistances(kit.getResistances());
        state.setEnduranceFeature(kit.getEnduranceFeature());
        int bonus = kit.speedBonus(worn != null && worn.wearingArmor(), worn != null && worn.heavy());
        int delta = bonus - state.getFeatureSpeedBonus();
        if (delta != 0) {
            state.setWalkSpeed(Math.max(0, state.getWalkSpeed() + delta));
            state.setFeatureSpeedBonus(bonus);
        }
    }

    // ── Armor Class ─────────────────────────────────────────────────────────

    /** What a character is wearing, as far as Armor Class is concerned. */
    public record Worn(int body, int shield, int accessories) {
        public boolean wearingArmor() {
            return body > 0 || shield > 0;
        }

        public boolean heavy() {
            return body >= 6;
        }
    }

    /**
     * The armor counted for AC: whatever is marked equipped, or every armor piece carried
     * when nothing is marked (older sheets never set the flag).
     */
    public static Worn worn(PlayerCharacter pc, Function<String, Item> items) {
        List<Armor> pieces = new ArrayList<>();
        List<Armor> equipped = new ArrayList<>();
        if (pc != null && pc.getItems() != null && items != null) {
            for (PlayerCharacter.PlayerItem carried : pc.getItems()) {
                if (carried == null || carried.getItemId() == null) continue;
                if (items.apply(carried.getItemId()) instanceof Armor armor) {
                    pieces.add(armor);
                    if (carried.isEquipped()) equipped.add(armor);
                }
            }
        }
        List<Armor> counted = equipped.isEmpty() ? pieces : equipped;
        int body = 0;
        int shield = 0;
        int accessories = 0;
        for (Armor armor : counted) {
            String key = ((armor.getId() == null ? "" : armor.getId()) + " "
                + (armor.getName() == null ? "" : armor.getName())).toLowerCase(Locale.ROOT);
            if (key.contains("shield")) {
                shield = Math.max(shield, armor.getArmorClassBonus());
            } else if (key.matches(".*(helm|gauntlet|glove|greave|boot|cloak|bracer|ring|amulet).*")) {
                accessories += armor.getArmorClassBonus();
            } else {
                body = Math.max(body, armor.getArmorClassBonus());
            }
        }
        return new Worn(body, shield, accessories);
    }

    public record ArmorClass(int total, String breakdown) {
    }

    public static ArmorClass armorClass(PlayerCharacter pc, Kit kit, Function<String, Item> items, int effectBonus) {
        Worn worn = worn(pc, items);
        CoreStats stats = pc == null ? null : pc.getStats();
        int dex = mod(stats, "dex");
        List<String> parts = new ArrayList<>();
        int ac;
        if (worn.body() > 0) {
            int dexPart = worn.body() >= 6 ? 0 : worn.body() >= 3 ? Math.min(dex, 2) : dex;
            ac = 10 + worn.body() + dexPart;
            parts.add("armor 10+" + worn.body());
            if (dexPart != 0) parts.add("DEX " + signed(dexPart));
        } else if (kit != null && kit.getDefense() == Defense.BARBARIAN) {
            int con = mod(stats, "con");
            ac = 10 + dex + con;
            parts.add("Unarmored Defense 10 " + signed(dex) + " DEX " + signed(con) + " CON");
        } else if (kit != null && kit.getDefense() == Defense.MONK && worn.shield() == 0) {
            int wis = mod(stats, "wis");
            ac = 10 + dex + wis;
            parts.add("Unarmored Defense 10 " + signed(dex) + " DEX " + signed(wis) + " WIS");
        } else {
            ac = 10 + dex;
            parts.add("10 " + signed(dex) + " DEX");
        }
        if (worn.shield() > 0) {
            ac += worn.shield();
            parts.add("shield +" + worn.shield());
        }
        if (worn.accessories() > 0) {
            ac += worn.accessories();
            parts.add("worn pieces +" + worn.accessories());
        }
        if (effectBonus != 0) {
            ac += effectBonus;
            parts.add("effects " + signed(effectBonus));
        }
        return new ArmorClass(ac, String.join(", ", parts));
    }

    // ── Attacks ─────────────────────────────────────────────────────────────

    /** A weapon (or fist) attack with its numbers already worked out. */
    public record Attack(String id, String name, String dice, String damageType, boolean melee,
                         boolean strengthBased, boolean finesseOrRanged, int toHit, int damageBonus,
                         double reachFeet) {
        public String damageLabel() {
            String flat = damageBonus == 0 ? "" : " " + (damageBonus > 0 ? "+ " : "- ") + Math.abs(damageBonus);
            return (dice == null || dice.isBlank() ? String.valueOf(damageBonus) : dice + flat)
                + (damageType == null ? "" : " " + damageType);
        }
    }

    /**
     * Every weapon the character carries plus an unarmed strike. Firearms are left out: they
     * shoot through their own spells, which spend the ammunition.
     */
    public static List<Attack> attacks(PlayerCharacter pc, Kit kit, Function<String, Item> items) {
        List<Attack> out = new ArrayList<>();
        if (pc == null) return out;
        CoreStats stats = pc.getStats();
        int str = mod(stats, "str");
        int dex = mod(stats, "dex");
        int prof = kit == null ? Progression.proficiencyBonus(Math.max(1, pc.getLevel())) : kit.getProficiency();
        List<String> seen = new ArrayList<>();
        if (pc.getItems() != null && items != null) {
            for (PlayerCharacter.PlayerItem carried : pc.getItems()) {
                if (carried == null || carried.getItemId() == null || seen.contains(carried.getItemId())) continue;
                Item item = items.apply(carried.getItemId());
                if (item == null || !(item instanceof Weapon || "weapon".equalsIgnoreCase(item.getType())) || item.getDamage() == null
                    || item.getDamage().getDice() == null || isFirearm(item)) continue;
                seen.add(carried.getItemId());
                String key = (item.getId() + " " + item.getName()).toLowerCase(Locale.ROOT);
                boolean ranged = key.matches(".*(bow|sling|dart|blowgun).*");
                boolean finesse = ranged || key.matches(".*(dagger|rapier|shortsword|scimitar|whip|katana|blade).*");
                boolean useStr = !ranged && (!finesse || str >= dex);
                int abilityMod = useStr ? str : dex;
                double reach = ranged ? rangedReach(key) : key.matches(".*(glaive|halberd|pike|lance|whip).*") ? 10 : 5;
                out.add(new Attack(item.getId(), item.getName(), item.getDamage().getDice(),
                    normaliseType(item.getDamage().getType()), !ranged, useStr, finesse, prof + abilityMod,
                    abilityMod, reach));
            }
        }
        String martial = kit == null ? null : kit.getMartialArtsDie();
        if (martial != null) {
            int best = Math.max(str, dex);
            out.add(new Attack("unarmed", "Unarmed strike", martial, "bludgeoning", true, str >= dex, false,
                prof + best, best, 5));
        } else {
            out.add(new Attack("unarmed", "Unarmed strike", null, "bludgeoning", true, true, false,
                prof + str, Math.max(1, 1 + str), 5));
        }
        return out;
    }

    private static boolean isFirearm(Item item) {
        String text = ((item.getName() == null ? "" : item.getName()) + " "
            + (item.getDescription() == null ? "" : item.getDescription())).toLowerCase(Locale.ROOT);
        return text.matches("(?s).*\\b(revolver|pistol|musket|rifle|firearm)\\b.*");
    }

    private static double rangedReach(String key) {
        if (key.contains("longbow")) return 150;
        if (key.contains("heavy")) return 100;
        if (key.contains("bow")) return 80;
        if (key.contains("sling")) return 30;
        if (key.contains("blowgun")) return 25;
        return 20;
    }

    private static String normaliseType(String type) {
        if (type == null) return null;
        String t = type.toLowerCase(Locale.ROOT);
        return t.equals("cutting") ? "slashing" : t;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    static String baseName(String name) {
        if (name == null) return "";
        int paren = name.indexOf('(');
        return (paren > 0 ? name.substring(0, paren) : name).trim();
    }

    public static int mod(CoreStats stats, String ability) {
        if (stats == null) return 0;
        int score = switch (ability) {
            case "str" -> stats.getStrength();
            case "dex" -> stats.getDexterity();
            case "con" -> stats.getConstitution();
            case "int" -> stats.getIntelligence();
            case "wis" -> stats.getWisdom();
            default -> stats.getCharisma();
        };
        return Progression.modifier(score);
    }

    private static String signed(int n) {
        return n >= 0 ? "+" + n : String.valueOf(n);
    }

    /** Rolls simple dice notation like "1d10" or "2d6" (a bare number counts as no dice). */
    public static int roll(String dice, Random rng) {
        return DiceExpr.roll(dice, rng);
    }

    static final class DiceExpr {
        final int count;
        final int sides;

        private DiceExpr(int count, int sides) {
            this.count = count;
            this.sides = sides;
        }

        static DiceExpr parse(String text) {
            if (text == null) return null;
            String t = text.trim().toLowerCase(Locale.ROOT);
            int d = t.indexOf('d');
            try {
                if (d < 0) return new DiceExpr(0, 0);
                int count = d == 0 ? 1 : Integer.parseInt(t.substring(0, d));
                return new DiceExpr(count, Integer.parseInt(t.substring(d + 1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        static int roll(String text, Random rng) {
            DiceExpr dice = parse(text);
            if (dice == null || dice.sides <= 0) return 0;
            int total = 0;
            for (int i = 0; i < dice.count; i++) total += 1 + rng.nextInt(dice.sides);
            return total;
        }
    }
}
