package com.dnd.model.item.crafting;

import com.dnd.model.item.Item;

/**
 * A tool kit needed by blueprints (tinker's tools, smith's tools...). A tool with a
 * {@link #getFurnaceTier()} above zero is also a furnace: it can turn fuel into energy charges
 * of every level up to that tier. Upgrading a workshop simply means raising its tier.
 */
public class Tool extends Item {
    private int furnaceTier;

    public Tool() {
        setType("tool");
    }

    public int getFurnaceTier() {
        return furnaceTier;
    }

    public void setFurnaceTier(int furnaceTier) {
        this.furnaceTier = Math.max(0, Math.min(9, furnaceTier));
    }
}
