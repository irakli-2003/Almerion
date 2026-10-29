package com.dnd.model.magic;

import com.dnd.model.combat.Damage;
import com.dnd.model.combat.Effect;
import com.dnd.model.interfaces.Printable;
import com.dnd.model.item.Item;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
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
    private List<SpellEffect> effects = new ArrayList<>();
    private Damage damage;
    /** Verbal / somatic / material ticks plus the material's description, cost and consumption. */
    private SpellComponents components = new SpellComponents();

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
        if (effects != null) {
            for (Effect effect : effects) {
                if (effect != null) this.effects.add(SpellEffect.of(effect));
            }
        }
        this.damage = damage;
    }

    /** Edited in the DM's form as one group of separate fields; see the JSON accessors below. */
    @JsonIgnore
    public SpellComponents getComponents() {
        return components;
    }

    @JsonIgnore
    public void setComponents(SpellComponents components) {
        this.components = components == null ? new SpellComponents() : components;
    }

    /** Short component line, e.g. "V, S, M (a bit of fleece)" or "V"; empty when none are listed. */
    public String componentsText() {
        return components.text();
    }

    // Spell files keep the original shape: a "components" letter list beside top-level material
    // fields. Reading also accepts a single line such as "V, S, M (a pinch of sulfur)".

    @JsonProperty("components")
    private List<String> jsonComponents() {
        return components.letters();
    }

    @JsonProperty("components")
    private void jsonComponents(JsonNode node) {
        components.setVerbal(false);
        components.setSomatic(false);
        components.setMaterial(false);
        components.readLegacy(node);
    }

    @JsonProperty("materialComponent")
    private String jsonMaterialComponent() {
        return components.getMaterialComponent();
    }

    @JsonProperty("materialComponent")
    private void jsonMaterialComponent(String text) {
        if (text != null && !text.isBlank()) components.setMaterialComponent(text);
    }

    @JsonProperty("materialCost")
    private int jsonMaterialCost() {
        return components.getMaterialCost();
    }

    @JsonProperty("materialCost")
    private void jsonMaterialCost(int cost) {
        if (cost > 0) components.setMaterialCost(cost);
    }

    @JsonProperty("materialConsumed")
    private boolean jsonMaterialConsumed() {
        return components.isMaterialConsumed();
    }

    @JsonProperty("materialConsumed")
    private void jsonMaterialConsumed(boolean consumed) {
        if (consumed) components.setMaterialConsumed(true);
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

    /** Effects picked from the campaign's catalogue, each with the power this spell gives it. */
    public List<SpellEffect> getEffects() {
        return effects;
    }

    public void setEffects(List<SpellEffect> effects) {
        this.effects = effects == null ? new ArrayList<>() : effects;
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
