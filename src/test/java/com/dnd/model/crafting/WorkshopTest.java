package com.dnd.model.crafting;

import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.Progression;
import com.dnd.model.item.Item;
import com.dnd.model.item.crafting.Blueprint;
import com.dnd.model.item.crafting.Fuel;
import com.dnd.model.item.crafting.Material;
import com.dnd.model.item.crafting.Tool;
import com.dnd.model.magic.CastingResource;
import com.dnd.model.magic.EnergyCharges;
import com.dnd.model.magic.SpellSlot;
import com.dnd.model.magic.SpellSlots;
import com.dnd.model.magic.SpellcastingType;
import com.dnd.ui.scenes.CharacterCreationWizard;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.*;

public class WorkshopTest {
    private final Map<String, Item> catalog = new HashMap<>();
    private final Function<String, Item> items = catalog::get;
    private PlayerCharacter pc;
    private Blueprint bp;

    private <T extends Item> T reg(T item, String id) {
        item.setId(id);
        item.setName(id);
        catalog.put(id, item);
        return item;
    }

    @Before
    public void setUp() {
        reg(new Material(), "brass");
        reg(new Tool(), "tinkers");
        Tool furnace = reg(new Tool(), "furnace");
        furnace.setFurnaceTier(2);
        Fuel coal = reg(new Fuel(), "coal");
        coal.setFuelValue(4);
        reg(new Material(), "grenade");
        bp = reg(new Blueprint(), "bp-grenade");
        bp.setProductId("grenade");
        bp.setProductQuantity(2);
        bp.getMaterials().add(new Blueprint.MaterialCost("brass", 2));
        bp.getToolIds().add("tinkers");
        bp.getCharges().add(new Blueprint.ChargeCost(1, 1));

        pc = new PlayerCharacter();
        pc.setName("Toren");
        pc.setLevel(5);
        pc.setItems(new ArrayList<>());
        pc.setCastingResource(CastingResource.ENERGY_CHARGES);
        pc.getSpellSlots().add(new SpellSlot(1, 4, 1));
        pc.getSpellSlots().add(new SpellSlot(2, 2, 0));
    }

    @Test
    public void craftingConsumesMaterialsAndChargesButKeepsTools() {
        Workshop.add(pc, "brass", 3);
        Workshop.add(pc, "tinkers", 1);
        assertTrue(Workshop.problems(pc, bp, items).isEmpty());

        Workshop.craft(pc, bp, items);

        assertEquals(1, Workshop.count(pc, "brass"));
        assertEquals(1, Workshop.count(pc, "tinkers"));
        assertEquals(2, Workshop.count(pc, "grenade"));
        assertEquals(0, pc.getSpellSlots().get(0).getCurrent());
    }

    @Test
    public void missingPartsAreReportedAndBlockCrafting() {
        Workshop.add(pc, "brass", 1);
        pc.getSpellSlots().get(0).setCurrent(0);
        List<String> problems = Workshop.problems(pc, bp, items);
        assertEquals(3, problems.size()); // brass, tools, charge
        assertThrows(IllegalStateException.class, () -> Workshop.craft(pc, bp, items));
        assertEquals(1, Workshop.count(pc, "brass"));
    }

    @Test
    public void strongerChargeStandsInForAWeakerOne() {
        Workshop.add(pc, "brass", 2);
        Workshop.add(pc, "tinkers", 1);
        pc.getSpellSlots().get(0).setCurrent(0);
        pc.getSpellSlots().get(1).setCurrent(1);
        assertTrue(Workshop.problems(pc, bp, items).isEmpty());
        Workshop.craft(pc, bp, items);
        assertEquals(0, pc.getSpellSlots().get(1).getCurrent());
    }

    @Test
    public void blueprintsAreRequiredAboveTheirMinimumLevel() {
        bp.setMinLevel(7);
        assertFalse(Workshop.problems(pc, bp, items).isEmpty());
    }

    @Test
    public void blueprintBookLearnsOnce() {
        assertTrue(Workshop.learn(pc, "bp-grenade"));
        assertFalse(Workshop.learn(pc, "bp-grenade"));
        assertTrue(Workshop.knows(pc, "bp-grenade"));
        assertTrue(Workshop.forget(pc, "bp-grenade"));
        assertFalse(Workshop.knows(pc, "bp-grenade"));
    }

    @Test
    public void furnaceBurnsFuelIntoCharges() {
        assertEquals(0, Workshop.furnaceTier(pc, items));
        assertNotNull(Workshop.chargeProblem(pc, 1, items));
        Workshop.add(pc, "furnace", 1);
        Workshop.add(pc, "coal", 2);
        assertEquals(2, Workshop.furnaceTier(pc, items));

        assertEquals(8, Workshop.burn(pc, "coal", 2, items));
        assertEquals(0, Workshop.count(pc, "coal"));
        assertEquals(8, pc.getFurnaceFuel());

        assertNull(Workshop.makeCharge(pc, 2, items));
        assertEquals(1, pc.getSpellSlots().get(1).getCurrent());
        assertEquals(8 - EnergyCharges.fuelCost(2), pc.getFurnaceFuel());
    }

    @Test
    public void longRestBurnsPackFuelIntoCharges() {
        Workshop.add(pc, "furnace", 1);
        Fuel wood = reg(new Fuel(), "wood");
        wood.setFuelValue(2);
        Workshop.add(pc, "wood", 3);
        Workshop.add(pc, "coal", 2);

        List<String> log = Progression.longRest(pc, items);

        // 3 missing Steam-pressure (2 each) + 2 missing Thermal (4 each) = 14 units; 6 wood + 8 coal = 14.
        assertEquals(4, pc.getSpellSlots().get(0).getCurrent());
        assertEquals(2, pc.getSpellSlots().get(1).getCurrent());
        assertEquals(0, Workshop.count(pc, "wood"));
        assertEquals(0, Workshop.count(pc, "coal"));
        assertTrue(String.join(", ", log), log.stream().anyMatch(l -> l.contains("Thermal")));
    }

    @Test
    public void shortRestRefuelsOnlyHalfTheMissingCharges() {
        Workshop.add(pc, "furnace", 1);
        Workshop.add(pc, "coal", 10);

        Progression.shortRest(pc, null, 0, items);

        // 3 Steam-pressure + 2 Thermal missing = 5; a short rest makes 3, weakest first.
        assertEquals(4, pc.getSpellSlots().get(0).getCurrent());
        assertEquals(0, pc.getSpellSlots().get(1).getCurrent());
        assertTrue(Workshop.count(pc, "coal") > 0);
    }

    @Test
    public void restWithoutFuelOrFurnaceRefillsNothing() {
        Workshop.add(pc, "coal", 5);
        Progression.longRest(pc, items);
        assertEquals(1, pc.getSpellSlots().get(0).getCurrent());
        assertEquals(5, Workshop.count(pc, "coal"));
    }

    @Test
    public void tunedDevicesOnlyTakeTheirOwnChargeAndMechanicalOnesHitHarder() {
        com.dnd.model.world.map.CombatState state = new com.dnd.model.world.map.CombatState(20);
        state.setCastingResource(CastingResource.ENERGY_CHARGES);
        List<SpellSlot> slots = new ArrayList<>();
        slots.add(new SpellSlot(1, 4, 0));
        slots.add(new SpellSlot(2, 2, 1));
        state.setSpellSlots(slots);

        com.dnd.model.magic.Spell steam = new com.dnd.model.magic.Spell();
        steam.setId("steam");
        steam.setName("Steam");
        steam.setLevel(1);
        steam.setDamage(new com.dnd.model.combat.Damage(
            new ArrayList<>(List.of(new com.dnd.model.combat.DiceRoll("d6", 2))), "fire"));
        var cast = com.dnd.model.combat.CastResolver.of(steam);

        // Tuned to Steam-pressure: the spare Thermal charge does not help.
        assertNotNull(com.dnd.model.combat.CastResolver.costProblem(state, cast.onCharge(1)));
        // Mechanical: run it on the Thermal charge for one extra die.
        var boosted = cast.onCharge(2);
        assertNull(com.dnd.model.combat.CastResolver.costProblem(state, boosted));
        assertEquals(1, boosted.getBoost());
        assertEquals(3, boosted.getDamage().getDice().get(0).getCount());
        assertEquals(2, cast.getDamage().getDice().get(0).getCount());
    }

    @Test
    public void furnaceTierLimitsChargeLevel() {
        Workshop.add(pc, "furnace", 1);
        pc.getSpellSlots().add(new SpellSlot(3, 1, 0));
        pc.setFurnaceFuel(100);
        assertNotNull(Workshop.chargeProblem(pc, 3, items));
    }

    @Test
    public void chargesStopAtTheirMaximum() {
        Workshop.add(pc, "furnace", 1);
        pc.setFurnaceFuel(100);
        pc.getSpellSlots().get(0).setCurrent(4);
        assertNotNull(Workshop.chargeProblem(pc, 1, items));
    }

    @Test
    public void energyCastersHalfCastAndDoNotRefillOnLongRest() {
        CharacterClass cls = new CharacterClass();
        cls.setId("artificer");
        cls.setSpellcasting(SpellcastingType.ENERGY);
        assertEquals(CastingResource.ENERGY_CHARGES, Progression.resourceOf(pc, cls));
        int[] max = SpellSlots.maxSlots(SpellcastingType.ENERGY, 5);
        assertEquals(4, max[0]);
        assertEquals(2, max[1]);

        Progression.longRest(pc);
        assertEquals(1, pc.getSpellSlots().get(0).getCurrent());
        assertEquals("Steam-pressure", EnergyCharges.name(1));
        assertEquals("Thermal", EnergyCharges.name(2));
    }

    @Test
    public void wizardSwapsClassDefaults() {
        CharacterClass artificer = new CharacterClass();
        artificer.setId("artificer");
        artificer.setSpellcasting(SpellcastingType.ENERGY);
        artificer.setDefaultSpells(List.of("mending"));
        artificer.setDefaultItems(List.of("tinkers"));
        CharacterClass fighter = new CharacterClass();
        fighter.setId("fighter");
        fighter.setSpellcasting(SpellcastingType.NONE);
        fighter.setDefaultItems(List.of("longsword"));

        CharacterCreationWizard.State s = new CharacterCreationWizard.State();
        s.itemIds.add("rope");
        CharacterCreationWizard.chooseClass(s, artificer);
        assertTrue(s.spellIds.contains("mending"));
        assertTrue(s.itemIds.containsAll(List.of("rope", "tinkers")));

        CharacterCreationWizard.chooseClass(s, fighter);
        assertTrue(s.spellIds.isEmpty());
        assertFalse(s.itemIds.contains("tinkers"));
        assertTrue(s.itemIds.containsAll(List.of("rope", "longsword")));
    }
}
