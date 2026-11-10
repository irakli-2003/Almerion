package com.dnd.model.character.stats;

/**
 * Campaign ability scores with preserved legacy storage and explicitly mapped rules roles.
 * Mutations and JSON input use the standard score range of 1-30.
 */
@com.fasterxml.jackson.annotation.JsonAutoDetect(
    fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY,
    getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE,
    setterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public class CoreStats {
    public static final int MIN_SCORE = 1;
    public static final int MAX_SCORE = 30;

    private int strength;
    private int dexterity;
    private int constitution;
    private int intelligence;
    private int wisdom;
    private int charisma;
    private java.util.Map<String, Integer> custom = new java.util.LinkedHashMap<>();
    @com.fasterxml.jackson.annotation.JsonIgnore
    private StatSchema schema = new StatSchema();

    public StatSchema schema() { return schema; }
    public void configure(StatSchema value) { schema = java.util.Objects.requireNonNull(value); }
    public int score(String id) {
        return switch (id) {
            case "strength" -> strength;
            case "dexterity" -> dexterity;
            case "constitution" -> constitution;
            case "intelligence" -> intelligence;
            case "wisdom" -> wisdom;
            case "charisma" -> charisma;
            default -> custom.getOrDefault(id, 10);
        };
    }
    public void score(String id, int value) {
        requireValidScore(value);
        switch (id) {
            case "strength" -> strength = value;
            case "dexterity" -> dexterity = value;
            case "constitution" -> constitution = value;
            case "intelligence" -> intelligence = value;
            case "wisdom" -> wisdom = value;
            case "charisma" -> charisma = value;
            default -> custom.put(id, value);
        }
    }

    public CoreStats() {
    }

    public CoreStats(int strength, int dexterity, int constitution, int intelligence, int wisdom, int charisma) {
        setStrength(strength);
        setDexterity(dexterity);
        setConstitution(constitution);
        setIntelligence(intelligence);
        setWisdom(wisdom);
        setCharisma(charisma);
    }

    @com.fasterxml.jackson.annotation.JsonSetter("custom")
    private void readCustom(java.util.Map<String, Integer> values) {
        java.util.Objects.requireNonNull(values, "Custom scores cannot be null.");
        values.forEach((id, value) -> {
            if (id == null || value == null) throw new IllegalArgumentException("Custom scores need an ID and a value.");
            requireValidScore(value);
        });
        custom = new java.util.LinkedHashMap<>(values);
    }

    public int getStrength() {
        return score(schema.abilityFor("strength"));
    }

    @com.fasterxml.jackson.annotation.JsonSetter("strength")
    public void setStrength(int strength) {
        score(schema.abilityFor("strength"), strength);
    }

    public int getDexterity() {
        return score(schema.abilityFor("dexterity"));
    }

    @com.fasterxml.jackson.annotation.JsonSetter("dexterity")
    public void setDexterity(int dexterity) {
        score(schema.abilityFor("dexterity"), dexterity);
    }

    public int getConstitution() {
        return score(schema.abilityFor("constitution"));
    }

    @com.fasterxml.jackson.annotation.JsonSetter("constitution")
    public void setConstitution(int constitution) {
        score(schema.abilityFor("constitution"), constitution);
    }

    public int getIntelligence() {
        return score(schema.abilityFor("intelligence"));
    }

    @com.fasterxml.jackson.annotation.JsonSetter("intelligence")
    public void setIntelligence(int intelligence) {
        score(schema.abilityFor("intelligence"), intelligence);
    }

    public int getWisdom() {
        return score(schema.abilityFor("wisdom"));
    }

    @com.fasterxml.jackson.annotation.JsonSetter("wisdom")
    public void setWisdom(int wisdom) {
        score(schema.abilityFor("wisdom"), wisdom);
    }

    public int getCharisma() {
        return score(schema.abilityFor("charisma"));
    }

    @com.fasterxml.jackson.annotation.JsonSetter("charisma")
    public void setCharisma(int charisma) {
        score(schema.abilityFor("charisma"), charisma);
    }

    private static int requireValidScore(int value) {
        if (value < MIN_SCORE || value > MAX_SCORE) {
            throw new IllegalArgumentException("Ability score must be between " + MIN_SCORE + " and " + MAX_SCORE + " but was " + value);
        }
        return value;
    }
}
