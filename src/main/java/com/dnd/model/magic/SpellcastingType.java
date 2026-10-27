package com.dnd.model.magic;

/** How quickly a class gains spell slots, following the standard 5e progression tables. */
public enum SpellcastingType {
    /** No spellcasting (barbarian, fighter, monk, rogue). */
    NONE("None"),
    /** Bard, cleric, druid, sorcerer, wizard. */
    FULL("Full caster"),
    /** Paladin, ranger. */
    HALF("Half caster"),
    /** Spellblade / Spellthief subclasses. */
    THIRD("Third caster"),
    /** Warlock: few slots, all of the same level, back on a short rest. */
    PACT("Pact magic"),
    /** Homebrew artificer: half-caster progression, paid with energy charges instead of slots. */
    ENERGY("Energy charges (half caster)");

    private final String label;

    SpellcastingType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
