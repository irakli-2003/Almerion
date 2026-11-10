package com.dnd.model.combat;

/** Defence rules stored on a spell or ability, with rolls supplied by the GM at cast time. */
public class CastChecks {
    public enum SavedDamage { NONE, HALF }

    private boolean attackRoll;
    private String savingThrow = "none";
    private SavedDamage successfulSaveDamage = SavedDamage.HALF;
    private Integer attackBonus;
    private Integer saveDc;

    public boolean isAttackRoll() { return attackRoll; }
    public void setAttackRoll(boolean value) { attackRoll = value; }
    public String getSavingThrow() { return savingThrow; }
    public void setSavingThrow(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Choose a saving throw or none.");
        savingThrow = value;
    }
    public SavedDamage getSuccessfulSaveDamage() { return successfulSaveDamage; }
    public void setSuccessfulSaveDamage(SavedDamage value) { successfulSaveDamage = java.util.Objects.requireNonNull(value); }
    public Integer getAttackBonus() { return attackBonus; }
    public void setAttackBonus(Integer value) { attackBonus = value; }
    public Integer getSaveDc() { return saveDc; }
    public void setSaveDc(Integer value) {
        if (value != null && value < 0) throw new IllegalArgumentException("Save DC must be non-negative.");
        saveDc = value;
    }

    public boolean needsRolls() { return attackRoll || !"none".equals(savingThrow); }

    public record Rolls(int attackDie, int attackBonus, int armorClass, int saveDie, int saveBonus, int saveDc) {
        public boolean hit(CastChecks rules) {
            if (!rules.attackRoll) return true;
            requireD20(attackDie);
            return attackDie != 1 && (attackDie == 20 || attackDie + attackBonus >= armorClass);
        }
        public boolean saved(CastChecks rules) {
            if ("none".equals(rules.savingThrow)) return false;
            requireD20(saveDie);
            return saveDie + saveBonus >= saveDc;
        }
        private static void requireD20(int die) {
            if (die < 1 || die > 20) throw new IllegalArgumentException("Enter a d20 result from 1 to 20.");
        }
    }
}
