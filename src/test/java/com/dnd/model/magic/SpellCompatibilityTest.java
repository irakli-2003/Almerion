package com.dnd.model.magic;

import com.dnd.data.JsonMappers;
import com.dnd.model.combat.CastResolver;
import com.dnd.model.combat.Damage;
import com.dnd.model.combat.DiceRoll;
import com.dnd.model.combat.Effect;
import com.dnd.model.creature.Monster;
import com.dnd.model.world.map.CombatState;
import com.dnd.model.world.map.MonsterToken;
import com.dnd.model.world.map.TokenSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SpellCompatibilityTest {

    private final ObjectMapper mapper = JsonMappers.create();

    @Test
    public void oldComponentLettersLoadIntoSeparateFields() throws Exception {
        Spell spell = mapper.readValue("{\"id\":\"fireball\",\"components\":[\"V\",\"S\",\"M\"],"
            + "\"materialComponent\":\"a pinch of sulfur\",\"materialCost\":5,\"materialConsumed\":true}", Spell.class);
        SpellComponents c = spell.getComponents();
        assertTrue(c.isVerbal());
        assertTrue(c.isSomatic());
        assertTrue(c.isMaterial());
        assertEquals("a pinch of sulfur", c.getMaterialComponent());
        assertEquals(5, c.getMaterialCost());
        assertTrue(c.isMaterialConsumed());
        assertEquals("V, S, M (a pinch of sulfur, worth 5 gp, consumed)", spell.componentsText());
    }

    @Test
    public void oldComponentsLineIsParsed() throws Exception {
        Spell spell = mapper.readValue("{\"id\":\"x\",\"components\":\"V, M (a pinch of sulfur, worth 5 gp, consumed)\"}",
            Spell.class);
        SpellComponents c = spell.getComponents();
        assertTrue(c.isVerbal());
        assertFalse(c.isSomatic());
        assertTrue(c.isMaterial());
        assertEquals("a pinch of sulfur", c.getMaterialComponent());
        assertEquals(5, c.getMaterialCost());
        assertTrue(c.isMaterialConsumed());
    }

    @Test
    public void componentsRoundTripInTheOriginalShape() throws Exception {
        Spell spell = new Spell();
        spell.setId("shield");
        spell.getComponents().setVerbal(true);
        spell.getComponents().setMaterial(true);
        spell.getComponents().setMaterialComponent("a bit of fleece");

        JsonNode json = mapper.valueToTree(spell);
        assertTrue(json.get("components").isArray());
        assertEquals("[\"V\",\"M\"]", json.get("components").toString());
        assertEquals("a bit of fleece", json.get("materialComponent").asText());

        Spell back = mapper.treeToValue(json, Spell.class);
        assertEquals("V, M (a bit of fleece)", back.componentsText());
        assertFalse(back.getComponents().isSomatic());
    }

    @Test
    public void spellWithoutComponentsHasNone() throws Exception {
        Spell spell = mapper.readValue("{\"id\":\"x\"}", Spell.class);
        assertTrue(spell.getComponents().none());
        assertEquals("", spell.componentsText());
    }

    @Test
    public void oldEmbeddedEffectsStillLoadAndApply() throws Exception {
        Spell spell = mapper.readValue("{\"id\":\"burn\",\"effects\":[{\"id\":\"burning\",\"name\":\"Burning\","
            + "\"damaging\":true,\"damageAmount\":4}]}", Spell.class);
        assertEquals(1, spell.getEffects().size());
        SpellEffect link = spell.getEffects().get(0);
        assertEquals("burning", link.getEffectId());
        assertFalse(link.hasPower());

        Effect resolved = link.resolve(id -> null);
        assertEquals("Burning", resolved.getName());
        assertTrue(resolved.isDamaging());
        assertEquals(4, resolved.getDamageAmount());

        Effect catalogue = new Effect();
        catalogue.setId("burning");
        catalogue.setName("Burning (campaign)");
        assertEquals("Burning (campaign)", link.resolve(Map.of("burning", catalogue)::get).getName());
    }

    @Test
    public void effectPowerRoundTrips() throws Exception {
        Spell spell = new Spell();
        spell.setId("burn");
        spell.getEffects().add(new SpellEffect("burning", 2, List.of(new DiceRoll("d4", 1))));

        String json = mapper.writeValueAsString(spell);
        Spell back = mapper.readValue(json, Spell.class);
        SpellEffect link = back.getEffects().get(0);
        assertEquals("burning", link.getEffectId());
        assertEquals(2, link.getConstant());
        assertEquals(1, link.getDice().size());
        assertEquals("d4", link.getDice().get(0).getDiceId());
        assertNull(link.definition());
        assertEquals(5, link.power(3));
    }

    @Test
    public void damageConstantRoundTripsAndIsAdded() throws Exception {
        Damage damage = new Damage(List.of(new DiceRoll("d6", 2)), "fire");
        damage.setConstant(3);
        Spell spell = new Spell();
        spell.setId("bolt");
        spell.setDamage(damage);

        Spell back = mapper.readValue(mapper.writeValueAsString(spell), Spell.class);
        assertEquals(3, back.getDamage().getConstant());
        assertEquals("2d6 + 3", back.getDamage().formula(id -> null));
        assertEquals(10, back.getDamage().total(7));

        Damage flat = new Damage();
        flat.setConstant(4);
        assertTrue(flat.hasAmount());
        assertFalse(flat.hasDice());
        assertEquals(4, flat.total(0));
    }

    @Test
    public void effectPowerReplacesTheEffectsOwnAmountWhenCast() {
        Effect burning = new Effect();
        burning.setId("burning");
        burning.setName("Burning");
        burning.setDamaging(true);
        burning.setDamageAmount(10);

        Spell spell = new Spell();
        spell.setId("burn");
        spell.setName("Burn");
        spell.setRange(30);
        spell.getEffects().add(new SpellEffect("burning", 2, List.of(new DiceRoll("d4", 1))));

        CastResolver.Castable action = CastResolver.of(spell, Map.of("burning", burning)::get);
        assertTrue(action.needsEffectRoll(0));
        MonsterToken caster = token("Caster");
        MonsterToken target = token("Target");
        CastResolver.Outcome outcome = CastResolver.cast(caster, action, List.of(target), 5, 0, List.of(3));
        assertTrue(outcome.getMessage(), outcome.isSuccess());
        assertEquals(30 - 5, TokenSupport.combatOf(target).getCurrentHitPoints());
    }

    private static MonsterToken token(String name) {
        Monster monster = new Monster();
        monster.setId(name.toLowerCase());
        monster.setName(name);
        MonsterToken token = new MonsterToken(monster);
        CombatState state = TokenSupport.combatOf(token);
        state.setMaxHitPoints(30);
        state.setCurrentHitPoints(30);
        return token;
    }
}
