package com.dnd.model.combat;

import com.dnd.model.interfaces.Printable;
import java.util.List;

public class Ability implements Printable {
    private CastChecks checks = new CastChecks();
    public CastChecks getChecks() { return checks; }
    public void setChecks(CastChecks value) { checks = value == null ? new CastChecks() : value; }
    private String id;
    private String name;
    private String description;
    private List<String> effects;
    private double range;
    private int recharge;

    public Ability() {
    }

    public Ability(String id, String name, String description, List<String> effects, double range, int recharge) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.effects = effects;
        this.range = range;
        this.recharge = recharge;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getEffects() {
        return effects;
    }

    public void setEffects(List<String> effects) {
        this.effects = effects;
    }

    public double getRange() {
        return range;
    }

    public void setRange(double range) {
        this.range = range;
    }

    public int getRecharge() {
        return recharge;
    }

    public void setRecharge(int recharge) {
        this.recharge = recharge;
    }

    /**
     * Blast radius in feet. Zero - the default, and what every ability written before this
     * existed has - means the ability strikes a single creature rather than an area.
     */
    private double radius;

    public double getRadius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = Math.max(0, radius);
    }

    /** Hit points the user pays to activate this (blood magic, summons, rune strain). */
    private int hpCost;
    /** Mana the user pays, for homebrew mana-based characters. */
    private int manaCost;
    /** Dice rolled when this hits; empty means it deals no direct damage. */
    private com.dnd.model.combat.Damage damage;

    public int getHpCost() {
        return hpCost;
    }

    public void setHpCost(int hpCost) {
        this.hpCost = Math.max(0, hpCost);
    }

    public int getManaCost() {
        return manaCost;
    }

    public void setManaCost(int manaCost) {
        this.manaCost = Math.max(0, manaCost);
    }

    public com.dnd.model.combat.Damage getDamage() {
        return damage;
    }

    public void setDamage(com.dnd.model.combat.Damage damage) {
        this.damage = damage;
    }

    @Override
    public String toString() {
        return name != null ? name : id;
    }
}
