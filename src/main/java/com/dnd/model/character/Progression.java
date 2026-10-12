package com.dnd.model.character;

import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.magic.CastingResource;
import com.dnd.model.magic.SpellSlot;
import com.dnd.model.magic.SpellSlots;
import com.dnd.model.magic.SpellcastingType;
import com.dnd.model.world.map.ActiveEffect;
import com.dnd.model.world.map.CombatState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Campaign rules for growing and recovering: the homebrew 100-XP level-up and the
 * short/long rest. Kept free of any UI so it can be unit tested and reused by the
 * character sheet, the storyline editor and the battle map alike.
 */
public final class Progression {

    /** XP needed to level up; the counter resets to zero afterwards (campaign homebrew). */
    public static final int XP_PER_LEVEL = 100;

    private static final int[] ASI_LEVELS = {4, 8, 12, 16, 19};

    private Progression() {
    }

    public static boolean canLevelUp(PlayerCharacter pc) {
        return pc != null && pc.getXp() >= XP_PER_LEVEL && pc.getLevel() < PlayerCharacter.MAX_LEVEL;
    }

    public static int modifier(int score) {
        return Math.floorDiv(score - 10, 2);
    }

    public static int proficiencyBonus(int level) {
        return 2 + (Math.max(1, level) - 1) / 4;
    }

    /** Casting resource a character really uses: their override, else slots for casters. */
    public static CastingResource resourceOf(PlayerCharacter pc, CharacterClass cls) {
        if (pc != null && pc.getCastingResource() != null) return pc.getCastingResource();
        SpellcastingType type = cls == null ? SpellcastingType.NONE : cls.getSpellcasting();
        if (type == SpellcastingType.NONE) return CastingResource.MANA;
        return type == SpellcastingType.ENERGY ? CastingResource.ENERGY_CHARGES : CastingResource.SPELL_SLOTS;
    }

    /** Everything that changes when {@code pc} reaches the next level - shown before applying. */
    public static final class LevelUpPlan {
        public int fromLevel;
        public int toLevel;
        public int hitDieSides;
        public int conModifier;
        /** Hit points gained: the DM's bonus roll on the hit die plus the CON modifier. */
        public int hitPointGain;
        public int manaGain;
        public int[] slotsBefore = new int[9];
        public int[] slotsAfter = new int[9];
        public List<ClassFeature> features = new ArrayList<>();
        public boolean abilityScoreImprovement;
        public int proficiencyBonus;
        public CastingResource resource;
    }

    public static LevelUpPlan plan(PlayerCharacter pc, CharacterClass cls) {
        LevelUpPlan plan = new LevelUpPlan();
        plan.fromLevel = pc.getLevel();
        plan.toLevel = Math.min(PlayerCharacter.MAX_LEVEL, pc.getLevel() + 1);
        plan.hitDieSides = cls != null && cls.getHitDie() != null && cls.getHitDie().getSides() > 0
            ? cls.getHitDie().getSides() : 8;
        CoreStats stats = pc.getStats();
        plan.conModifier = stats == null ? 0 : modifier(stats.getConstitution());
        plan.hitPointGain = Math.max(1, averageHitDie(plan.hitDieSides) + plan.conModifier);
        plan.resource = resourceOf(pc, cls);
        if (plan.resource == CastingResource.MANA && pc.getMaxMana() > 0) {
            int intMod = stats == null ? 0 : modifier(stats.getIntelligence());
            plan.manaGain = Math.max(1, 2 + intMod);
        }
        SpellcastingType type = cls == null ? SpellcastingType.NONE : cls.getSpellcasting();
        if (plan.resource.usesSlots()) {
            plan.slotsBefore = SpellSlots.maxSlots(type, plan.fromLevel);
            plan.slotsAfter = SpellSlots.maxSlots(type, plan.toLevel);
        }
        if (cls != null) plan.features = cls.featuresAt(plan.toLevel, pc.getSubclass());
        for (int asi : ASI_LEVELS) {
            if (asi == plan.toLevel) plan.abilityScoreImprovement = true;
        }
        plan.proficiencyBonus = proficiencyBonus(plan.toLevel);
        return plan;
    }

    /** The rounded-up average of a hit die, the usual "take the average" level-up value. */
    public static int averageHitDie(int sides) {
        return sides / 2 + 1;
    }

    /**
     * Applies a (possibly DM-edited) plan: +1 level, XP back to zero, more max HP and mana
     * (current values grow by the same amount), new slot maxima.
     */
    public static void apply(PlayerCharacter pc, LevelUpPlan plan) {
        pc.setLevel(plan.toLevel);
        pc.setXp(0);
        if (plan.hitPointGain != 0) {
            int max = Math.max(0, pc.getMaxHitPoints() + plan.hitPointGain);
            int current = pc.getCurrentHitPoints() + plan.hitPointGain;
            pc.setMaxHitPoints(max);
            pc.setCurrentHitPoints(current);
        }
        if (plan.manaGain != 0) {
            pc.setMaxMana(pc.getMaxMana() + plan.manaGain);
            pc.setCurrentMana(pc.getCurrentMana() + plan.manaGain);
        }
        if (plan.resource.usesSlots()) {
            pc.setSpellSlots(SpellSlots.withMax(pc.getSpellSlots(), plan.slotsAfter));
        }
    }

    /** Fills in slot maxima for a caster who has none yet (older sheets), all unspent. */
    public static boolean ensureSlots(PlayerCharacter pc, CharacterClass cls) {
        if (pc == null || !resourceOf(pc, cls).usesSlots()) return false;
        if (pc.getCastingResource() == null && cls != null && cls.getSpellcasting() == SpellcastingType.ENERGY) {
            pc.setCastingResource(CastingResource.ENERGY_CHARGES);
        }
        if (pc.getSpellSlots() != null && !pc.getSpellSlots().isEmpty()) return false;
        SpellcastingType type = cls == null ? SpellcastingType.NONE : cls.getSpellcasting();
        List<SpellSlot> slots = SpellSlots.fresh(SpellSlots.maxSlots(type, pc.getLevel()));
        if (slots.isEmpty()) return false;
        pc.setSpellSlots(slots);
        return true;
    }

    // ── Rests ───────────────────────────────────────────────────────────────

    /**
     * Short rest: the DM-entered hit points come back (from spent hit dice), warlock pact
     * slots refill, half of the missing mana returns and effects with a timer wear off.
     */
    public static List<String> shortRest(PlayerCharacter pc, CharacterClass cls, int hitPointsRecovered) {
        List<String> log = new ArrayList<>();
        if (hitPointsRecovered > 0) {
            int before = pc.getCurrentHitPoints();
            pc.heal(hitPointsRecovered);
            log.add("recovers " + (pc.getCurrentHitPoints() - before) + " HP");
        }
        if (cls != null && cls.getSpellcasting() == SpellcastingType.PACT && !pc.getSpellSlots().isEmpty()) {
            SpellSlots.restoreAll(pc.getSpellSlots());
            log.add("regains pact slots");
        }
        int missing = pc.getMaxMana() - pc.getCurrentMana();
        if (missing > 0) {
            int back = (missing + 1) / 2;
            pc.setCurrentMana(pc.getCurrentMana() + back);
            log.add("regains " + back + " mana");
        }
        com.dnd.model.rules.FeatureRules.recover(pc.getFeatureUses(), false, pc.getLevel());
        int cleared = clearTimedEffects(pc.getActiveEffects());
        if (cleared > 0) log.add(cleared + " effect" + (cleared == 1 ? "" : "s") + " wear off");
        return log;
    }

    /** {@link #shortRest} plus a partial refuel of an energy caster's charges (half the missing ones). */
    public static List<String> shortRest(PlayerCharacter pc, CharacterClass cls, int hitPointsRecovered,
                                         java.util.function.Function<String, com.dnd.model.item.Item> items) {
        List<String> log = shortRest(pc, cls, hitPointsRecovered);
        if (pc.getCastingResource() == CastingResource.ENERGY_CHARGES) {
            log.addAll(com.dnd.model.crafting.Workshop.shortRestRefill(pc, pc.getSpellSlots(), items));
        }
        return log;
    }

    /** Long rest: everything back to full and every effect and cooldown cleared. */
    public static List<String> longRest(PlayerCharacter pc) {
        List<String> log = new ArrayList<>();
        if (pc.getMaxHitPoints() > 0) pc.setCurrentHitPoints(pc.getMaxHitPoints());
        pc.setCurrentMana(pc.getMaxMana());
        boolean energy = pc.getCastingResource() == CastingResource.ENERGY_CHARGES;
        if (!energy) SpellSlots.restoreAll(pc.getSpellSlots());
        int effects = pc.getActiveEffects() == null ? 0 : pc.getActiveEffects().size();
        pc.getActiveEffects().clear();
        pc.getCooldowns().clear();
        pc.getFeatureUses().clear();
        log.add("fully rested (HP, mana and " + (energy ? "features" : "spell slots") + " restored"
            + (effects > 0 ? ", " + effects + " effect" + (effects == 1 ? "" : "s") + " cleared" : "") + ")");
        return log;
    }

    /**
     * Long rest that also lets an energy caster's workshop furnace burn the fuel in the pack
     * and refill the charges (rests alone never do).
     */
    public static List<String> longRest(PlayerCharacter pc,
                                        java.util.function.Function<String, com.dnd.model.item.Item> items) {
        List<String> log = longRest(pc);
        if (pc.getCastingResource() == CastingResource.ENERGY_CHARGES) {
            log.addAll(com.dnd.model.crafting.Workshop.restRefill(pc, pc.getSpellSlots(), items));
        }
        return log;
    }

    /** Battle-map counterpart of {@link #shortRest} for a token's combat state. */
    public static void shortRest(CombatState state, boolean pactCaster, int hitPointsRecovered) {
        shortRest(state, pactCaster, hitPointsRecovered, 1);
    }

    /** Short rest for a token whose class features recharge according to {@code level}. */
    public static void shortRest(CombatState state, boolean pactCaster, int hitPointsRecovered, int level) {
        com.dnd.model.rules.FeatureRules.recover(state.getFeatureUses(), false, level);
        if (hitPointsRecovered > 0) state.heal(hitPointsRecovered);
        if (pactCaster) SpellSlots.restoreAll(state.getSpellSlots());
        int missing = state.getMaxMana() - state.getCurrentMana();
        if (missing > 0) state.setCurrentMana(state.getCurrentMana() + (missing + 1) / 2);
        clearTimedEffects(state.getActiveEffects());
    }

    /** Battle-map counterpart of {@link #longRest} for a token's combat state. */
    public static void longRest(CombatState state) {
        if (!state.isDead()) {
            state.revive();
            state.setCurrentMana(state.getMaxMana());
        }
        if (state.effectiveCastingResource() != CastingResource.ENERGY_CHARGES) SpellSlots.restoreAll(state.getSpellSlots());
        state.getActiveEffects().clear();
        state.getCooldowns().clear();
        state.getFeatureUses().clear();
    }

    private static int clearTimedEffects(List<ActiveEffect> effects) {
        if (effects == null) return 0;
        int cleared = 0;
        Iterator<ActiveEffect> it = effects.iterator();
        while (it.hasNext()) {
            ActiveEffect effect = it.next();
            if (effect != null && effect.getRemainingRounds() > 0) {
                it.remove();
                cleared++;
            }
        }
        return cleared;
    }
}
