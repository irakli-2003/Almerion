package com.dnd.model.rules;

import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.Progression;
import com.dnd.model.character.SheetDetails;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.item.Item;
import com.dnd.model.magic.SpellcastingType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The numbers printed on a character sheet: the GM's {@link SheetDetails} override when one is
 * set, otherwise the value worked out from the character, class, race and gear. Shared by the
 * character panel, the PDF sheet, the battle map and the browser sheet so they always agree.
 */
public final class SheetValues {

    public static final List<String> ABILITIES = List.of("str", "dex", "con", "int", "wis", "cha");
    public static final int DEFAULT_SPEED = 30;

    private SheetValues() {
    }

    private static SheetDetails sheet(PlayerCharacter pc) {
        return pc == null ? new SheetDetails() : pc.getSheet();
    }

    private static CoreStats stats(PlayerCharacter pc) {
        return pc == null || pc.getStats() == null ? new CoreStats() : pc.getStats();
    }

    public static int mod(PlayerCharacter pc, String ability) {
        String key = abilityKey(ability);
        return key == null ? 0 : FeatureRules.mod(stats(pc), key);
    }

    public static int proficiencyBonus(PlayerCharacter pc) {
        Integer o = sheet(pc).getProficiencyBonus();
        return o != null ? o : Progression.proficiencyBonus(pc == null ? 1 : Math.max(1, pc.getLevel()));
    }

    public static int initiative(PlayerCharacter pc) {
        Integer o = sheet(pc).getInitiative();
        return o != null ? o : mod(pc, "dex");
    }

    /** Walking speed in feet: race speed plus Fast/Unarmored Movement, unless overridden. */
    public static int speed(PlayerCharacter pc, CharacterRace race, FeatureRules.Kit kit, Function<String, Item> items) {
        Integer o = sheet(pc).getSpeed();
        if (o != null) return o;
        int base = race != null && race.getSpeed() > 0 ? race.getSpeed() : DEFAULT_SPEED;
        if (kit == null) return base;
        FeatureRules.Worn worn;
        try {
            worn = FeatureRules.worn(pc, items);
        } catch (RuntimeException e) {
            return base;
        }
        return base + kit.speedBonus(worn.wearingArmor(), worn.heavy());
    }

    public static FeatureRules.ArmorClass armorClass(PlayerCharacter pc, FeatureRules.Kit kit, Function<String, Item> items) {
        return FeatureRules.armorClass(pc, kit, items, 0);
    }

    public static int passivePerception(PlayerCharacter pc) {
        Integer o = sheet(pc).getPassivePerception();
        if (o != null) return o;
        return 10 + (pc == null ? 0 : pc.getSkills().getPerception());
    }

    public static String hitDice(PlayerCharacter pc, CharacterClass cls) {
        String o = sheet(pc).getHitDice();
        if (o != null && !o.isBlank()) return o.trim();
        int sides = cls != null && cls.getHitDie() != null && cls.getHitDie().getSides() > 0 ? cls.getHitDie().getSides() : 8;
        return (pc == null ? 1 : Math.max(1, pc.getLevel())) + "d" + sides;
    }

    /** Three-letter ability key ("str"…"cha") for a typed name like "Wisdom" or "WIS", else null. */
    public static String abilityKey(String text) {
        if (text == null) return null;
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.length() < 3) return null;
        String head = t.substring(0, 3);
        return ABILITIES.contains(head) ? head : null;
    }

    /** Whether the character adds proficiency to this ability's saving throw. */
    public static boolean saveProficient(PlayerCharacter pc, CharacterClass cls, String ability) {
        String key = abilityKey(ability);
        if (key == null) return false;
        List<String> chosen = sheet(pc).getSavingThrowProficiencies();
        if (!chosen.isEmpty()) {
            for (String c : chosen) if (key.equals(abilityKey(c))) return true;
            return false;
        }
        if (cls == null || cls.getSavingThrowBonuses() == null) return false;
        for (Map.Entry<String, Integer> e : cls.getSavingThrowBonuses().entrySet()) {
            if (key.equals(abilityKey(e.getKey())) && e.getValue() != null && e.getValue() > 0) return true;
        }
        return false;
    }

    public static int savingThrow(PlayerCharacter pc, CharacterClass cls, String ability) {
        return mod(pc, abilityKey(ability)) + (saveProficient(pc, cls, ability) ? proficiencyBonus(pc) : 0);
    }

    /** The ability spells are cast with ("int", "wis", "cha"), or null for non-casters. */
    public static String spellcastingAbility(PlayerCharacter pc, CharacterClass cls) {
        String o = abilityKey(sheet(pc).getSpellcastingAbility());
        if (o != null) return o;
        String id = (cls != null && cls.getId() != null ? cls.getId() : pc == null || pc.getClassId() == null ? "" : pc.getClassId())
            .toLowerCase(Locale.ROOT);
        switch (id) {
            case "bard", "paladin", "sorcerer", "warlock" -> { return "cha"; }
            case "cleric", "druid", "ranger" -> { return "wis"; }
            case "wizard", "artificer" -> { return "int"; }
            default -> { }
        }
        boolean caster = (cls != null && cls.getSpellcasting() != SpellcastingType.NONE)
            || (pc != null && (pc.getMaxMana() > 0 || (pc.getSpells() != null && !pc.getSpells().isEmpty())))
            || sheet(pc).getSpellSaveDc() != null || sheet(pc).getSpellAttackBonus() != null;
        if (!caster) return null;
        if (cls != null && cls.getPrimaryAbilities() != null) {
            for (String a : cls.getPrimaryAbilities()) {
                String k = abilityKey(a);
                if ("int".equals(k) || "wis".equals(k) || "cha".equals(k)) return k;
            }
        }
        return "int";
    }

    public static int spellSaveDc(PlayerCharacter pc, CharacterClass cls) {
        Integer o = sheet(pc).getSpellSaveDc();
        if (o != null) return o;
        String a = spellcastingAbility(pc, cls);
        return 8 + proficiencyBonus(pc) + (a == null ? 0 : mod(pc, a));
    }

    public static int spellAttackBonus(PlayerCharacter pc, CharacterClass cls) {
        Integer o = sheet(pc).getSpellAttackBonus();
        if (o != null) return o;
        String a = spellcastingAbility(pc, cls);
        return proficiencyBonus(pc) + (a == null ? 0 : mod(pc, a));
    }

    public static String signed(int v) {
        return v >= 0 ? "+" + v : String.valueOf(v);
    }
}
