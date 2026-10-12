package com.dnd.model.item.crafting;

import com.dnd.model.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * A crafted gadget. When it has a {@link #getSpellId() linked action} that action shows up in
 * the carrier's battle-map spell list; when the action has a level above 0 using it spends an
 * energy charge of that level (devices with no level run on clockwork and cost nothing).
 *
 * <p>A {@link #isMechanical() mechanical} device (pistons, springs, pressure valves) takes any
 * charge of its level or stronger, and a stronger charge makes it hit harder. Any other device
 * is tuned to one charge type and runs on nothing else.</p>
 */
public class Device extends Item {
    private String spellId;
    private int chargeLevel;
    private boolean mechanical = true;
    private List<String> effects = new ArrayList<>();

    public Device() {
        setType("device");
    }

    /** Spell/action the device performs when used in battle; may be blank for passive gear. */
    public String getSpellId() {
        return spellId;
    }

    public void setSpellId(String spellId) {
        this.spellId = spellId;
    }

    /** Energy charge level needed for one use; 0 means it needs no charge. */
    public int getChargeLevel() {
        return chargeLevel;
    }

    public void setChargeLevel(int chargeLevel) {
        this.chargeLevel = Math.max(0, Math.min(9, chargeLevel));
    }

    /** True when any charge at or above its level works (stronger = stronger effect). */
    public boolean isMechanical() {
        return mechanical;
    }

    public void setMechanical(boolean mechanical) {
        this.mechanical = mechanical;
    }

    /** Effect ids the device grants or applies (informational unless its action uses them). */
    public List<String> getEffects() {
        return effects;
    }

    public void setEffects(List<String> effects) {
        this.effects = effects == null ? new ArrayList<>() : effects;
    }
}
