package com.dnd.model.item.crafting;

import com.dnd.model.item.Item;

/**
 * Something a workshop furnace can burn. {@link #getFuelValue()} is how many fuel units one
 * piece adds to the furnace; better fuels (coal, lamp oil) are worth more than firewood.
 */
public class Fuel extends Item {
    private int fuelValue = 2;

    public Fuel() {
        setType("fuel");
    }

    public int getFuelValue() {
        return fuelValue;
    }

    public void setFuelValue(int fuelValue) {
        this.fuelValue = Math.max(0, fuelValue);
    }
}
