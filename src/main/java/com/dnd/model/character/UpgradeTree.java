package com.dnd.model.character;

import java.util.ArrayList;
import java.util.List;

/** Class-owned choices. IDs are permanent; edges require every prerequisite. */
public class UpgradeTree {
    private List<Branch> branches = new ArrayList<>();
    private List<Node> nodes = new ArrayList<>();

    public List<Branch> getBranches() { return branches; }
    public void setBranches(List<Branch> value) { branches = value == null ? new ArrayList<>() : value; }
    public List<Node> getNodes() { return nodes; }
    public void setNodes(List<Node> value) { nodes = value == null ? new ArrayList<>() : value; }

    public static class Branch {
        private String id;
        private String name;
        public String getId() { return id; }
        public void setId(String value) { id = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        @Override public String toString() { return name == null ? "" : name; }
    }

    public static class Node {
        private String id;
        private String branchId;
        private String name;
        private String description;
        private String imagePath;
        private int minimumLevel = 2;
        private String parentId;
        private List<String> children = new ArrayList<>();
        private List<String> prerequisites = new ArrayList<>();
        private List<String> exclusiveWith = new ArrayList<>();
        private List<String> existingTraining = new ArrayList<>();
        private Reward reward = new Reward();

        public String getId() { return id; }
        public void setId(String value) { id = value; }
        public String getBranchId() { return branchId; }
        public void setBranchId(String value) { branchId = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public String getDescription() { return description; }
        public void setDescription(String value) { description = value; }
        public String getImagePath() { return imagePath; }
        public void setImagePath(String value) { imagePath = value; }
        public int getMinimumLevel() { return minimumLevel; }
        public void setMinimumLevel(int value) { minimumLevel = value; }
        public String getParentId() { return parentId; }
        public void setParentId(String value) { parentId = value; }
        public List<String> getChildren() { return children; }
        public void setChildren(List<String> value) { children = value == null ? new ArrayList<>() : new ArrayList<>(value); }
        public List<String> getPrerequisites() { return prerequisites; }
        public void setPrerequisites(List<String> value) { prerequisites = value == null ? new ArrayList<>() : value; }
        public List<String> getExclusiveWith() { return exclusiveWith; }
        public void setExclusiveWith(List<String> value) { exclusiveWith = value == null ? new ArrayList<>() : value; }
        public List<String> getExistingTraining() { return existingTraining; }
        public void setExistingTraining(List<String> value) { existingTraining = value == null ? new ArrayList<>() : value; }
        public Reward getReward() { return reward; }
        public void setReward(Reward value) { reward = value == null ? new Reward() : value; }
        @Override public String toString() { return name == null ? "" : name; }
    }

    public static class SpellAlternatives {
        private String spellId;
        private List<String> alternativeSpellIds = new ArrayList<>();
        public String getSpellId() { return spellId; }
        public void setSpellId(String value) { spellId = value; }
        public List<String> getAlternativeSpellIds() { return alternativeSpellIds; }
        public void setAlternativeSpellIds(List<String> value) {
            alternativeSpellIds = value == null ? new ArrayList<>() : new ArrayList<>(value);
        }
    }

    /** Numeric rewards are applied once; combat modifiers are read from the saved unlock. */
    public static class Reward {
        private int hitPoints;
        private int mana;
        private String abilityId;
        private int abilityIncrease;
        private int armorClass;
        private int attackBonus;
        private int basicAttackDamage;
        private int rageDamage;
        private int speed;
        private List<String> spellIds = new ArrayList<>();
        private List<SpellAlternatives> spellAlternatives = new ArrayList<>();
        private List<ClassFeature> features = new ArrayList<>();

        public List<SpellAlternatives> getSpellAlternatives() { return spellAlternatives; }
        public void setSpellAlternatives(List<SpellAlternatives> value) {
            spellAlternatives = value == null ? new ArrayList<>() : new ArrayList<>(value);
        }
        public List<ClassFeature> getFeatures() { return features; }
        public void setFeatures(List<ClassFeature> value) { features = value == null ? new ArrayList<>() : value; }

        public int getHitPoints() { return hitPoints; }
        public void setHitPoints(int value) { hitPoints = value; }
        public int getMana() { return mana; }
        public void setMana(int value) { mana = value; }
        public String getAbilityId() { return abilityId; }
        public void setAbilityId(String value) { abilityId = value; }
        public int getAbilityIncrease() { return abilityIncrease; }
        public void setAbilityIncrease(int value) { abilityIncrease = value; }
        public int getArmorClass() { return armorClass; }
        public void setArmorClass(int value) { armorClass = value; }
        public int getAttackBonus() { return attackBonus; }
        public void setAttackBonus(int value) { attackBonus = value; }
        public int getBasicAttackDamage() { return basicAttackDamage; }
        public void setBasicAttackDamage(int value) { basicAttackDamage = value; }
        public int getRageDamage() { return rageDamage; }
        public void setRageDamage(int value) { rageDamage = value; }
        public int getSpeed() { return speed; }
        public void setSpeed(int value) { speed = value; }
        public List<String> getSpellIds() { return spellIds; }
        public void setSpellIds(List<String> value) { spellIds = value == null ? new ArrayList<>() : value; }
    }
}
