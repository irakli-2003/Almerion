package com.dnd.model.combat;

import com.dnd.model.interfaces.Printable;
import com.dnd.model.item.armors.Armor;
import com.dnd.model.item.armors.ArmorMaterial;
import com.dnd.model.world.Dice;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How much a spell hurts, described as the dice the DM should physically roll rather than a
 * flat number - e.g. "2d6 fire" instead of "7 fire". {@link CastResolver} asks the DM for the
 * actual rolled total when this is cast rather than simulating the roll itself, keeping the
 * dice-rolling part of the game at the table where the fun is.
 */
public class Damage implements Printable {
    private List<DiceRoll> dice = new ArrayList<>();
    private String typeId;
    private int constant;

    public Damage() {
    }

    public Damage(List<DiceRoll> dice, String typeId) {
        this.dice = dice == null ? new ArrayList<>() : new ArrayList<>(dice);
        this.typeId = typeId;
    }

    /** Convenience for a single die (e.g. one d6) - equivalent to a one-entry dice list. */
    public Damage(Dice singleDie, String typeId) {
        this.dice = singleDie == null ? new ArrayList<>() : new ArrayList<>(List.of(new DiceRoll(singleDie.getId(), 1)));
        this.typeId = typeId;
    }

    public DamageResolution resolveAgainst(Armor armor, int attackRoll, int rolledAmount) {
        ArmorMaterial material = armor == null ? ArmorMaterial.NONE : armor.getMaterial();
        String normalizedType = typeId == null ? "" : typeId.trim().toLowerCase(Locale.ROOT);

        double targetMultiplier = 1.0;
        double armorMultiplier = 0.0;
        List<String> triggeredEffects = new ArrayList<>();

        switch (normalizedType) {
            case "slashing":
                if (material == ArmorMaterial.LEATHER) {
                    targetMultiplier = 0.5;
                    armorMultiplier = 1.0;
                } else if (material == ArmorMaterial.CHAIN) {
                    targetMultiplier = 0.0;
                    armorMultiplier = 1.0 / 3.0;
                } else if (material == ArmorMaterial.STEEL) {
                    targetMultiplier = 0.0;
                    armorMultiplier = 0.0;
                }
                break;
            case "cutting":
                if (material == ArmorMaterial.LEATHER) {
                    targetMultiplier = 2.0 / 3.0;
                    armorMultiplier = 1.0;
                } else if (material == ArmorMaterial.CHAIN) {
                    targetMultiplier = 1.0 / 3.0;
                    armorMultiplier = 2.0 / 3.0;
                } else if (material == ArmorMaterial.STEEL) {
                    targetMultiplier = 0.0;
                    armorMultiplier = 1.0 / 3.0;
                }
                break;
            case "bludgeoning":
                if (material == ArmorMaterial.CHAIN) {
                    targetMultiplier = 2.0 / 3.0;
                    armorMultiplier = 2.0 / 3.0;
                } else if (material == ArmorMaterial.STEEL) {
                    targetMultiplier = 1.0 / 3.0;
                    armorMultiplier = 2.0 / 3.0;
                }
                break;
            case "piercing":
                if (material == ArmorMaterial.LEATHER) {
                    targetMultiplier = 3.0 / 4.0;
                    armorMultiplier = 1.0;
                } else if (material == ArmorMaterial.CHAIN) {
                    targetMultiplier = 0.5;
                    armorMultiplier = 2.0 / 3.0;
                } else if (material == ArmorMaterial.STEEL) {
                    targetMultiplier = 1.0 / 3.0;
                    armorMultiplier = 1.0 / 3.0;
                }
                break;
            case "fire":
                if (material == ArmorMaterial.LEATHER) {
                    targetMultiplier = 2.0 / 3.0;
                    armorMultiplier = 1.0;
                } else if (material == ArmorMaterial.CHAIN) {
                    targetMultiplier = 2.0 / 3.0;
                    armorMultiplier = 1.0 / 3.0;
                    if (attackRoll > 15) {
                        triggeredEffects.add("heated_armor");
                    }
                } else if (material == ArmorMaterial.STEEL) {
                    targetMultiplier = 0.5;
                    armorMultiplier = 1.0 / 3.0;
                    if (attackRoll > 15) {
                        triggeredEffects.add("heated_armor");
                    }
                }
                break;
            case "cold":
                if (material == ArmorMaterial.LEATHER) {
                    targetMultiplier = 1.0 / 3.0;
                    armorMultiplier = 0.5;
                } else if (material == ArmorMaterial.CHAIN) {
                    targetMultiplier = 2.0 / 3.0;
                    armorMultiplier = 1.0 / 3.0;
                } else if (material == ArmorMaterial.STEEL) {
                    targetMultiplier = 0.5;
                    armorMultiplier = 0.5;
                }
                if (attackRoll > 15) {
                    triggeredEffects.add("freeze");
                }
                break;
            case "lightning":
                if (material == ArmorMaterial.LEATHER) {
                    targetMultiplier = 1.0;
                    armorMultiplier = 1.0;
                } else if (material == ArmorMaterial.CHAIN) {
                    targetMultiplier = 1.5;
                    armorMultiplier = attackRoll > 15 ? 0.5 : 0.0;
                } else if (material == ArmorMaterial.STEEL) {
                    targetMultiplier = 1.5;
                    armorMultiplier = attackRoll > 15 ? 0.5 : 0.0;
                }
                if (attackRoll > 15) {
                    triggeredEffects.add("shock");
                }
                break;
            case "acid":
                if (attackRoll > 15) {
                    targetMultiplier = 1.0;
                    armorMultiplier = 0.0;
                    triggeredEffects.add("burn");
                } else if (material == ArmorMaterial.LEATHER) {
                    targetMultiplier = 0.0;
                    armorMultiplier = 1.0;
                } else if (material == ArmorMaterial.CHAIN) {
                    targetMultiplier = 1.0;
                    armorMultiplier = 0.5;
                } else if (material == ArmorMaterial.STEEL) {
                    targetMultiplier = 0.0;
                    armorMultiplier = 0.5;
                }
                break;
            case "psychic":
                if (attackRoll > 15) {
                    triggeredEffects.add("fear");
                }
                break;
            case "bio":
                if (attackRoll > 15) {
                    triggeredEffects.add("infection");
                }
                break;
            default:
                break;
        }

        int baseAmount = rolledAmount;
        int damageToTarget = (int) Math.round(baseAmount * targetMultiplier);
        int damageToArmor = (int) Math.round(baseAmount * armorMultiplier);
        return new DamageResolution(damageToTarget, damageToArmor, triggeredEffects);
    }

    public List<DiceRoll> getDice() {
        return dice;
    }

    public void setDice(List<DiceRoll> dice) {
        this.dice = dice == null ? new ArrayList<>() : dice;
    }

    public boolean hasDice() {
        return !dice.isEmpty();
    }

    /**
     * Flat amount added on top of the rolled dice (the "+ 3" in "2d6 + 3"). Zero for every
     * damage written before constants existed, so old campaigns roll exactly as they did.
     */
    public int getConstant() {
        return constant;
    }

    public void setConstant(int constant) {
        this.constant = constant;
    }

    /** True when this deals anything at all: dice to roll, a positive constant, or both. */
    public boolean hasAmount() {
        return hasDice() || constant > 0;
    }

    /** The damage actually dealt for a given dice total: the roll plus the constant, never negative. */
    public int total(int rolledDice) {
        return Math.max(0, (hasDice() ? rolledDice : 0) + constant);
    }

    /**
     * Reads the pre-rework shape ({@code "amount": {"id": "d6", ...}}, a single die with no
     * count) so campaigns written before dice became a list keep loading. The next save of
     * whatever entity owns this migrates it to the {@code dice} list permanently - there is no
     * corresponding getter, so this never appears as a second, confusing field in the DM's form.
     */
    public void setAmount(JsonNode legacyAmount) {
        if (legacyAmount != null && legacyAmount.isObject() && legacyAmount.hasNonNull("id")) {
            dice = new ArrayList<>(List.of(new DiceRoll(legacyAmount.get("id").asText(), 1)));
        }
    }

    public String getTypeId() {
        return typeId;
    }

    public void setTypeId(String typeId) {
        this.typeId = typeId;
    }

    /** e.g. "2d6 + 1d4 + 3", resolving each entry's die label through the campaign's dice catalogue. */
    public String formula(java.util.function.Function<String, Dice> diceLookup) {
        return DiceRoll.formula(dice, constant, diceLookup);
    }

    @Override
    public String toString() {
        String rolled = DiceRoll.formula(dice, constant, null);
        return (rolled.isEmpty() ? "0" : rolled) + " " + (typeId != null ? typeId : "damage");
    }

    public static class DamageResolution {
        private int damageToTarget;
        private int damageToArmor;
        private List<String> triggeredEffects;

        public DamageResolution() {
        }

        public DamageResolution(int damageToTarget, int damageToArmor, List<String> triggeredEffects) {
            this.damageToTarget = damageToTarget;
            this.damageToArmor = damageToArmor;
            this.triggeredEffects = triggeredEffects;
        }

        public int getDamageToTarget() {
            return damageToTarget;
        }

        public void setDamageToTarget(int damageToTarget) {
            this.damageToTarget = damageToTarget;
        }

        public int getDamageToArmor() {
            return damageToArmor;
        }

        public void setDamageToArmor(int damageToArmor) {
            this.damageToArmor = damageToArmor;
        }

        public List<String> getTriggeredEffects() {
            return triggeredEffects;
        }

        public void setTriggeredEffects(List<String> triggeredEffects) {
            this.triggeredEffects = triggeredEffects;
        }
    }
}

