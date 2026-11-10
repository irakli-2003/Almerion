package com.dnd.model.character;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything on the printed character sheet that the rest of the character doesn't already
 * hold: the GM's overrides for numbers the app normally works out (Armor Class, initiative,
 * speed...), table-side bookkeeping (inspiration, temporary hit points, death saves, coins)
 * and the free-text boxes.
 *
 * <p>An override left {@code null} (a blank field in the character panel) means "calculate it
 * automatically", see {@link com.dnd.model.rules.SheetValues}.</p>
 */
@JsonPropertyOrder({
    "armorClass", "initiative", "speed", "proficiencyBonus", "passivePerception",
    "savingThrowProficiencies", "hitDice", "temporaryHitPoints", "deathSaveSuccesses", "deathSaveFailures",
    "inspiration", "spellcastingAbility", "spellSaveDc", "spellAttackBonus",
    "copper", "silver", "electrum", "gold", "platinum",
    "extraAttacks", "proficienciesAndLanguages", "extraFeatures", "notes"
})
public class SheetDetails {

    private Integer armorClass;
    private Integer initiative;
    private Integer speed;
    private Integer proficiencyBonus;
    private Integer passivePerception;
    private List<String> savingThrowProficiencies = new ArrayList<>();
    private String hitDice;
    private int temporaryHitPoints;
    private int deathSaveSuccesses;
    private int deathSaveFailures;
    private boolean inspiration;
    private String spellcastingAbility;
    private Integer spellSaveDc;
    private Integer spellAttackBonus;
    private int copper;
    private int silver;
    private int electrum;
    /** Shared with the battle map's purse; null until either side sets it. */
    private Integer gold;
    private int platinum;
    private List<Attack> extraAttacks = new ArrayList<>();
    private String proficienciesAndLanguages;
    private String extraFeatures;
    private String notes;

    public Integer getArmorClass() { return armorClass; }
    public void setArmorClass(Integer armorClass) { this.armorClass = armorClass; }

    public Integer getInitiative() { return initiative; }
    public void setInitiative(Integer initiative) { this.initiative = initiative; }

    public Integer getSpeed() { return speed; }
    public void setSpeed(Integer speed) { this.speed = speed == null ? null : Math.max(0, speed); }

    public Integer getProficiencyBonus() { return proficiencyBonus; }
    public void setProficiencyBonus(Integer proficiencyBonus) { this.proficiencyBonus = proficiencyBonus; }

    public Integer getPassivePerception() { return passivePerception; }
    public void setPassivePerception(Integer passivePerception) { this.passivePerception = passivePerception; }

    public List<String> getSavingThrowProficiencies() {
        if (savingThrowProficiencies == null) savingThrowProficiencies = new ArrayList<>();
        return savingThrowProficiencies;
    }
    public void setSavingThrowProficiencies(List<String> savingThrowProficiencies) {
        this.savingThrowProficiencies = savingThrowProficiencies != null ? savingThrowProficiencies : new ArrayList<>();
    }

    public String getHitDice() { return hitDice; }
    public void setHitDice(String hitDice) { this.hitDice = hitDice; }

    public int getTemporaryHitPoints() { return temporaryHitPoints; }
    public void setTemporaryHitPoints(int temporaryHitPoints) { this.temporaryHitPoints = Math.max(0, temporaryHitPoints); }

    public int getDeathSaveSuccesses() { return deathSaveSuccesses; }
    public void setDeathSaveSuccesses(int deathSaveSuccesses) { this.deathSaveSuccesses = clampSaves(deathSaveSuccesses); }

    public int getDeathSaveFailures() { return deathSaveFailures; }
    public void setDeathSaveFailures(int deathSaveFailures) { this.deathSaveFailures = clampSaves(deathSaveFailures); }

    public boolean isInspiration() { return inspiration; }
    public void setInspiration(boolean inspiration) { this.inspiration = inspiration; }

    public String getSpellcastingAbility() { return spellcastingAbility; }
    public void setSpellcastingAbility(String spellcastingAbility) { this.spellcastingAbility = spellcastingAbility; }

    public Integer getSpellSaveDc() { return spellSaveDc; }
    public void setSpellSaveDc(Integer spellSaveDc) { this.spellSaveDc = spellSaveDc; }

    public Integer getSpellAttackBonus() { return spellAttackBonus; }
    public void setSpellAttackBonus(Integer spellAttackBonus) { this.spellAttackBonus = spellAttackBonus; }

    public int getCopper() { return copper; }
    public void setCopper(int copper) { this.copper = Math.max(0, copper); }

    public int getSilver() { return silver; }
    public void setSilver(int silver) { this.silver = Math.max(0, silver); }

    public int getElectrum() { return electrum; }
    public void setElectrum(int electrum) { this.electrum = Math.max(0, electrum); }

    public Integer getGold() { return gold; }
    public void setGold(Integer gold) { this.gold = gold == null ? null : Math.max(0, gold); }

    public int getPlatinum() { return platinum; }
    public void setPlatinum(int platinum) { this.platinum = Math.max(0, platinum); }

    public List<Attack> getExtraAttacks() {
        if (extraAttacks == null) extraAttacks = new ArrayList<>();
        return extraAttacks;
    }
    public void setExtraAttacks(List<Attack> extraAttacks) {
        this.extraAttacks = extraAttacks != null ? extraAttacks : new ArrayList<>();
    }

    public String getProficienciesAndLanguages() { return proficienciesAndLanguages; }
    public void setProficienciesAndLanguages(String proficienciesAndLanguages) { this.proficienciesAndLanguages = proficienciesAndLanguages; }

    public String getExtraFeatures() { return extraFeatures; }
    public void setExtraFeatures(String extraFeatures) { this.extraFeatures = extraFeatures; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    private static int clampSaves(int value) {
        return Math.max(0, Math.min(3, value));
    }

    /** A hand-written row for the sheet's "Attacks & spellcasting" box. */
    @JsonPropertyOrder({"name", "attackBonus", "damage"})
    public static class Attack {
        private String name;
        private String attackBonus;
        private String damage;

        public Attack() {
        }

        public Attack(String name, String attackBonus, String damage) {
            this.name = name;
            this.attackBonus = attackBonus;
            this.damage = damage;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getAttackBonus() { return attackBonus; }
        public void setAttackBonus(String attackBonus) { this.attackBonus = attackBonus; }

        public String getDamage() { return damage; }
        public void setDamage(String damage) { this.damage = damage; }
    }
}
