package com.dnd.model.character.stats;

/**
 * The six core ability scores. Values are constrained to the standard 5th-edition
 * range (1-30) at the point of mutation, so invalid stats can never enter the
 * system regardless of which caller (CLI, future API, tests) creates them.
 */
public class CoreStats {
    public static final int MIN_SCORE = 1;
    public static final int MAX_SCORE = 30;

    private int strength;
    private int dexterity;
    private int constitution;
    private int intelligence;
    private int wisdom;
    private int charisma;

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

    public int getStrength() {
        return strength;
    }

    public void setStrength(int strength) {
        this.strength = requireValidScore(strength);
    }

    public int getDexterity() {
        return dexterity;
    }

    public void setDexterity(int dexterity) {
        this.dexterity = requireValidScore(dexterity);
    }

    public int getConstitution() {
        return constitution;
    }

    public void setConstitution(int constitution) {
        this.constitution = requireValidScore(constitution);
    }

    public int getIntelligence() {
        return intelligence;
    }

    public void setIntelligence(int intelligence) {
        this.intelligence = requireValidScore(intelligence);
    }

    public int getWisdom() {
        return wisdom;
    }

    public void setWisdom(int wisdom) {
        this.wisdom = requireValidScore(wisdom);
    }

    public int getCharisma() {
        return charisma;
    }

    public void setCharisma(int charisma) {
        this.charisma = requireValidScore(charisma);
    }

    private static int requireValidScore(int value) {
        if (value < MIN_SCORE || value > MAX_SCORE) {
            throw new IllegalArgumentException("Ability score must be between " + MIN_SCORE + " and " + MAX_SCORE + " but was " + value);
        }
        return value;
    }
}


