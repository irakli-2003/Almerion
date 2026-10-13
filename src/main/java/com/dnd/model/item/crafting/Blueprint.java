package com.dnd.model.item.crafting;

import com.dnd.model.item.Item;
import com.dnd.model.interfaces.Printable;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans for building something. A blueprint can be carried like any item; an artificer copies
 * it into their blueprint book once, after which they can build its {@link #getProductId()
 * product} whenever they carry the listed materials and tools and - for the fancier designs -
 * have the energy charges it needs.
 */
public class Blueprint extends Item {
    private String productId;
    private int productQuantity = 1;
    /** Free text such as "Utility", "Light combat" or "Medium combat". */
    private String category;
    /** Character level needed to understand the plans. */
    private int minLevel = 1;
    private int craftMinutes = 10;
    private List<MaterialCost> materials = new ArrayList<>();
    private List<String> toolIds = new ArrayList<>();
    private List<ChargeCost> charges = new ArrayList<>();
    private List<String> effects = new ArrayList<>();
    /** Crafting check: d20 + INT modifier + proficiency must reach this (0 = no roll needed). */
    private int checkDc;
    /** When above 0 the blueprint makes an energy capsule of this level instead of an item. */
    private int producesChargeLevel;

    public Blueprint() {
        setType("blueprint");
    }

    public int getCheckDc() { return checkDc; }
    public void setCheckDc(int checkDc) { this.checkDc = Math.max(0, checkDc); }

    public int getProducesChargeLevel() { return producesChargeLevel; }
    public void setProducesChargeLevel(int producesChargeLevel) { this.producesChargeLevel = Math.max(0, producesChargeLevel); }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public int getProductQuantity() { return productQuantity; }
    public void setProductQuantity(int productQuantity) { this.productQuantity = Math.max(1, productQuantity); }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public int getMinLevel() { return minLevel; }
    public void setMinLevel(int minLevel) { this.minLevel = Math.max(1, minLevel); }

    public int getCraftMinutes() { return craftMinutes; }
    public void setCraftMinutes(int craftMinutes) { this.craftMinutes = Math.max(0, craftMinutes); }

    public List<MaterialCost> getMaterials() { return materials; }
    public void setMaterials(List<MaterialCost> materials) { this.materials = materials == null ? new ArrayList<>() : materials; }

    public List<String> getToolIds() { return toolIds; }
    public void setToolIds(List<String> toolIds) { this.toolIds = toolIds == null ? new ArrayList<>() : toolIds; }

    public List<ChargeCost> getCharges() { return charges; }
    public void setCharges(List<ChargeCost> charges) { this.charges = charges == null ? new ArrayList<>() : charges; }

    /** Effect ids the finished product is known for; shown on the blueprint. */
    public List<String> getEffects() { return effects; }
    public void setEffects(List<String> effects) { this.effects = effects == null ? new ArrayList<>() : effects; }

    /** An amount of one material item used up by crafting. */
    public static class MaterialCost implements Printable {
        private String itemId;
        private int quantity = 1;

        public MaterialCost() {
        }

        public MaterialCost(String itemId, int quantity) {
            this.itemId = itemId;
            setQuantity(quantity);
        }

        public String getItemId() { return itemId; }
        public void setItemId(String itemId) { this.itemId = itemId; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = Math.max(1, quantity); }

        @Override
        public String toString() {
            return quantity + "\u00d7 " + itemId;
        }
    }

    /** Energy charges of one level poured into the build. */
    public static class ChargeCost implements Printable {
        private int level = 1;
        private int count = 1;

        public ChargeCost() {
        }

        public ChargeCost(int level, int count) {
            setLevel(level);
            setCount(count);
        }

        public int getLevel() { return level; }
        public void setLevel(int level) { this.level = Math.max(1, Math.min(9, level)); }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = Math.max(1, count); }

        @Override
        public String toString() {
            return count + "\u00d7 " + com.dnd.model.magic.EnergyCharges.name(level);
        }
    }
}
