package com.dnd.model.combat;

/**
 * One entry in a {@link Damage}'s dice pool: a count of a particular catalogue
 * {@link com.dnd.model.world.Dice} (e.g. "2" of "d6" for 2d6 fire damage). A cast's total
 * damage is however many of these the DM actually rolls on the table and types in - see
 * {@link CastResolver} - rather than a number the app invents for them.
 */
public class DiceRoll {
    private String diceId;
    private int count = 1;

    public DiceRoll() {
    }

    public DiceRoll(String diceId, int count) {
        this.diceId = diceId;
        this.count = Math.max(1, count);
    }

    public String getDiceId() {
        return diceId;
    }

    public void setDiceId(String diceId) {
        this.diceId = diceId;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = Math.max(1, count);
    }

    /**
     * Readable "dice + constant" text, e.g. "2d6 + 3", "1d4 - 1" or just "5"; empty when there
     * are no dice and the constant is 0. Die labels resolve through {@code diceLookup} when given.
     */
    public static String formula(java.util.List<DiceRoll> dice, int constant,
                                 java.util.function.Function<String, com.dnd.model.world.Dice> diceLookup) {
        StringBuilder sb = new StringBuilder();
        if (dice != null) {
            for (DiceRoll roll : dice) {
                if (roll == null) continue;
                com.dnd.model.world.Dice die = diceLookup == null || roll.getDiceId() == null
                    ? null : diceLookup.apply(roll.getDiceId());
                String label = die != null ? die.toString() : roll.getDiceId();
                if (sb.length() > 0) sb.append(" + ");
                sb.append(roll.getCount()).append(label == null ? "" : label);
            }
        }
        if (constant != 0) {
            if (sb.length() == 0) sb.append(constant);
            else sb.append(constant > 0 ? " + " : " - ").append(Math.abs(constant));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return count + (diceId == null ? "" : diceId);
    }
}
