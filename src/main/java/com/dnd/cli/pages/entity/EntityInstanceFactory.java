package com.dnd.cli.pages.entity;

import com.dnd.cli.core.ConsoleIO;
import com.dnd.model.item.Item;
import com.dnd.model.item.Weapon;
import com.dnd.model.item.alchemy.AlchemyItem;
import com.dnd.model.item.alchemy.Decoction;
import com.dnd.model.item.alchemy.Oil;
import com.dnd.model.item.alchemy.Poison;
import com.dnd.model.item.alchemy.Potion;
import com.dnd.model.item.armors.Armor;
import com.dnd.model.item.books.Book;
import com.dnd.model.item.weapons.magic_weapons.DarkWeapon;
import com.dnd.model.item.weapons.magic_weapons.DivineWeapon;
import com.dnd.model.item.weapons.magic_weapons.ElementalWeapon;
import com.dnd.model.item.weapons.magic_weapons.IllusionWeapon;
import com.dnd.model.item.weapons.magic_weapons.MagicWeapon;
import com.dnd.model.item.weapons.magic_weapons.NatureWeapon;
import com.dnd.model.item.weapons.magic_weapons.NecromancyWeapon;
import com.dnd.model.item.weapons.magic_weapons.TeleportationWeapon;
import com.dnd.model.item.weapons.magic_weapons.TransmutationWeapon;
import com.dnd.model.item.weapons.magic_weapons.SignMagicWeapon;
import com.dnd.model.item.weapons.physical_weapons.FinesseWeapon;
import com.dnd.model.item.weapons.physical_weapons.MeleeWeapon;
import com.dnd.model.item.weapons.physical_weapons.PhysicalWeapon;
import com.dnd.model.item.weapons.physical_weapons.RangedWeapon;
import com.dnd.model.item.weapons.physical_weapons.ThrowingWeapon;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves abstract/interface model types to a concrete subtype (prompting the
 * user when more than one option exists) and instantiates entities via their
 * no-arg constructor.
 *
 * Centralizing the "which subtypes exist for this abstract type" knowledge here
 * (instead of scattering it across page classes) means new item/weapon
 * subtypes only need to be registered in one place.
 */
public final class EntityInstanceFactory {
    private static final List<Class<?>> ITEM_TYPES = Arrays.asList(
        Armor.class,
        Book.class,
        com.dnd.model.item.crafting.Blueprint.class,
        com.dnd.model.item.crafting.Device.class,
        com.dnd.model.item.crafting.Tool.class,
        com.dnd.model.item.crafting.Material.class,
        com.dnd.model.item.crafting.Fuel.class,
        Potion.class,
        Poison.class,
        Oil.class,
        Decoction.class,
        MeleeWeapon.class,
        RangedWeapon.class,
        FinesseWeapon.class,
        ThrowingWeapon.class,
        DarkWeapon.class,
        DivineWeapon.class,
        ElementalWeapon.class,
        IllusionWeapon.class,
        NatureWeapon.class,
        NecromancyWeapon.class,
        TeleportationWeapon.class,
        TransmutationWeapon.class,
        SignMagicWeapon.class
    );
    private static final List<Class<?>> WEAPON_TYPES = Arrays.asList(
        MeleeWeapon.class,
        RangedWeapon.class,
        FinesseWeapon.class,
        ThrowingWeapon.class,
        DarkWeapon.class,
        DivineWeapon.class,
        ElementalWeapon.class,
        IllusionWeapon.class,
        NatureWeapon.class,
        NecromancyWeapon.class,
        TeleportationWeapon.class,
        TransmutationWeapon.class,
        SignMagicWeapon.class
    );
    private static final List<Class<?>> ALCHEMY_TYPES = Arrays.asList(
        Potion.class,
        Poison.class,
        Oil.class,
        Decoction.class
    );
    private static final List<Class<?>> MAGIC_WEAPON_TYPES = Arrays.asList(
        DarkWeapon.class,
        DivineWeapon.class,
        ElementalWeapon.class,
        IllusionWeapon.class,
        NatureWeapon.class,
        NecromancyWeapon.class,
        TeleportationWeapon.class,
        TransmutationWeapon.class,
        SignMagicWeapon.class
    );

    private EntityInstanceFactory() {
    }

    public static Object createInstanceForType(Class<?> modelClass, ConsoleIO console, String label) {
        Class<?> resolved = resolveConcreteClass(modelClass, console, label);
        if (resolved == null) {
            return null;
        }
        return instantiateType(resolved);
    }

    public static Object instantiateType(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    public static Class<?> resolveConcreteClass(Class<?> type, ConsoleIO console, String label) {
        if (type == null) {
            return null;
        }
        if (!Modifier.isAbstract(type.getModifiers()) && !type.isInterface()) {
            return type;
        }

        List<Class<?>> options = getConcreteOptions(type);
        if (options.isEmpty()) {
            console.println("No concrete types available for " + label + ".");
            return null;
        }

        console.println("Choose " + label + " type:");
        for (int i = 0; i < options.size(); i++) {
            console.println("  " + (i + 1) + ") " + options.get(i).getSimpleName());
        }

        while (true) {
            console.print("Enter choice (1-" + options.size() + ", blank to cancel): ");
            String input = console.readLine().trim();
            if (input.isEmpty()) {
                return null;
            }
            try {
                int index = Integer.parseInt(input);
                if (index < 1 || index > options.size()) {
                    console.println("Enter a number between 1 and " + options.size() + ".");
                    continue;
                }
                return options.get(index - 1);
            } catch (NumberFormatException e) {
                console.println("Enter a valid number.");
            }
        }
    }

    private static List<Class<?>> getConcreteOptions(Class<?> type) {
        if (type == Item.class) {
            return ITEM_TYPES;
        }
        if (type == Weapon.class || type == PhysicalWeapon.class) {
            return WEAPON_TYPES;
        }
        if (type == AlchemyItem.class) {
            return ALCHEMY_TYPES;
        }
        if (type == MagicWeapon.class) {
            return MAGIC_WEAPON_TYPES;
        }
        return new ArrayList<>();
    }
}



