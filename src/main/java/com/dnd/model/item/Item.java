package com.dnd.model.item;

import com.dnd.model.interfaces.Printable;
import com.dnd.model.item.alchemy.Potion;
import com.dnd.model.item.armors.BodyArmor;
import com.dnd.model.item.books.Book;
import com.dnd.model.item.weapons.physical_weapons.MeleeWeapon;
import com.dnd.model.interfaces.Pickable;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", defaultImpl = Book.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Book.class, name = "book"),
    @JsonSubTypes.Type(value = BodyArmor.class, name = "armor"),
    @JsonSubTypes.Type(value = MeleeWeapon.class, name = "weapon"),
    @JsonSubTypes.Type(value = Potion.class, name = "alchemy"),
    @JsonSubTypes.Type(value = com.dnd.model.item.crafting.Blueprint.class, name = "blueprint"),
    @JsonSubTypes.Type(value = com.dnd.model.item.crafting.Fuel.class, name = "fuel"),
    @JsonSubTypes.Type(value = com.dnd.model.item.crafting.Tool.class, name = "tool"),
    @JsonSubTypes.Type(value = com.dnd.model.item.crafting.Material.class, name = "material"),
    @JsonSubTypes.Type(value = com.dnd.model.item.crafting.Device.class, name = "device")
})
public abstract class Item implements Printable, Pickable {
    private String id;
    private String name;
    private String type;
    private String description;
    private int valueGold;
    private double weight;
    private ItemDamage damage;
    private ItemDurability durability;
    /**
     * Whether an actor standing on the same cell can pick this item up.
     * Defaults to {@code true} for all items; set to {@code false} for
     * fixed objects such as shrines, doors, or quest-locked items.
     */
    private boolean pickable = true;

    protected Item() {
    }

    protected Item(String id, String name, String type, String description, int valueGold, double weight, ItemDamage damage, ItemDurability durability) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.description = description;
        this.valueGold = valueGold;
        this.weight = weight;
        this.damage = damage;
        this.durability = durability;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getValueGold() {
        return valueGold;
    }

    public void setValueGold(int valueGold) {
        if (valueGold < 0) {
            throw new IllegalArgumentException("Item value must be non-negative but was " + valueGold);
        }
        this.valueGold = valueGold;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        if (weight < 0) {
            throw new IllegalArgumentException("Item weight must be non-negative but was " + weight);
        }
        this.weight = weight;
    }

    public ItemDamage getDamage() {
        return damage;
    }

    public void setDamage(ItemDamage damage) {
        this.damage = damage;
    }

    public ItemDurability getDurability() {
        return durability;
    }

    public void setDurability(ItemDurability durability) {
        this.durability = durability;
    }

    @Override
    public boolean isPickable() {
        return pickable;
    }

    public void setPickable(boolean pickable) {
        this.pickable = pickable;
    }

    private String imagePath;
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }

    @Override
    public String toString() {
        return name != null ? name : id;
    }

    public static class ItemDamage implements Printable {
        private String dice;
        private String type;

        public ItemDamage() {
        }

        public ItemDamage(String dice, String type) {
            this.dice = dice;
            this.type = type;
        }

        public String getDice() {
            return dice;
        }

        public void setDice(String dice) {
            this.dice = dice;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        @Override
        public String toString() {
            return dice != null ? dice + " " + (type != null ? type : "damage") : "damage";
        }
    }

    public static class ItemDurability implements Printable {
        private int max;
        private int current;

        public ItemDurability() {
        }

        public ItemDurability(int max, int current) {
            setMax(max);
            setCurrent(current);
        }

        public int getMax() {
            return max;
        }

        public void setMax(int max) {
            if (max < 0) {
                throw new IllegalArgumentException("Item durability max must be non-negative but was " + max);
            }
            this.max = max;
        }

        public int getCurrent() {
            return current;
        }

        public void setCurrent(int current) {
            if (current < 0) {
                throw new IllegalArgumentException("Item durability current must be non-negative but was " + current);
            }
            if (current > this.max) {
                throw new IllegalArgumentException("Item durability current (" + current + ") cannot exceed max (" + this.max + ")");
            }
            this.current = current;
        }

        @Override
        public String toString() {
            return current + "/" + max;
        }
    }
}
