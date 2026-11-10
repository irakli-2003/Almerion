package com.dnd.model.world.map;

import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.combat.Ability;
import com.dnd.model.creature.Beast;
import com.dnd.model.creature.Monster;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reads display and combat information out of any {@link MapObject}.
 *
 * <p>Tokens wrap five unrelated model types that share no common supertype beyond
 * {@code MapObject}, so anything that needs a token's name, portrait, stats or combat state
 * would otherwise repeat the same {@code instanceof} chain. Centralising it here keeps the
 * battle map's rendering and panels free of that noise, and means adding a new token type
 * only requires editing this class.</p>
 */
public final class TokenSupport {

    private static final Random RANDOM = new Random();

    private TokenSupport() {
    }

    // ── Identity ────────────────────────────────────────────────────────────

    public static String nameOf(MapObject token) {
        if (token == null) return "(none)";
        if (token instanceof PlayerToken t) return orDefault(t.getCharacter() == null ? null : t.getCharacter().getName(), "Player");
        if (token instanceof NpcToken t) return orDefault(t.getNpc() == null ? null : t.getNpc().getName(), "NPC");
        if (token instanceof MonsterToken t) return orDefault(t.getMonster() == null ? null : t.getMonster().getName(), "Monster");
        if (token instanceof BeastToken t) return orDefault(t.getBeast() == null ? null : t.getBeast().getName(), "Beast");
        if (token instanceof MapEntity t) return orDefault(t.getName(), "Entity");
        return token.getSymbol();
    }

    /** Short human label for the token's kind, used in list rows and the detail panel. */
    public static String kindOf(MapObject token) {
        if (token instanceof PlayerToken) return "Player";
        if (token instanceof NpcToken) return "NPC";
        if (token instanceof MonsterToken) return "Monster";
        if (token instanceof BeastToken) return "Beast";
        if (token instanceof MapItemToken) return "Item";
        return "Object";
    }

    /** Campaign-relative portrait path, or null when the token has no picture. */
    public static String imagePathOf(MapObject token) {
        if (token instanceof PlayerToken t && t.getCharacter() != null) return t.getCharacter().getImagePath();
        if (token instanceof NpcToken t && t.getNpc() != null) return t.getNpc().getImagePath();
        if (token instanceof MonsterToken t && t.getMonster() != null) return t.getMonster().getImagePath();
        if (token instanceof BeastToken t && t.getBeast() != null) return t.getBeast().getImagePath();
        return null;
    }

    /** True for tokens that represent a creature and therefore take part in combat. */
    public static boolean isCreature(MapObject token) {
        return token instanceof PlayerToken || token instanceof NpcToken
            || token instanceof MonsterToken || token instanceof BeastToken;
    }

    public static CoreStats statsOf(MapObject token) {
        if (token instanceof PlayerToken t && t.getCharacter() != null) return t.getCharacter().getStats();
        if (token instanceof NpcToken t && t.getNpc() != null) return t.getNpc().getStats();
        if (token instanceof MonsterToken t && t.getMonster() != null) return t.getMonster().getStats();
        if (token instanceof BeastToken t && t.getBeast() != null) return t.getBeast().getStats();
        return null;
    }

    /**
     * Armor Class written on an NPC, monster or beast sheet (0 when unknown). Player
     * characters work theirs out from armor and class features instead.
     */
    public static int sheetArmorClassOf(MapObject token) {
        if (token instanceof NpcToken t && t.getNpc() != null) return t.getNpc().getArmorClass();
        if (token instanceof MonsterToken t && t.getMonster() != null) return t.getMonster().getArmorClass();
        if (token instanceof BeastToken t && t.getBeast() != null) return t.getBeast().getArmorClass();
        return 0;
    }

    public static List<Ability> abilitiesOf(MapObject token) {
        if (token instanceof MonsterToken t && t.getMonster() != null && t.getMonster().getAbilities() != null) {
            return t.getMonster().getAbilities();
        }
        if (token instanceof BeastToken t && t.getBeast() != null && t.getBeast().getAbilities() != null) {
            return t.getBeast().getAbilities();
        }
        return List.of();
    }

    /** Spell ids known by this token, resolved against the campaign's spell catalog by the caller. */
    public static List<String> spellIdsOf(MapObject token) {
        if (token instanceof PlayerToken t && t.getCharacter() != null && t.getCharacter().getSpells() != null) {
            List<String> ids = new ArrayList<>();
            for (PlayerCharacter.PlayerSpell spell : t.getCharacter().getSpells()) {
                if (spell != null && spell.getSpellId() != null) ids.add(spell.getSpellId());
            }
            return ids;
        }
        return List.of();
    }

    /** Item ids carried by this token, resolved against the campaign's item catalog by the caller. */
    public static List<String> itemIdsOf(MapObject token) {
        if (token instanceof PlayerToken t && t.getCharacter() != null && t.getCharacter().getItems() != null) {
            List<String> ids = new ArrayList<>();
            for (PlayerCharacter.PlayerItem item : t.getCharacter().getItems()) {
                if (item != null && item.getItemId() != null) ids.add(item.getItemId());
            }
            return ids;
        }
        return List.of();
    }

    // ── Combat state ────────────────────────────────────────────────────────

    /** Returns this token's combat state, creating and attaching a sensible default first time. */
    public static CombatState combatOf(MapObject token) {
        if (token == null) return new CombatState();
        CombatState existing = readCombat(token);
        if (existing != null) return existing;
        CombatState fresh = defaultCombatFor(token);
        writeCombat(token, fresh);
        return fresh;
    }

    private static CombatState readCombat(MapObject token) {
        if (token instanceof PlayerToken t) return t.getCombat();
        if (token instanceof NpcToken t) return t.getCombat();
        if (token instanceof MonsterToken t) return t.getCombat();
        if (token instanceof BeastToken t) return t.getCombat();
        if (token instanceof MapEntity t) return t.getCombat();
        return null;
    }

    /** Replaces a token's combat state outright (used when rewinding time). */
    public static void setCombat(MapObject token, CombatState state) {
        writeCombat(token, state);
    }

    private static void writeCombat(MapObject token, CombatState state) {
        if (token instanceof PlayerToken t) t.setCombat(state);
        else if (token instanceof NpcToken t) t.setCombat(state);
        else if (token instanceof MonsterToken t) t.setCombat(state);
        else if (token instanceof BeastToken t) t.setCombat(state);
        else if (token instanceof MapEntity t) t.setCombat(state);
    }

    /**
     * Builds starting combat numbers for a token that has never been in a battle.
     *
     * <p>The campaign models store ability scores but no hit points or mana, so these are
     * derived with the usual tabletop shape - a hit die per level plus the constitution
     * modifier - purely as a starting point. Every value is editable in the battle map,
     * which is the authoritative source once a fight is underway.</p>
     */
    static CombatState defaultCombatFor(MapObject token) {
        CoreStats stats = statsOf(token);
        int level = levelOf(token);
        int conMod = modifier(stats == null ? 10 : stats.getConstitution());
        int intMod = modifier(stats == null ? 10 : stats.getIntelligence());

        CombatState state = new CombatState();
        if (token instanceof PlayerToken t && t.getCharacter() != null && t.getCharacter().getMaxHitPoints() > 0) {
            // The DM has already set this character's vitals from the storyline editor (or a
            // previous session) - a freshly placed token should start from that, not overwrite
            // it with the generic level-based estimate.
            pullVitals(t.getCharacter(), state);
        } else if (creatureMaxHp(token) > 0) {
            state.setMaxHitPoints(creatureMaxHp(token));
            state.setCurrentHitPoints(state.getMaxHitPoints());
            state.setMaxMana(creatureMaxMana(token));
            state.setCurrentMana(state.getMaxMana());
            List<com.dnd.model.magic.SpellSlot> slots = creatureSlots(token);
            state.setSpellSlots(com.dnd.model.magic.SpellSlots.copy(slots));
            com.dnd.model.magic.SpellSlots.restoreAll(state.getSpellSlots());
        } else {
            state.setMaxHitPoints(Math.max(1, level * (6 + conMod)));
            state.setCurrentHitPoints(state.getMaxHitPoints());
            state.setMaxMana(Math.max(0, level * Math.max(0, 2 + intMod)));
            state.setCurrentMana(state.getMaxMana());
        }
        state.setInInitiative(isCreature(token));
        return state;
    }

    // ── Character sheet ⇄ battle map (single source of truth) ───────────────

    /**
     * Copies the persistent sheet's vitals, effects, cooldowns and spell slots into a token's
     * combat state, so the battle map always starts from what the sheet says.
     */
    public static void pullVitals(PlayerCharacter pc, CombatState state) {
        if (pc == null || state == null) return;
        if (pc.getMaxHitPoints() > 0) {
            state.setMaxHitPoints(pc.getMaxHitPoints());
            state.setCurrentHitPoints(pc.getCurrentHitPoints());
            if (pc.getCurrentHitPoints() > 0) {
                state.setDowned(false);
                if (state.isDead()) state.setDead(false);
                state.setDeathSaveFailures(0);
            } else if (!state.isDead()) {
                state.setDowned(true);
            }
        }
        state.setMaxMana(pc.getMaxMana());
        state.setCurrentMana(pc.getCurrentMana());
        List<ActiveEffect> effects = new ArrayList<>();
        if (pc.getActiveEffects() != null) {
            for (ActiveEffect e : pc.getActiveEffects()) if (e != null) effects.add(e.copy());
        }
        state.setActiveEffects(effects);
        state.setCooldowns(new java.util.LinkedHashMap<>(pc.getCooldowns()));
        state.setFeatureUses(new java.util.LinkedHashMap<>(pc.getFeatureUses()));
        state.setSpellSlots(com.dnd.model.magic.SpellSlots.copy(pc.getSpellSlots()));
        state.setCastingResource(pc.getCastingResource());
        Integer gold = pc.getSheet().getGold();
        if (gold != null) state.setGold(gold);
    }

    /** Writes a token's live combat numbers back onto its persistent character sheet. */
    public static void pushVitals(CombatState state, PlayerCharacter pc) {
        if (pc == null || state == null) return;
        pc.setMaxHitPoints(state.getMaxHitPoints());
        pc.setCurrentHitPoints(state.getCurrentHitPoints());
        pc.setMaxMana(state.getMaxMana());
        pc.setCurrentMana(state.getCurrentMana());
        List<ActiveEffect> effects = new ArrayList<>();
        for (ActiveEffect e : state.getActiveEffects()) if (e != null) effects.add(e.copy());
        pc.setActiveEffects(effects);
        pc.setCooldowns(new java.util.LinkedHashMap<>(state.getCooldowns()));
        pc.setFeatureUses(new java.util.LinkedHashMap<>(state.getFeatureUses()));
        pc.setSpellSlots(com.dnd.model.magic.SpellSlots.copy(state.getSpellSlots()));
        pc.getSheet().setGold(state.getGold());
    }

    /**
     * Replaces a player token's embedded character copy with the latest sheet and pulls its
     * vitals in. A sheet with no hit points yet is instead seeded from the token.
     */
    public static void refreshFromSheet(PlayerToken token, PlayerCharacter sheet) {
        if (token == null || sheet == null) return;
        CombatState state = combatOf(token);
        token.setCharacter(sheet);
        if (sheet.getMaxHitPoints() > 0) {
            pullVitals(sheet, state);
        } else {
            pushVitals(state, sheet);
            state.setCastingResource(sheet.getCastingResource());
        }
    }

    public static int creatureMaxHp(MapObject token) {
        if (token instanceof NpcToken t && t.getNpc() != null) return t.getNpc().getMaxHitPoints();
        if (token instanceof MonsterToken t && t.getMonster() != null) return t.getMonster().getMaxHitPoints();
        if (token instanceof BeastToken t && t.getBeast() != null) return t.getBeast().getMaxHitPoints();
        return 0;
    }

    public static int creatureMaxMana(MapObject token) {
        if (token instanceof NpcToken t && t.getNpc() != null) return t.getNpc().getMaxMana();
        if (token instanceof MonsterToken t && t.getMonster() != null) return t.getMonster().getMaxMana();
        if (token instanceof BeastToken t && t.getBeast() != null) return t.getBeast().getMaxMana();
        return 0;
    }

    public static List<com.dnd.model.magic.SpellSlot> creatureSlots(MapObject token) {
        if (token instanceof NpcToken t && t.getNpc() != null) return t.getNpc().getSpellSlots();
        if (token instanceof MonsterToken t && t.getMonster() != null) return t.getMonster().getSpellSlots();
        if (token instanceof BeastToken t && t.getBeast() != null) return t.getBeast().getSpellSlots();
        return List.of();
    }

    /** Level, or a level-equivalent derived from challenge rating for monsters and beasts. */
    public static int levelOf(MapObject token) {
        if (token instanceof PlayerToken t && t.getCharacter() != null) return Math.max(1, t.getCharacter().getLevel());
        if (token instanceof NpcToken t && t.getNpc() != null) return Math.max(1, t.getNpc().getLevel());
        if (token instanceof MonsterToken t && t.getMonster() != null) return challengeLevel(t.getMonster());
        if (token instanceof BeastToken t && t.getBeast() != null) return challengeLevel(t.getBeast());
        return 1;
    }

    /** True for tokens whose level is a real, settable field (players and NPCs) rather than
     *  a challenge-rating stand-in (monsters and beasts). */
    public static boolean hasSettableLevel(MapObject token) {
        return (token instanceof PlayerToken pt && pt.getCharacter() != null)
            || (token instanceof NpcToken nt && nt.getNpc() != null);
    }

    /** Writes a new level onto the underlying model. Only valid when {@link #hasSettableLevel} is true. */
    public static void setLevelOf(MapObject token, int level) {
        if (token instanceof PlayerToken t && t.getCharacter() != null) t.getCharacter().setLevel(level);
        else if (token instanceof NpcToken t && t.getNpc() != null) t.getNpc().setLevel(level);
    }

    /** Current XP, or -1 when this token doesn't track experience (only player characters do). */
    public static int xpOf(MapObject token) {
        if (token instanceof PlayerToken t && t.getCharacter() != null) return t.getCharacter().getXp();
        return -1;
    }

    /** Adds (or removes, with a negative amount) experience. No-op for tokens that don't track it. */
    public static void addXpOf(MapObject token, int amount) {
        if (token instanceof PlayerToken t && t.getCharacter() != null) t.getCharacter().addXp(amount);
    }

    private static int challengeLevel(Monster monster) {
        return monster.getChallengeRating() == null ? 1 : Math.max(1, monster.getChallengeRating().ordinal());
    }

    private static int challengeLevel(Beast beast) {
        return beast.getChallengeRating() == null ? 1 : Math.max(1, beast.getChallengeRating().ordinal());
    }

    /** Standard ability-score modifier: (score - 10) / 2, rounded down. */
    public static int modifier(int abilityScore) {
        return Math.floorDiv(abilityScore - 10, 2);
    }

    /** Rolls d20 + initiative bonus (a player's character-sheet override, else dexterity modifier). */
    public static int rollInitiative(MapObject token) {
        if (token instanceof PlayerToken t && t.getCharacter() != null) {
            return RANDOM.nextInt(20) + 1 + com.dnd.model.rules.SheetValues.initiative(t.getCharacter());
        }
        CoreStats stats = statsOf(token);
        int dexMod = modifier(stats == null ? 10 : stats.getDexterity());
        return RANDOM.nextInt(20) + 1 + dexMod;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Description shown in the detail panel, when the underlying model has one. */
    public static String descriptionOf(MapObject token) {
        if (token instanceof NpcToken t && t.getNpc() != null) return t.getNpc().getDescription();
        if (token instanceof MonsterToken t && t.getMonster() != null) return t.getMonster().getDescription();
        if (token instanceof BeastToken t && t.getBeast() != null) return t.getBeast().getDescription();
        if (token instanceof PlayerToken t && t.getCharacter() != null) {
            PlayerCharacter pc = t.getCharacter();
            return "Level " + pc.getLevel() + (pc.getPlayerName() == null ? "" : " - played by " + pc.getPlayerName());
        }
        return null;
    }
}
