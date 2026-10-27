package com.dnd.ui.components;

import com.dnd.model.magic.CastingResource;
import com.dnd.model.magic.SpellcastingType;

import java.util.HashMap;
import java.util.Map;

/**
 * Plain-language explanations shown under form fields and for the option currently picked,
 * so someone who has never played D&amp;D can still fill in a class, race, item or character.
 *
 * <p>Lookups try {@code "SimpleClassName.property"} first (a spell's "level" means something
 * different from a character's), then the bare property name.</p>
 */
public final class FieldHelp {

    private static final Map<String, String> FIELDS = new HashMap<>();
    private static final Map<Object, String> OPTIONS = new HashMap<>();

    static {
        // Common
        f("id", "Internal key other records use to point at this one. Lowercase-with-dashes; avoid changing it later.");
        f("name", "The name players see.");
        f("description", "What it is and what it does, in your own words. Players read this.");
        f("valueGold", "Price in gold pieces when bought or sold.");
        f("weight", "Weight in kilograms; matters for how much someone can carry.");
        f("damage", "Dice rolled when it hits, e.g. 1d8, and the damage type (slashing, fire...).");
        f("durability", "How much wear it can take before breaking. Current cannot exceed max.");
        f("pickable", "Untick for fixed objects (altars, doors) that cannot be picked up from the map.");
        f("effects", "Conditions or bonuses it applies, e.g. Burning, Stunned, Blessed.");

        // Classes
        f("CharacterClass.hitDie", "The die rolled for hit points each level. Bigger die = tougher class (d6 wizard ... d12 barbarian).");
        f("CharacterClass.primaryAbilities", "The ability scores this class relies on most - put your best numbers here.");
        f("CharacterClass.savingThrowBonuses", "Saving throws (resisting danger) the class is trained in, with their bonus.");
        f("CharacterClass.spellcasting", "How the class gets magic. Pick an option to read what it means.");
        f("CharacterClass.subclasses", "Specialisations chosen at character creation, e.g. Path of the Berserker.");
        f("CharacterClass.subclassDescriptions", "One overview per subclass (key = subclass name): its theme, how it plays "
            + "and who it suits. Shown in the character creation wizard above the subclass's features.");
        f("CharacterClass.features", "Abilities unlocked level by level. Leave 'subclass' blank for features every member gets.");
        f("CharacterClass.defaultSpells", "Spells pre-ticked when a new character picks this class. Players can still change them.");
        f("CharacterClass.defaultItems", "Starting equipment pre-ticked when a new character picks this class. Still changeable.");
        f("ClassFeature.level", "Character level at which the feature is gained.");
        f("ClassFeature.subclass", "Only members of this subclass get it; blank means everyone in the class.");

        // Races
        f("CharacterRace.abilityBonuses", "Bonuses added to ability scores, e.g. strength +2. Ability names in lowercase.");
        f("CharacterRace.speed", "How far they walk in one turn, in feet (30 is normal, 25 is short-legged).");

        // Characters
        f("PlayerCharacter.classId", "The character's class: their training and role in the party.");
        f("PlayerCharacter.raceId", "The character's people: gives ability bonuses, speed and traits.");
        f("PlayerCharacter.level", "Overall power, 1-20. Higher level = more HP, features and spells.");
        f("PlayerCharacter.xp", "Experience points. At 100 the character can level up (campaign rule).");
        f("PlayerCharacter.stats", "The six ability scores. 10 is average, 15 is very good, 8 is weak. Each 2 points above 10 = +1 to rolls.");
        f("PlayerCharacter.skills", "Bonus added to each skill check (e.g. +5 Stealth, -1 Animal Handling). Any whole number: positive, negative or 0. New characters start from their ability modifiers.");
        f("PlayerCharacter.maxHitPoints", "How much damage they can take before falling unconscious.");
        f("PlayerCharacter.maxMana", "Only for mana-based homebrew casters. Leave 0 for slot casters.");
        f("PlayerCharacter.subclass", "Their specialisation within the class.");
        f("PlayerCharacter.castingResource", "What they pay to cast. Leave empty to use the class default.");
        f("PlayerCharacter.spellSlots", "Casting uses per spell level (or energy charges for artificers). 'Current' goes down as they cast.");
        f("PlayerCharacter.knownBlueprints", "Blueprints copied into the artificer's book; only these can be crafted.");
        f("PlayerCharacter.furnaceFuel", "Fuel units burning in the workshop furnace, waiting to be turned into charges.");

        // Spells
        f("Spell.level", "0 = cantrip (free, unlimited). 1-9 = costs a slot or energy charge of that level or higher.");
        f("Spell.manaCost", "Mana spent by mana-based casters (and HP for blood-magic casters). Slot casters ignore it.");
        f("Spell.range", "How far away the target can be, in feet. 0 = touch or self.");
        f("Spell.radius", "Area of effect in feet; 0 means a single target.");
        f("Spell.cooldownRounds", "Rounds before it can be used again; 0 = no wait.");
        f("Spell.requiredConsumables", "Items used up every time (e.g. ammunition). Casting fails without them.");
        f("Spell.requiredTools", "Items that must be carried but are not used up.");
        f("Spell.components", "V = spoken words, S = hand gestures, M = a material. A gagged or bound caster can't cast spells that need V or S.");
        f("Spell.materialComponent", "The material the spell needs (the M component), e.g. 'a pinch of sulfur'.");
        f("Spell.materialCost", "Gold value of the material; 0 = trivial, a component pouch covers it.");
        f("Spell.materialConsumed", "Tick if casting destroys the material, so it must be bought again.");
        f("Spell.concentration", "Concentration spells end if the caster casts another one or is badly hurt.");

        // Crafting
        f("Blueprint.productId", "The item that comes out of the workshop when this blueprint is crafted.");
        f("Blueprint.productQuantity", "How many of the product one crafting makes (e.g. 6 bolts).");
        f("Blueprint.category", "Free label to sort the blueprint book, e.g. Utility, Light combat, Medium combat.");
        f("Blueprint.minLevel", "Character level needed to understand these plans.");
        f("Blueprint.craftMinutes", "In-game time the work takes, so the GM can judge whether there is time.");
        f("Blueprint.materials", "Items used up by crafting, with amounts.");
        f("Blueprint.toolIds", "Tool kits that must be in the pack. They are not used up.");
        f("Blueprint.charges", "Energy charges poured into the build (level 1 Steam-pressure, 2 Thermal, 3 Shock...).");
        f("Blueprint.checkDc", "Crafting check DC: d20 + INT + proficiency. Fail by 5 or more and the materials are ruined. 0 = no check.");
        f("Blueprint.producesChargeLevel", "Capsule recipe: fills one energy charge of this level instead of making an item. 0 = makes the product.");
        f("MaterialCost.quantity", "How many of this material one crafting uses.");
        f("ChargeCost.level", "Charge type: 1 Steam-pressure, 2 Thermal, 3 Shock, 4 Force, 5 Arcane Resonance. Higher charges can stand in for lower.");
        f("ChargeCost.count", "How many charges of that type.");
        f("Fuel.fuelValue", "Furnace units per piece. A steam-pressure charge costs 2 units, thermal 4, electric 7, magnetic 10, resonance 14.");
        f("Tool.furnaceTier", "0 = ordinary tool. Above 0 = a workshop furnace that can make energy charges up to this level. Raise it to 'upgrade' the workshop.");
        f("Device.spellId", "The action this gadget performs on the battle map. Whoever carries it gets the action.");
        f("Device.mechanical", "Ticked: a mechanical gadget that runs on this charge or any stronger one - a stronger charge adds a damage die per level. Unticked: tuned to exactly this charge type and nothing else.");
        f("Device.chargeLevel", "Energy charge level used per activation; 0 = runs on clockwork and costs nothing.");

        o(SpellcastingType.NONE, "No spells. The class fights with weapons and its own features (barbarian, fighter, rogue, monk).");
        o(SpellcastingType.FULL, "Gets spell slots from level 1 and reaches 9th-level spells - the strongest magic users (wizard, cleric, druid, bard, sorcerer).");
        o(SpellcastingType.HALF, "Gets fewer slots starting at level 2 and tops out at 5th-level spells; mixes magic with fighting (paladin, ranger).");
        o(SpellcastingType.THIRD, "A dash of magic from level 3, up to 4th-level spells (Spellblade, Spellthief).");
        o(SpellcastingType.PACT, "Very few slots, all at the highest level they know, but they come back after a short rest (warlock).");
        o(SpellcastingType.ENERGY, "Homebrew artificer. Same numbers as a half caster, but the 'slots' are energy charges: steam-pressure, thermal, "
            + "electric... Rests do NOT refill them - the workshop furnace does, by burning fuel.");
        o(CastingResource.SPELL_SLOTS, "Standard rules: each levelled spell uses one slot of its level or higher. Slots come back on a long rest.");
        o(CastingResource.ENERGY_CHARGES, "Artificer charges. Work like slots, but are refilled by burning fuel in a workshop furnace, not by resting.");
        o(CastingResource.MANA, "Homebrew: every spell has a mana cost paid from a pool; half the missing mana returns on a short rest.");
        o(CastingResource.HIT_POINTS, "Blood magic: a spell's mana cost is paid in the caster's own hit points.");
        o(CastingResource.NONE, "Cannot cast spells at all.");
    }

    private FieldHelp() {
    }

    private static void f(String key, String text) {
        FIELDS.put(key, text);
    }

    private static void o(Object option, String text) {
        OPTIONS.put(option, text);
    }

    /** Explanation for a property of {@code owner}, or null when there is none. */
    public static String field(Class<?> owner, String property) {
        if (property == null) return null;
        if (owner != null) {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                String text = FIELDS.get(c.getSimpleName() + "." + property);
                if (text != null) return text;
            }
        }
        return FIELDS.get(property);
    }

    /** Explanation of one enum option, or null. */
    public static String option(Object option) {
        return option == null ? null : OPTIONS.get(option);
    }
}
