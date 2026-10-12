package com.dnd.ui.components;

import com.dnd.model.item.Item;
import com.dnd.model.item.alchemy.Potion;
import com.dnd.model.item.armors.BodyArmor;
import com.dnd.model.item.books.Book;
import com.dnd.model.item.crafting.Blueprint;
import com.dnd.model.item.crafting.Device;
import com.dnd.model.item.crafting.Fuel;
import com.dnd.model.item.crafting.Material;
import com.dnd.model.item.crafting.Tool;
import com.dnd.model.item.weapons.physical_weapons.MeleeWeapon;

import java.util.List;

/**
 * The kinds of item the DM can create, shared by the DM panel and the Manage Players quick
 * form so both offer the same choices with the same plain-language explanations.
 */
public final class ItemKinds {

    public record Kind(String label, String type, Class<? extends Item> javaClass, String note) {
        @Override
        public String toString() {
            return label;
        }

        public Item create() {
            try {
                Item item = javaClass.getDeclaredConstructor().newInstance();
                item.setType(type);
                return item;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create " + label, e);
            }
        }
    }

    public static final List<Kind> ALL = List.of(
        new Kind("Gear", "gear", Book.class,
            "Everyday stuff with no special rules: rope, keys, letters, trinkets, books."),
        new Kind("Weapon", "weapon", MeleeWeapon.class,
            "Something you attack with. Set its damage dice and type; the battle map uses it for attacks."),
        new Kind("Armor", "armor", BodyArmor.class,
            "Worn protection. Its armor class replaces the wearer's base AC when equipped."),
        new Kind("Alchemy", "alchemy", Potion.class,
            "Potions, oils and poisons: drunk or applied once, then gone."),
        new Kind("Blueprint", "blueprint", Blueprint.class,
            "Plans an artificer copies into their blueprint book. Lists the product it builds, the materials "
                + "used up, the tools needed and any energy charges poured in."),
        new Kind("Device", "device", Device.class,
            "A crafted gadget. Link an action (spell) to make it usable on the battle map; set a charge "
                + "level if each use needs an energy charge, or 0 if it runs on clockwork."),
        new Kind("Tool", "tool", Tool.class,
            "Tool kits blueprints ask for (tinker's tools, smith's tools...). A furnace tier above 0 makes "
                + "it a workshop furnace that turns fuel into energy charges up to that level."),
        new Kind("Material", "material", Material.class,
            "Crafting ingredients that blueprints use up: scrap brass, springs, lenses, salts."),
        new Kind("Fuel", "fuel", Fuel.class,
            "Burns in a workshop furnace. Fuel value = furnace units per piece (wood 2, charcoal 3, coal 4, oil 5).")
    );

    private ItemKinds() {
    }

    /** The kind matching an existing item (by class first, then by its type string). */
    public static Kind of(Item item) {
        if (item == null) return ALL.get(0);
        for (Kind kind : ALL) {
            if (kind.javaClass() != Book.class && kind.javaClass().isInstance(item)) return kind;
        }
        for (Kind kind : ALL) if (kind.type().equalsIgnoreCase(item.getType())) return kind;
        return ALL.get(0);
    }
}
