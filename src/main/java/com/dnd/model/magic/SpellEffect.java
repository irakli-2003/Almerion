package com.dnd.model.magic;

import com.dnd.model.combat.DiceRoll;
import com.dnd.model.combat.Effect;
import com.dnd.model.interfaces.Printable;
import com.dnd.model.world.Dice;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One effect a spell applies: a link to an entry of the campaign's effect catalogue plus the
 * power the spell gives it - a constant and a list of dice (e.g. "1d4 + 2" burning).
 *
 * <p>A power of 0 with no dice means "use the effect's own damage/healing amount". Otherwise the
 * power replaces that amount: the DM rolls the dice at the table, and the constant is added.</p>
 *
 * <p>Spells written before effects were picked from the catalogue embedded the whole effect
 * ({@code {"id": "burn", "name": "Burn", "damageAmount": 3, ...}}). That shape still loads: the
 * id becomes the link and the rest is kept as {@link #definition()}, used whenever the catalogue
 * has no effect with that id, so an old spell keeps working exactly as before.</p>
 */
@JsonPropertyOrder({"effectId", "constant", "dice"})
public class SpellEffect implements Printable {
    private String effectId;
    private int constant;
    private List<DiceRoll> dice = new ArrayList<>();

    @JsonProperty("definition")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Effect definition;

    public SpellEffect() {
    }

    public SpellEffect(String effectId, int constant, List<DiceRoll> dice) {
        this.effectId = effectId;
        this.constant = constant;
        setDice(dice);
    }

    /** Wraps a whole effect (the pre-catalogue shape) so it keeps its own amounts. */
    public static SpellEffect of(Effect effect) {
        SpellEffect spellEffect = new SpellEffect();
        if (effect != null) {
            spellEffect.effectId = effect.getId();
            spellEffect.definition = effect;
        }
        return spellEffect;
    }

    public String getEffectId() {
        return effectId;
    }

    public void setEffectId(String effectId) {
        if (definition != null && !Objects.equals(definition.getId(), effectId)) {
            // Picked a different effect: the embedded copy of the old one no longer applies.
            definition = null;
        }
        this.effectId = effectId;
    }

    public int getConstant() {
        return constant;
    }

    public void setConstant(int constant) {
        this.constant = constant;
    }

    public List<DiceRoll> getDice() {
        return dice;
    }

    public void setDice(List<DiceRoll> dice) {
        this.dice = dice == null ? new ArrayList<>() : new ArrayList<>(dice);
    }

    /** The embedded effect from an older spell file, or null for a plain catalogue link. */
    public Effect definition() {
        return definition;
    }

    /** True when the spell sets its own power instead of using the effect's amount. */
    public boolean hasPower() {
        return constant != 0 || !dice.isEmpty();
    }

    /** True when casting needs a dice roll from the DM for this effect's power. */
    public boolean hasPowerDice() {
        return !dice.isEmpty();
    }

    /** The power for a given dice total (constant added), never negative. */
    public int power(int rolledDice) {
        return Math.max(0, (dice.isEmpty() ? 0 : rolledDice) + constant);
    }

    /** e.g. "1d4 + 2"; empty when the spell does not set a power. */
    public String powerFormula(Function<String, Dice> diceLookup) {
        return DiceRoll.formula(dice, constant, diceLookup);
    }

    /**
     * A fresh copy of the effect this links to: the campaign's current version when
     * {@code catalogue} knows the id, else the embedded one from an older file, else a bare
     * effect named after the id. The power is not applied here - it may need a dice roll.
     */
    public Effect resolve(Function<String, Effect> catalogue) {
        Effect base = effectId == null || catalogue == null ? null : catalogue.apply(effectId);
        if (base == null) base = definition;
        Effect copy = new Effect();
        if (base == null) {
            copy.setId(effectId);
            copy.setName(effectId);
            return copy;
        }
        copy.setId(base.getId() != null ? base.getId() : effectId);
        copy.setName(base.getName());
        copy.setDescription(base.getDescription());
        copy.setDamaging(base.isDamaging());
        copy.setHealing(base.isHealing());
        copy.setDamageAmount(base.getDamageAmount());
        copy.setHealingAmount(base.getHealingAmount());
        copy.setDurationRounds(base.getDurationRounds());
        return copy;
    }

    /** e.g. "Burning (1d4 + 2)", naming the effect through {@code catalogue} when possible. */
    public String describe(Function<String, Effect> catalogue, Function<String, Dice> diceLookup) {
        Effect effect = resolve(catalogue);
        String name = effect.getName() != null && !effect.getName().isBlank() ? effect.getName() : effect.getId();
        String power = powerFormula(diceLookup);
        return (name == null ? "" : name) + (power.isEmpty() ? "" : " (" + power + ")");
    }

    private Effect legacyDefinition() {
        if (definition == null) definition = new Effect();
        return definition;
    }

    // Pre-catalogue spell files embedded the whole effect; these read that shape.

    @JsonProperty("id")
    private void legacyId(String id) {
        effectId = id;
        legacyDefinition().setId(id);
    }

    @JsonProperty("name")
    private void legacyName(String name) {
        legacyDefinition().setName(name);
    }

    @JsonProperty("description")
    private void legacyDescription(String description) {
        legacyDefinition().setDescription(description);
    }

    @JsonProperty("damaging")
    private void legacyDamaging(boolean damaging) {
        legacyDefinition().setDamaging(damaging);
    }

    @JsonProperty("healing")
    private void legacyHealing(boolean healing) {
        legacyDefinition().setHealing(healing);
    }

    @JsonProperty("damageAmount")
    private void legacyDamageAmount(int amount) {
        legacyDefinition().setDamageAmount(amount);
    }

    @JsonProperty("healingAmount")
    private void legacyHealingAmount(int amount) {
        legacyDefinition().setHealingAmount(amount);
    }

    @JsonProperty("durationRounds")
    private void legacyDurationRounds(int rounds) {
        legacyDefinition().setDurationRounds(rounds);
    }

    @Override
    public String toString() {
        return describe(null, null);
    }
}
