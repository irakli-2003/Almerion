package com.dnd.model.crafting;

import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.item.Item;
import com.dnd.model.item.crafting.Blueprint;
import com.dnd.model.item.crafting.Fuel;
import com.dnd.model.item.crafting.Tool;
import com.dnd.model.magic.EnergyCharges;
import com.dnd.model.magic.SpellSlot;
import com.dnd.model.magic.SpellSlots;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

/**
 * The artificer's workshop rules, free of any UI: learning blueprints into the blueprint book,
 * crafting from them, and running the furnace that turns fuel into energy charges.
 *
 * <p>Items are looked up through a function (normally the campaign item repository) so the
 * rules can be unit-tested with a plain map.</p>
 */
public final class Workshop {

    private Workshop() {
    }

    // ── Inventory helpers ───────────────────────────────────────────────────

    public static int count(PlayerCharacter pc, String itemId) {
        if (pc == null || itemId == null || pc.getItems() == null) return 0;
        int n = 0;
        for (PlayerCharacter.PlayerItem item : pc.getItems()) {
            if (item != null && itemId.equalsIgnoreCase(item.getItemId())) n += Math.max(1, item.getQuantity());
        }
        return n;
    }

    public static void add(PlayerCharacter pc, String itemId, int quantity) {
        if (pc == null || itemId == null || quantity <= 0) return;
        for (PlayerCharacter.PlayerItem item : pc.getItems()) {
            if (item != null && itemId.equalsIgnoreCase(item.getItemId())) {
                item.setQuantity(Math.max(1, item.getQuantity()) + quantity);
                return;
            }
        }
        PlayerCharacter.PlayerItem fresh = new PlayerCharacter.PlayerItem(itemId, new PlayerCharacter.ItemCondition(100), false);
        fresh.setQuantity(quantity);
        pc.getItems().add(fresh);
    }

    /** Removes up to {@code quantity} of an item; returns how many were actually removed. */
    public static int remove(PlayerCharacter pc, String itemId, int quantity) {
        if (pc == null || itemId == null || pc.getItems() == null) return 0;
        int left = quantity;
        Iterator<PlayerCharacter.PlayerItem> it = pc.getItems().iterator();
        while (it.hasNext() && left > 0) {
            PlayerCharacter.PlayerItem item = it.next();
            if (item == null || !itemId.equalsIgnoreCase(item.getItemId())) continue;
            int have = Math.max(1, item.getQuantity());
            if (have > left) {
                item.setQuantity(have - left);
                left = 0;
            } else {
                it.remove();
                left -= have;
            }
        }
        return quantity - left;
    }

    private static String nameOf(String itemId, Function<String, Item> items) {
        Item item = itemId == null || items == null ? null : items.apply(itemId);
        return item != null && item.getName() != null ? item.getName() : itemId;
    }

    // ── Blueprint book ──────────────────────────────────────────────────────

    public static boolean knows(PlayerCharacter pc, String blueprintId) {
        if (pc == null || blueprintId == null) return false;
        for (String id : pc.getKnownBlueprints()) if (blueprintId.equalsIgnoreCase(id)) return true;
        return false;
    }

    /** Copies a blueprint into the book; false when it was already there. */
    public static boolean learn(PlayerCharacter pc, String blueprintId) {
        if (pc == null || blueprintId == null || blueprintId.isBlank() || knows(pc, blueprintId)) return false;
        pc.getKnownBlueprints().add(blueprintId);
        return true;
    }

    public static boolean forget(PlayerCharacter pc, String blueprintId) {
        return pc != null && pc.getKnownBlueprints().removeIf(id -> id != null && id.equalsIgnoreCase(blueprintId));
    }

    // ── Crafting ────────────────────────────────────────────────────────────

    /** Everything stopping {@code pc} from building {@code bp} right now; empty when it can be built. */
    public static List<String> problems(PlayerCharacter pc, Blueprint bp, Function<String, Item> items) {
        List<String> out = new ArrayList<>();
        if (pc == null || bp == null) {
            out.add("Nothing selected.");
            return out;
        }
        if (bp.getProductId() == null || bp.getProductId().isBlank()) out.add("The blueprint has no product set.");
        if (pc.getLevel() < bp.getMinLevel()) out.add("Needs character level " + bp.getMinLevel() + ".");
        for (Blueprint.MaterialCost m : bp.getMaterials()) {
            if (m == null || m.getItemId() == null) continue;
            int have = count(pc, m.getItemId());
            if (have < m.getQuantity()) {
                out.add("Needs " + m.getQuantity() + "\u00d7 " + nameOf(m.getItemId(), items) + " (has " + have + ").");
            }
        }
        for (String tool : bp.getToolIds()) {
            if (tool != null && count(pc, tool) == 0) out.add("Needs " + nameOf(tool, items) + ".");
        }
        List<SpellSlot> trial = SpellSlots.copy(pc.getSpellSlots());
        for (Blueprint.ChargeCost c : bp.getCharges()) {
            if (c == null) continue;
            for (int i = 0; i < c.getCount(); i++) {
                int slot = SpellSlots.lowestAvailable(trial, c.getLevel());
                if (slot < 0 || !SpellSlots.spend(trial, slot)) {
                    out.add("Needs " + c.getCount() + "\u00d7 " + EnergyCharges.name(c.getLevel()) + " charge"
                        + (c.getCount() == 1 ? "" : "s") + " (or stronger).");
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Builds the blueprint's product: materials and charges are used up, tools are kept.
     *
     * @return a log of what happened
     * @throws IllegalStateException when {@link #problems} is not empty
     */
    public static List<String> craft(PlayerCharacter pc, Blueprint bp, Function<String, Item> items) {
        List<String> problems = problems(pc, bp, items);
        if (!problems.isEmpty()) throw new IllegalStateException(String.join(" ", problems));
        List<String> log = new ArrayList<>();
        for (Blueprint.MaterialCost m : bp.getMaterials()) {
            if (m == null || m.getItemId() == null) continue;
            remove(pc, m.getItemId(), m.getQuantity());
            log.add("uses " + m.getQuantity() + "\u00d7 " + nameOf(m.getItemId(), items));
        }
        for (Blueprint.ChargeCost c : bp.getCharges()) {
            if (c == null) continue;
            for (int i = 0; i < c.getCount(); i++) {
                int slot = SpellSlots.lowestAvailable(pc.getSpellSlots(), c.getLevel());
                SpellSlots.spend(pc.getSpellSlots(), slot);
                log.add("spends a " + EnergyCharges.name(slot) + " charge");
            }
        }
        add(pc, bp.getProductId(), bp.getProductQuantity());
        log.add("builds " + bp.getProductQuantity() + "\u00d7 " + nameOf(bp.getProductId(), items));
        return log;
    }

    // ── Furnace ─────────────────────────────────────────────────────────────

    /** Highest charge level any carried furnace can make; 0 when no furnace is carried. */
    public static int furnaceTier(PlayerCharacter pc, Function<String, Item> items) {
        int best = 0;
        if (pc == null || pc.getItems() == null || items == null) return 0;
        for (PlayerCharacter.PlayerItem carried : pc.getItems()) {
            Item item = carried == null || carried.getItemId() == null ? null : items.apply(carried.getItemId());
            if (item instanceof Tool tool) best = Math.max(best, tool.getFurnaceTier());
        }
        return best;
    }

    /**
     * Feeds {@code count} pieces of a fuel item into the furnace.
     *
     * @return fuel units added (0 if the item is not fuel or not carried)
     */
    public static int burn(PlayerCharacter pc, String fuelId, int count, Function<String, Item> items) {
        Item item = fuelId == null || items == null ? null : items.apply(fuelId);
        if (!(item instanceof Fuel fuel) || fuel.getFuelValue() <= 0 || count <= 0) return 0;
        int burned = remove(pc, fuelId, count);
        int units = burned * fuel.getFuelValue();
        pc.setFurnaceFuel(pc.getFurnaceFuel() + units);
        return units;
    }

    /** Why one charge of {@code level} cannot be made right now, or null when it can. */
    public static String chargeProblem(PlayerCharacter pc, int level, Function<String, Item> items) {
        if (pc == null) return "No character.";
        SpellSlot slot = SpellSlots.find(pc.getSpellSlots(), level);
        if (slot == null || slot.getMax() <= 0) return "No " + EnergyCharges.name(level) + " charge capacity yet.";
        if (slot.getCurrent() >= slot.getMax()) return EnergyCharges.name(level) + " charges are already full.";
        int tier = furnaceTier(pc, items);
        if (tier <= 0) return "Needs a workshop furnace in the pack.";
        if (tier < level) return "The furnace only reaches " + EnergyCharges.name(tier) + " charges - upgrade it first.";
        int cost = EnergyCharges.fuelCost(level);
        if (pc.getFurnaceFuel() < cost) {
            return "Needs " + cost + " fuel units in the furnace (has " + pc.getFurnaceFuel() + ").";
        }
        return null;
    }

    /**
     * What a rest does for an energy caster: the furnace runs on the fuel in the pack and
     * refills {@code slots} (the sheet's own, or a battle token's copy), weakest charges first.
     * Fuel is only burned when it can finish a charge; the least efficient fuel goes in first.
     *
     * @return what happened, e.g. "burns 2x Coal Lump", "+3 Steam-pressure charges"; empty if nothing
     */
    public static List<String> restRefill(PlayerCharacter pc, List<SpellSlot> slots, Function<String, Item> items) {
        List<String> log = new ArrayList<>();
        int tier = furnaceTier(pc, items);
        if (pc == null || slots == null || tier <= 0) return log;
        java.util.Map<String, Integer> burned = new java.util.LinkedHashMap<>();
        for (SpellSlot slot : slots.stream().sorted(java.util.Comparator.comparingInt(SpellSlot::getLevel)).toList()) {
            if (slot == null || slot.getLevel() > tier) continue;
            int cost = EnergyCharges.fuelCost(slot.getLevel());
            int made = 0;
            while (slot.getCurrent() < slot.getMax()) {
                if (pc.getFurnaceFuel() + carriedFuelUnits(pc, items) < cost) break;
                while (pc.getFurnaceFuel() < cost) {
                    Fuel fuel = weakestFuel(pc, items);
                    burn(pc, fuel.getId(), 1, items);
                    burned.merge(fuel.getName() == null ? fuel.getId() : fuel.getName(), 1, Integer::sum);
                }
                pc.setFurnaceFuel(pc.getFurnaceFuel() - cost);
                slot.setCurrent(slot.getCurrent() + 1);
                made++;
            }
            if (made > 0) log.add("+" + made + " " + EnergyCharges.name(slot.getLevel()) + " charge" + (made == 1 ? "" : "s"));
        }
        burned.forEach((name, n) -> log.add(0, "burns " + n + "\u00d7 " + name));
        return log;
    }

    private static int carriedFuelUnits(PlayerCharacter pc, Function<String, Item> items) {
        int units = 0;
        if (pc.getItems() == null) return 0;
        for (PlayerCharacter.PlayerItem carried : pc.getItems()) {
            Item item = carried == null || carried.getItemId() == null ? null : items.apply(carried.getItemId());
            if (item instanceof Fuel f && f.getFuelValue() > 0) units += f.getFuelValue() * Math.max(1, carried.getQuantity());
        }
        return units;
    }

    private static Fuel weakestFuel(PlayerCharacter pc, Function<String, Item> items) {
        Fuel best = null;
        for (PlayerCharacter.PlayerItem carried : pc.getItems()) {
            Item item = carried == null || carried.getItemId() == null ? null : items.apply(carried.getItemId());
            if (item instanceof Fuel f && f.getFuelValue() > 0 && (best == null || f.getFuelValue() < best.getFuelValue())) best = f;
        }
        return best;
    }

    /** Makes one charge of {@code level}; returns null on success or the reason it failed. */
    public static String makeCharge(PlayerCharacter pc, int level, Function<String, Item> items) {
        String problem = chargeProblem(pc, level, items);
        if (problem != null) return problem;
        SpellSlot slot = SpellSlots.find(pc.getSpellSlots(), level);
        slot.setCurrent(slot.getCurrent() + 1);
        pc.setFurnaceFuel(pc.getFurnaceFuel() - EnergyCharges.fuelCost(level));
        return null;
    }
}
