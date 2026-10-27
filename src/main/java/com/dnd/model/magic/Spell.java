package com.dnd.model.magic;

import com.dnd.model.combat.Damage;
import com.dnd.model.combat.Effect;
import com.dnd.model.interfaces.Printable;
import com.dnd.model.item.Item;

import java.util.List;

public class Spell implements Printable {
    public static final int MIN_LEVEL = 0;
    public static final int MAX_LEVEL = 9;

    private String id;
    private String name;
    private String description;
    private int level;
    private School school;
    private int manaCost;
    private int range;
    private int radius;
    private CastingMethod castingMethod;
    private List<Item> requiredConsumables;
    private List<Item> requiredTools;
    private Concentration concentration;
    private List<Effect> effects;
    private Damage damage;
    /** Spell components: any of "V" (spoken words), "S" (gestures), "M" (a material). */
    private List<String> components = new java.util.ArrayList<>();
    /** What the material component is, e.g. "a pinch of sulfur and bat guano". */
    private String materialComponent;
    /** Gold value of a costly material (0 = trivial, covered by a component pouch or focus). */
    private int materialCost;
    /** True when casting uses the material up. */
    private boolean materialConsumed;

    public Spell() {
    }

    public Spell(String id, String name, String description, int level, School school, int manaCost,
                 int range, int radius, CastingMethod castingMethod, List<Item> requiredConsumables,
                 List<Item> requiredTools, Concentration concentration, List<Effect> effects, Damage damage) {
        this.id = id;
        this.name = name;
        this.description = description;
        setLevel(level);
        this.school = school;
        setManaCost(manaCost);
        setRange(range);
        setRadius(radius);
        this.castingMethod = castingMethod;
        this.requiredConsumables = requiredConsumables;
        this.requiredTools = requiredTools;
        this.concentration = concentration;
        this.effects = effects;
        this.damage = damage;
    }

    public List<String> getComponents() {
        return components;
    }

    public void setComponents(List<String> components) {
        this.components = components == null ? new java.util.ArrayList<>() : components;
    }

    public String getMaterialComponent() {
        return materialComponent;
    }

    public void setMaterialComponent(String materialComponent) {
        this.materialComponent = materialComponent;
    }

    public int getMaterialCost() {
        return materialCost;
    }

    public void setMaterialCost(int materialCost) {
        this.materialCost = Math.max(0, materialCost);
    }

    public boolean isMaterialConsumed() {
        return materialConsumed;
    }

    public void setMaterialConsumed(boolean materialConsumed) {
        this.materialConsumed = materialConsumed;
    }

    /** Short component line, e.g. "V, S, M (a bit of fleece)" or "V"; empty when none are listed. */
    public String componentsText() {
        if (components == null || components.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(String.join(", ", components));
        if (components.contains("M") && materialComponent != null && !materialComponent.isBlank()) {
            sb.append(" (").append(materialComponent);
            if (materialCost > 0) sb.append(", worth ").append(materialCost).append(" gp");
            if (materialConsumed) sb.append(", consumed");
            sb.append(")");
        }
        return sb.toString();
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

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Spell level must be between " + MIN_LEVEL + " and " + MAX_LEVEL + " but was " + level);
        }
        this.level = level;
    }

    public School getSchool() {
        return school;
    }

    public void setSchool(School school) {
        this.school = school;
    }

    public int getManaCost() {
        return manaCost;
    }

    public void setManaCost(int manaCost) {
        if (manaCost < 0) {
            throw new IllegalArgumentException("Spell mana cost must be non-negative but was " + manaCost);
        }
        this.manaCost = manaCost;
    }

    public int getRange() {
        return range;
    }

    public void setRange(int range) {
        if (range < 0) {
            throw new IllegalArgumentException("Spell range must be non-negative but was " + range);
        }
        this.range = range;
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("Spell radius must be non-negative but was " + radius);
        }
        this.radius = radius;
    }

    public CastingMethod getCastingMethod() {
        return castingMethod;
    }

    public void setCastingMethod(CastingMethod castingMethod) {
        this.castingMethod = castingMethod;
    }

    public List<Item> getRequiredConsumables() {
        return requiredConsumables;
    }

    public void setRequiredConsumables(List<Item> requiredConsumables) {
        this.requiredConsumables = requiredConsumables;
    }

    public List<Item> getRequiredTools() {
        return requiredTools;
    }

    public void setRequiredTools(List<Item> requiredTools) {
        this.requiredTools = requiredTools;
    }

    public Concentration getConcentration() {
        return concentration;
    }

    public void setConcentration(Concentration concentration) {
        this.concentration = concentration;
    }

    public List<Effect> getEffects() {
        return effects;
    }

    public void setEffects(List<Effect> effects) {
        this.effects = effects;
    }

    public Damage getDamage() {
        return damage;
    }

    public void setDamage(Damage damage) {
        this.damage = damage;
    }

    /**
     * Rounds before this spell can be cast again. Zero means it is always available, which
     * keeps every spell written before cooldowns existed behaving exactly as it did.
     */
    private int cooldownRounds;

    public int getCooldownRounds() {
        return cooldownRounds;
    }

    public void setCooldownRounds(int cooldownRounds) {
        this.cooldownRounds = Math.max(0, cooldownRounds);
    }

    /** True when this spell is thrown at a patch of ground rather than at one creature. */
    public boolean isAreaSpell() {
        return radius > 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name != null ? name : id);
        if (level > 0) {
            sb.append(" (Level ").append(level).append(")");
        }
        if (school != null) {
            sb.append(" [").append(school).append("]");
        }
        return sb.toString();
    }
}
