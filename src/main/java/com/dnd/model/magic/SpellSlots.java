package com.dnd.model.magic;

import java.util.ArrayList;
import java.util.List;

/**
 * The standard 5e spell slot tables, plus the bookkeeping for spending and regaining slots.
 *
 * <p>Half and third casters use the full-caster table at a reduced "caster level"
 * (rounded up, starting at class level 2 and 3 respectively), which reproduces the
 * official paladin/ranger and Eldritch Knight/Arcane Trickster tables exactly.</p>
 */
public final class SpellSlots {

    /** Full-caster slots per character level (index 0 = level 1), per slot level 1-9. */
    private static final int[][] FULL = {
        {2},
        {3},
        {4, 2},
        {4, 3},
        {4, 3, 2},
        {4, 3, 3},
        {4, 3, 3, 1},
        {4, 3, 3, 2},
        {4, 3, 3, 3, 1},
        {4, 3, 3, 3, 2},
        {4, 3, 3, 3, 2, 1},
        {4, 3, 3, 3, 2, 1},
        {4, 3, 3, 3, 2, 1, 1},
        {4, 3, 3, 3, 2, 1, 1},
        {4, 3, 3, 3, 2, 1, 1, 1},
        {4, 3, 3, 3, 2, 1, 1, 1},
        {4, 3, 3, 3, 2, 1, 1, 1, 1},
        {4, 3, 3, 3, 3, 1, 1, 1, 1},
        {4, 3, 3, 3, 3, 2, 1, 1, 1},
        {4, 3, 3, 3, 3, 2, 2, 1, 1},
    };

    private SpellSlots() {
    }

    /** Maximum slots for a character of this class type and level; index 0 is slot level 1. */
    public static int[] maxSlots(SpellcastingType type, int level) {
        int[] out = new int[9];
        if (type == null || level < 1) return out;
        int lvl = Math.min(20, level);
        switch (type) {
            case FULL -> copyRow(FULL[lvl - 1], out);
            case HALF, ENERGY -> {
                if (lvl >= 2) copyRow(FULL[(lvl + 1) / 2 - 1], out);
            }
            case THIRD -> {
                if (lvl >= 3) copyRow(FULL[(lvl + 2) / 3 - 1], out);
            }
            case PACT -> out[pactSlotLevel(lvl) - 1] = pactSlotCount(lvl);
            default -> { }
        }
        return out;
    }

    /** Warlock slot count: 1 at level 1, 2 at 2-10, 3 at 11-16, 4 at 17+. */
    public static int pactSlotCount(int level) {
        if (level <= 1) return 1;
        if (level <= 10) return 2;
        if (level <= 16) return 3;
        return 4;
    }

    /** Warlock slot level: rises every two levels up to 5th. */
    public static int pactSlotLevel(int level) {
        return Math.max(1, Math.min(5, (level + 1) / 2));
    }

    private static void copyRow(int[] row, int[] out) {
        System.arraycopy(row, 0, out, 0, row.length);
    }

    /** Builds a full (all slots unspent) slot list from a max-slots array, skipping empty levels. */
    public static List<SpellSlot> fresh(int[] max) {
        List<SpellSlot> slots = new ArrayList<>();
        for (int i = 0; i < max.length; i++) {
            if (max[i] > 0) slots.add(new SpellSlot(i + 1, max[i], max[i]));
        }
        return slots;
    }

    /**
     * Re-applies new maxima (e.g. after levelling up) while keeping already-spent slots spent:
     * each level gains exactly as many unspent slots as its maximum grew by.
     */
    public static List<SpellSlot> withMax(List<SpellSlot> existing, int[] max) {
        List<SpellSlot> out = new ArrayList<>();
        for (int i = 0; i < max.length; i++) {
            SpellSlot old = find(existing, i + 1);
            int oldMax = old == null ? 0 : old.getMax();
            int oldCurrent = old == null ? 0 : old.getCurrent();
            if (max[i] <= 0) continue;
            int current = Math.max(0, Math.min(max[i], oldCurrent + Math.max(0, max[i] - oldMax)));
            out.add(new SpellSlot(i + 1, max[i], current));
        }
        return out;
    }

    public static SpellSlot find(List<SpellSlot> slots, int level) {
        if (slots == null) return null;
        for (SpellSlot slot : slots) {
            if (slot != null && slot.getLevel() == level) return slot;
        }
        return null;
    }

    /** The lowest slot level, at or above {@code minLevel}, that still has an unspent slot; -1 if none. */
    public static int lowestAvailable(List<SpellSlot> slots, int minLevel) {
        int best = -1;
        if (slots == null) return best;
        for (SpellSlot slot : slots) {
            if (slot == null || slot.getLevel() < minLevel || slot.getCurrent() <= 0) continue;
            if (best < 0 || slot.getLevel() < best) best = slot.getLevel();
        }
        return best;
    }

    /** Spends one slot of exactly {@code level}. Returns false when none is left. */
    public static boolean spend(List<SpellSlot> slots, int level) {
        SpellSlot slot = find(slots, level);
        if (slot == null || slot.getCurrent() <= 0) return false;
        slot.setCurrent(slot.getCurrent() - 1);
        return true;
    }

    /** Refills every slot (long rest). */
    public static void restoreAll(List<SpellSlot> slots) {
        if (slots == null) return;
        for (SpellSlot slot : slots) {
            if (slot != null) slot.setCurrent(slot.getMax());
        }
    }

    public static List<SpellSlot> copy(List<SpellSlot> slots) {
        List<SpellSlot> out = new ArrayList<>();
        if (slots == null) return out;
        for (SpellSlot slot : slots) {
            if (slot != null) out.add(slot.copy());
        }
        return out;
    }

    /** "a 2nd-level slot", or "a Thermal charge" for energy casters. */
    public static String slotName(int level, CastingResource resource) {
        if (resource == CastingResource.ENERGY_CHARGES) return "a " + EnergyCharges.name(level) + " charge";
        return "a " + ordinal(level) + "-level slot";
    }

    /** Row label for slot editors: "2nd-level slots" or "Thermal charges (L2)". */
    public static String rowLabel(int level, CastingResource resource) {
        if (resource == CastingResource.ENERGY_CHARGES) return EnergyCharges.name(level) + " charges (L" + level + ")";
        return ordinal(level) + "-level slots";
    }

    /** {@link #describe(List)} using charge names when {@code resource} is energy charges. */
    public static String describe(List<SpellSlot> slots, CastingResource resource) {
        if (resource != CastingResource.ENERGY_CHARGES) return describe(slots);
        return EnergyCharges.describe(slots);
    }

    /** Compact "1st 3/4 · 2nd 1/3" style summary; empty string when there are no slots. */
    public static String describe(List<SpellSlot> slots) {
        if (slots == null || slots.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (SpellSlot slot : slots) {
            if (slot == null || slot.getMax() <= 0) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(ordinal(slot.getLevel())).append(' ').append(slot.getCurrent()).append('/').append(slot.getMax());
        }
        return sb.toString();
    }

    public static String ordinal(int n) {
        return switch (n) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> n + "th";
        };
    }
}
