package com.dnd.model.magic;

import java.util.List;

/**
 * Names and furnace costs for the artificer's energy charges.
 *
 * <p>Energy charges are spell slots under another name: a level-1 spell costs a level-1
 * charge (or any stronger one), and the counts follow the half-caster table. What differs is
 * the flavour - each level is a different kind of stored energy, from cheap steam pressure up
 * to rare resonance - and the way they come back: not on a rest, but by burning fuel in a
 * workshop furnace (see {@link com.dnd.model.crafting.Workshop}).</p>
 */
public final class EnergyCharges {

    /** Charge name per level, index 0 = level 1. */
    private static final String[] NAMES = {
        "Steam-pressure", "Thermal", "Electric", "Magnetic", "Resonance",
        "Aether", "Aether", "Aether", "Aether"
    };

    /** One-line explanation per level, shown in tooltips and the furnace panel. */
    private static final String[] NOTES = {
        "Compressed steam in a brass cylinder. Cheap and easy: any furnace makes it.",
        "Stored heat in a ceramic heat-cell. Needs a hot, steady fire.",
        "A crackling Leyden battery. Needs an upgraded furnace with a dynamo coil.",
        "A lodestone capacitor. Rare - needs an advanced, magnet-wound furnace.",
        "A humming resonance crystal. Very rare - only a master workshop core can tune one.",
        "Raw aether, the stuff of legends.", "Raw aether, the stuff of legends.",
        "Raw aether, the stuff of legends.", "Raw aether, the stuff of legends."
    };

    /** Fuel units the furnace burns to make one charge of each level. */
    private static final int[] FUEL_COST = {2, 4, 7, 10, 14, 20, 20, 20, 20};

    private EnergyCharges() {
    }

    public static String name(int level) {
        return level >= 1 && level <= NAMES.length ? NAMES[level - 1] : "Level " + level;
    }

    public static String note(int level) {
        return level >= 1 && level <= NOTES.length ? NOTES[level - 1] : "";
    }

    public static int fuelCost(int level) {
        return level >= 1 && level <= FUEL_COST.length ? FUEL_COST[level - 1] : Integer.MAX_VALUE;
    }

    /** "Steam-pressure 3/4 · Thermal 1/2"; empty when there are no charges. */
    public static String describe(List<SpellSlot> slots) {
        if (slots == null || slots.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (SpellSlot slot : slots) {
            if (slot == null || slot.getMax() <= 0) continue;
            if (sb.length() > 0) sb.append(" \u00b7 ");
            sb.append(name(slot.getLevel())).append(' ').append(slot.getCurrent()).append('/').append(slot.getMax());
        }
        return sb.toString();
    }
}
