package com.dnd.model.character.stats;

import java.util.List;

/**
 * A character's skill bonuses (Stealth, Animal Handling, Sleight of Hand...). Each value is a
 * plain whole number - positive, negative or zero - set by the GM; it is not recalculated when
 * ability scores change.
 */
@com.fasterxml.jackson.annotation.JsonAutoDetect(
    fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY,
    getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE,
    setterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public class Skills {
    private java.util.Map<String, Integer> custom = new java.util.LinkedHashMap<>();
    @com.fasterxml.jackson.annotation.JsonIgnore
    private StatSchema schema = new StatSchema();
    public StatSchema schema() { return schema; }
    public void configure(StatSchema value) { schema = java.util.Objects.requireNonNull(value); }
    /** One skill: its property name, display label and the ability it is normally based on. */
    public record Def(String key, String label, String ability) {
    }

    public static final List<Def> ALL = List.of(
            new Def("acrobatics", "Acrobatics", "dexterity"),
            new Def("animalHandling", "Animal Handling", "wisdom"),
            new Def("arcana", "Arcana", "intelligence"),
            new Def("athletics", "Athletics", "strength"),
            new Def("deception", "Deception", "charisma"),
            new Def("history", "History", "intelligence"),
            new Def("insight", "Insight", "wisdom"),
            new Def("intimidation", "Intimidation", "charisma"),
            new Def("investigation", "Investigation", "intelligence"),
            new Def("medicine", "Medicine", "wisdom"),
            new Def("nature", "Nature", "intelligence"),
            new Def("perception", "Perception", "wisdom"),
            new Def("performance", "Performance", "charisma"),
            new Def("persuasion", "Persuasion", "charisma"),
            new Def("religion", "Religion", "intelligence"),
            new Def("sleightOfHand", "Sleight of Hand", "dexterity"),
            new Def("stealth", "Stealth", "dexterity"),
            new Def("survival", "Survival", "wisdom"));

    /** Starting values for a character without stored skills: each skill's ability modifier. */
    public static Skills fromAbilities(CoreStats stats) {
        Skills skills = new Skills();
        if (stats == null) return skills;
        skills.configure(stats.schema());
        for (var def : stats.schema().getSkills()) {
            skills.set(def.id(), Math.floorDiv(stats.score(def.ability()) - 10, 2));
        }
        return skills;
    }

    public int get(String key) {
        return switch (key) {
            case "acrobatics" -> acrobatics;
            case "animalHandling" -> animalHandling;
            case "arcana" -> arcana;
            case "athletics" -> athletics;
            case "deception" -> deception;
            case "history" -> history;
            case "insight" -> insight;
            case "intimidation" -> intimidation;
            case "investigation" -> investigation;
            case "medicine" -> medicine;
            case "nature" -> nature;
            case "perception" -> perception;
            case "performance" -> performance;
            case "persuasion" -> persuasion;
            case "religion" -> religion;
            case "sleightOfHand" -> sleightOfHand;
            case "stealth" -> stealth;
            case "survival" -> survival;
            default -> custom.getOrDefault(key, 0);
        };
    }

    public void set(String key, int value) {
        switch (key) {
            case "acrobatics" -> acrobatics = value;
            case "animalHandling" -> animalHandling = value;
            case "arcana" -> arcana = value;
            case "athletics" -> athletics = value;
            case "deception" -> deception = value;
            case "history" -> history = value;
            case "insight" -> insight = value;
            case "intimidation" -> intimidation = value;
            case "investigation" -> investigation = value;
            case "medicine" -> medicine = value;
            case "nature" -> nature = value;
            case "perception" -> perception = value;
            case "performance" -> performance = value;
            case "persuasion" -> persuasion = value;
            case "religion" -> religion = value;
            case "sleightOfHand" -> sleightOfHand = value;
            case "stealth" -> stealth = value;
            case "survival" -> survival = value;
            default -> custom.put(key, value);
        }
    }

    private int acrobatics;
    private int animalHandling;
    private int arcana;
    private int athletics;
    private int deception;
    private int history;
    private int insight;
    private int intimidation;
    private int investigation;
    private int medicine;
    private int nature;
    private int perception;
    private int performance;
    private int persuasion;
    private int religion;
    private int sleightOfHand;
    private int stealth;
    private int survival;

    public Skills() {
    }

    public Skills(int acrobatics, int animalHandling, int arcana, int athletics, int deception, int history, int insight,
                  int intimidation, int investigation, int medicine, int nature, int perception, int performance,
                  int persuasion, int religion, int sleightOfHand, int stealth, int survival) {
        this.acrobatics = acrobatics;
        this.animalHandling = animalHandling;
        this.arcana = arcana;
        this.athletics = athletics;
        this.deception = deception;
        this.history = history;
        this.insight = insight;
        this.intimidation = intimidation;
        this.investigation = investigation;
        this.medicine = medicine;
        this.nature = nature;
        this.perception = perception;
        this.performance = performance;
        this.persuasion = persuasion;
        this.religion = religion;
        this.sleightOfHand = sleightOfHand;
        this.stealth = stealth;
        this.survival = survival;
    }

    public int getAcrobatics() {
        return acrobatics;
    }

    public void setAcrobatics(int acrobatics) {
        this.acrobatics = acrobatics;
    }

    public int getAnimalHandling() {
        return animalHandling;
    }

    public void setAnimalHandling(int animalHandling) {
        this.animalHandling = animalHandling;
    }

    public int getArcana() {
        return arcana;
    }

    public void setArcana(int arcana) {
        this.arcana = arcana;
    }

    public int getAthletics() {
        return athletics;
    }

    public void setAthletics(int athletics) {
        this.athletics = athletics;
    }

    public int getDeception() {
        return deception;
    }

    public void setDeception(int deception) {
        this.deception = deception;
    }

    public int getHistory() {
        return history;
    }

    public void setHistory(int history) {
        this.history = history;
    }

    public int getInsight() {
        return insight;
    }

    public void setInsight(int insight) {
        this.insight = insight;
    }

    public int getIntimidation() {
        return intimidation;
    }

    public void setIntimidation(int intimidation) {
        this.intimidation = intimidation;
    }

    public int getInvestigation() {
        return investigation;
    }

    public void setInvestigation(int investigation) {
        this.investigation = investigation;
    }

    public int getMedicine() {
        return medicine;
    }

    public void setMedicine(int medicine) {
        this.medicine = medicine;
    }

    public int getNature() {
        return nature;
    }

    public void setNature(int nature) {
        this.nature = nature;
    }

    public int getPerception() {
        return get(schema.skillFor("perception"));
    }

    public void setPerception(int perception) {
        this.perception = perception;
    }

    public int getPerformance() {
        return performance;
    }

    public void setPerformance(int performance) {
        this.performance = performance;
    }

    public int getPersuasion() {
        return persuasion;
    }

    public void setPersuasion(int persuasion) {
        this.persuasion = persuasion;
    }

    public int getReligion() {
        return religion;
    }

    public void setReligion(int religion) {
        this.religion = religion;
    }

    public int getSleightOfHand() {
        return sleightOfHand;
    }

    public void setSleightOfHand(int sleightOfHand) {
        this.sleightOfHand = sleightOfHand;
    }

    public int getStealth() {
        return stealth;
    }

    public void setStealth(int stealth) {
        this.stealth = stealth;
    }

    public int getSurvival() {
        return survival;
    }

    public void setSurvival(int survival) {
        this.survival = survival;
    }
}
