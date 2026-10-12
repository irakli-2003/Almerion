package com.dnd.model.magic;

/**
 * What a creature pays to cast a spell.
 *
 * <p>Standard 5e casters spend {@link #SPELL_SLOTS}. Homebrew characters can instead run on
 * {@link #MANA}, or - like a blood-magic revenant - on their own {@link #HIT_POINTS}, in
 * which case a spell's mana cost is taken from their health instead.</p>
 */
public enum CastingResource {
    SPELL_SLOTS("Spell slots"),
    /**
     * Artificer homebrew: works exactly like spell slots (one charge per levelled spell, higher
     * levels can stand in for lower ones) but each level is a kind of stored energy - steam
     * pressure, heat, electricity... - and rests do not refill them: the workshop furnace does.
     */
    ENERGY_CHARGES("Energy charges"),
    MANA("Mana"),
    HIT_POINTS("Hit points"),
    NONE("None");

    private final String label;

    CastingResource(String label) {
        this.label = label;
    }

    /** True for the resources that are tracked per level with {@link SpellSlot}s. */
    public boolean usesSlots() {
        return this == SPELL_SLOTS || this == ENERGY_CHARGES;
    }

    @Override
    public String toString() {
        return label;
    }
}
