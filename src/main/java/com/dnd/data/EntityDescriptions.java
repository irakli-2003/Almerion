package com.dnd.data;

import com.dnd.model.combat.Effect;
import com.dnd.model.item.Item;
import com.dnd.model.item.armors.Armor;
import com.dnd.model.item.crafting.Blueprint;
import com.dnd.model.item.crafting.Device;
import com.dnd.model.item.crafting.Fuel;
import com.dnd.model.item.crafting.Tool;
import com.dnd.model.magic.EnergyCharges;
import com.dnd.model.magic.Spell;
import com.dnd.model.magic.SpellEffect;
import com.dnd.model.world.Dice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Readable, complete descriptions of spells and items for hover cards: a one-line summary
 * ("Level 3 evocation · 45 m · V, S, M (...)") and a body with every rule the entry carries.
 */
public final class EntityDescriptions {

    private final Function<String, Dice> dice;
    private final Function<String, Item> items;
    private final Function<String, Spell> spells;
    private final Function<String, Effect> effects;

    public EntityDescriptions(Function<String, Dice> dice, Function<String, Item> items, Function<String, Spell> spells) {
        this(dice, items, spells, null);
    }

    public EntityDescriptions(Function<String, Dice> dice, Function<String, Item> items, Function<String, Spell> spells,
                              Function<String, Effect> effects) {
        this.dice = dice == null ? id -> null : dice;
        this.items = items == null ? id -> null : items;
        this.spells = spells == null ? id -> null : spells;
        this.effects = effects == null ? id -> null : effects;
    }

    public static EntityDescriptions of(CampaignRepositories repos) {
        return new EntityDescriptions(repos.dice()::getById, repos.items()::getById, repos.spells()::getById,
            repos.effects()::getById);
    }

    // ------------------------------------------------------------------ spells

    public String spellSummary(Spell s) {
        List<String> parts = new ArrayList<>();
        String school = s.getSchool() == null ? "" : " " + s.getSchool().toString().toLowerCase();
        parts.add(s.getLevel() == 0 ? "Cantrip" + (school.isEmpty() ? "" : " (" + school.trim() + ")")
            : "Level " + s.getLevel() + school);
        if (s.getRange() > 0) parts.add("range " + s.getRange() + " ft");
        else parts.add("self / touch");
        if (s.getRadius() > 0) parts.add(s.getRadius() + " ft area");
        if (s.getConcentration() != null) parts.add("concentration");
        return String.join(" · ", parts);
    }

    public String spellBody(Spell s) {
        StringBuilder sb = new StringBuilder();
        if (notBlank(s.getDescription())) sb.append(s.getDescription().trim()).append("\n");
        String components = s.componentsText();
        if (!components.isEmpty()) line(sb, "Components", components);
        if (s.getCastingMethod() != null) line(sb, "Casting", s.getCastingMethod().getValue().replace('-', ' '));
        if (s.getDamage() != null) {
            String formula = s.getDamage().formula(dice);
            if (notBlank(formula)) line(sb, "Damage", formula);
        }
        String effectList = spellEffectNames(s.getEffects());
        if (!effectList.isEmpty()) line(sb, "Effects", effectList);
        if (s.getManaCost() > 0) line(sb, "Mana cost", String.valueOf(s.getManaCost()));
        if (s.getCooldownRounds() > 0) line(sb, "Cooldown", s.getCooldownRounds() + " rounds");
        if (s.getRequiredConsumables() != null && !s.getRequiredConsumables().isEmpty()) {
            line(sb, "Uses up", names(s.getRequiredConsumables()));
        }
        if (s.getRequiredTools() != null && !s.getRequiredTools().isEmpty()) {
            line(sb, "Needs", names(s.getRequiredTools()));
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------- items

    public String itemSummary(Item item) {
        List<String> parts = new ArrayList<>();
        String type = item.getType();
        if (notBlank(type)) parts.add(Character.toUpperCase(type.charAt(0)) + type.substring(1));
        if (item.getValueGold() > 0) parts.add(item.getValueGold() + " gp");
        if (item.getWeight() > 0) parts.add(trim(item.getWeight()) + " lb");
        return String.join(" · ", parts);
    }

    public String itemBody(Item item) {
        StringBuilder sb = new StringBuilder();
        if (notBlank(item.getDescription())) sb.append(item.getDescription().trim()).append("\n");
        if (item instanceof com.dnd.model.item.Weapon w && w.getWeaponDamage() != null) {
            String formula = w.getWeaponDamage().formula(dice);
            if (notBlank(formula)) line(sb, "Damage", formula);
            String fx = effectNames(w.getEffects());
            if (!fx.isEmpty()) line(sb, "On hit", fx);
        } else if (item.getDamage() != null && notBlank(item.getDamage().getDice())) {
            line(sb, "Damage", item.getDamage().getDice()
                + (notBlank(item.getDamage().getType()) ? " " + item.getDamage().getType() : ""));
        }
        if (item instanceof Armor a && a.getArmorClassBonus() != 0) {
            line(sb, "Armour class", "+" + a.getArmorClassBonus()
                + (a.getMaterial() != null ? " (" + a.getMaterial().toString().toLowerCase() + ")" : ""));
        }
        if (item instanceof Device d) {
            Spell linked = d.getSpellId() == null ? null : spells.apply(d.getSpellId());
            if (linked != null) line(sb, "Use", linked.getName() + " - " + oneLine(linked.getDescription()));
            if (d.getChargeLevel() > 0) {
                line(sb, "Power", d.isMechanical()
                    ? EnergyCharges.name(d.getChargeLevel()) + " charge or stronger (mechanical: stronger hits harder)"
                    : EnergyCharges.name(d.getChargeLevel()) + " charge only (tuned device)");
            }
        }
        if (item instanceof Fuel f && f.getFuelValue() > 0) line(sb, "Fuel value", f.getFuelValue() + " units per piece");
        if (item instanceof Tool t && t.getFurnaceTier() > 0) {
            line(sb, "Furnace", "makes charges up to " + EnergyCharges.name(t.getFurnaceTier()) + " (tier " + t.getFurnaceTier() + ")");
        }
        if (item instanceof Blueprint b) blueprint(sb, b);
        if (item instanceof com.dnd.model.item.weapons.physical_weapons.RangedWeapon r && r.getMaxCharges() > 0) {
            line(sb, "Ammunition", r.getMaxCharges() + " shots");
        }
        if (item.getDurability() != null && item.getDurability().getMax() > 1) {
            line(sb, "Durability", item.getDurability().getCurrent() + "/" + item.getDurability().getMax());
        }
        return sb.toString().trim();
    }

    private void blueprint(StringBuilder sb, Blueprint b) {
        Item product = b.getProductId() == null ? null : items.apply(b.getProductId());
        line(sb, "Builds", b.getProducesChargeLevel() > 0
            ? "1 " + EnergyCharges.name(b.getProducesChargeLevel()) + " charge (refills a spent capsule)"
            : (b.getProductQuantity() > 1 ? b.getProductQuantity() + "× " : "")
                + (product != null ? product.getName() : nz(b.getProductId())));
        if (b.getCheckDc() > 0) line(sb, "Check", "DC " + b.getCheckDc() + " - d20 + INT + proficiency (tools)");
        List<String> mats = new ArrayList<>();
        for (Blueprint.MaterialCost m : b.getMaterials()) {
            Item it = m == null ? null : items.apply(m.getItemId());
            if (m != null) mats.add(m.getQuantity() + "× " + (it == null ? m.getItemId() : it.getName()));
        }
        if (!mats.isEmpty()) line(sb, "Materials (used up)", String.join(", ", mats));
        List<String> tools = new ArrayList<>();
        for (String t : b.getToolIds()) {
            Item it = items.apply(t);
            tools.add(it == null ? t : it.getName());
        }
        if (!tools.isEmpty()) line(sb, "Tools (kept)", String.join(", ", tools));
        List<String> charges = new ArrayList<>();
        for (Blueprint.ChargeCost c : b.getCharges()) {
            if (c != null) charges.add(c.getCount() + "× " + EnergyCharges.name(c.getLevel()));
        }
        if (!charges.isEmpty()) line(sb, "Charges", String.join(", ", charges));
        if (b.getMinLevel() > 1) line(sb, "Requires", "character level " + b.getMinLevel());
    }

    // ---------------------------------------------------------------- mentions

    /**
     * The entry whose name (a key of {@code byLowerName}, lower case) appears in {@code text}
     * as whole words covering {@code index}; the longest name wins. Null when none does.
     */
    public static <T> T mentionAt(String text, int index, java.util.Map<String, T> byLowerName) {
        if (text == null || index < 0 || index >= text.length() || byLowerName.isEmpty()) return null;
        if (!Character.isLetterOrDigit(text.charAt(index))) return null;
        int start = text.lastIndexOf('\n', index) + 1;
        int end = text.indexOf('\n', index);
        String line = text.substring(start, end < 0 ? text.length() : end).toLowerCase(java.util.Locale.ROOT);
        int at = index - start;
        String best = null;
        for (String name : byLowerName.keySet()) {
            if (best != null && name.length() <= best.length()) continue;
            int from = Math.max(0, at - name.length() + 1);
            int hit = line.indexOf(name, from);
            while (hit >= 0 && hit <= at) {
                int stop = hit + name.length();
                boolean wordStart = hit == 0 || !Character.isLetterOrDigit(line.charAt(hit - 1));
                boolean wordEnd = stop >= line.length() || !Character.isLetterOrDigit(line.charAt(stop));
                if (stop > at && wordStart && wordEnd) {
                    best = name;
                    break;
                }
                hit = line.indexOf(name, hit + 1);
            }
        }
        return best == null ? null : byLowerName.get(best);
    }

    // ----------------------------------------------------------------- helpers

    private static void line(StringBuilder sb, String label, String value) {
        sb.append("\n").append(label).append(": ").append(value);
    }

    private static String effectNames(List<Effect> effects) {
        if (effects == null) return "";
        List<String> names = new ArrayList<>();
        for (Effect e : effects) {
            if (e == null) continue;
            String n = notBlank(e.getName()) ? e.getName() : e.getId();
            if (notBlank(n)) names.add(n);
        }
        return String.join(", ", names);
    }

    /** e.g. "Burning (1d4 + 2), Stunned", with names from the effect catalogue. */
    private String spellEffectNames(List<SpellEffect> list) {
        if (list == null) return "";
        List<String> names = new ArrayList<>();
        for (SpellEffect e : list) {
            if (e == null) continue;
            String n = e.describe(effects, dice);
            if (notBlank(n)) names.add(n);
        }
        return String.join(", ", names);
    }

    private static String names(List<Item> list) {
        List<String> names = new ArrayList<>();
        for (Item i : list) if (i != null) names.add(notBlank(i.getName()) ? i.getName() : i.getId());
        return String.join(", ", names);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').trim();
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
