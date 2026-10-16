package com.dnd.model.combat;

import com.dnd.model.creature.Monster;
import com.dnd.model.world.map.CombatState;
import com.dnd.model.world.map.MonsterToken;
import com.dnd.model.world.map.TokenSupport;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class CastTargetingTest {

    private static final Map<String, Effect> EFFECTS = Map.of(
        "restrained", effect("restrained", "Restrained"),
        "blessed", effect("blessed", "Blessed"),
        CastResolver.LIFE_DRAIN_EFFECT, effect(CastResolver.LIFE_DRAIN_EFFECT, "Life Drain"),
        CastResolver.NO_HEALING_EFFECT, effect(CastResolver.NO_HEALING_EFFECT, "No Healing"));

    private static Effect effect(String id, String name) {
        Effect effect = new Effect();
        effect.setId(id);
        effect.setName(name);
        effect.setDescription(name + ".");
        return effect;
    }

    private static Ability ability(String id, double range, double radius, String... effects) {
        Ability ability = new Ability();
        ability.setId(id);
        ability.setName(id);
        ability.setDescription("Roll d4 and read the hat.");
        ability.setRange(range);
        ability.setRadius(radius);
        ability.setEffects(new ArrayList<>(List.of(effects)));
        return ability;
    }

    private static CastResolver.Castable castable(Ability ability) {
        return CastResolver.of(ability, EFFECTS::get);
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

    @Test
    public void rangeZeroWithoutRadiusOrDamageIsSelf() {
        CastResolver.Castable hats = castable(ability("hats", 0, 0));
        assertEquals(CastResolver.Targeting.SELF, hats.getTargeting());
        assertFalse(hats.needsTarget());
    }

    @Test
    public void rangeZeroWithRadiusIsAnAuraAroundTheCaster() {
        CastResolver.Castable sitDown = castable(ability("sit-down", 0, 30, "restrained"));
        assertEquals(CastResolver.Targeting.AURA, sitDown.getTargeting());
        assertFalse(sitDown.needsTarget());
    }

    @Test
    public void rangedAndTouchAttacksStillNeedATarget() {
        assertTrue(castable(ability("kneel", 30, 0)).needsTarget());
        assertEquals(CastResolver.Targeting.AREA, castable(ability("blast", 60, 10)).getTargeting());
        Ability touch = ability("claw", 0, 0);
        touch.setDamage(new Damage(new ArrayList<>(List.of(new DiceRoll("d6", 1))), "slashing"));
        assertEquals(CastResolver.Targeting.SINGLE, castable(touch).getTargeting());
    }

    @Test
    public void selfAbilityLandsOnTheCasterWithoutATarget() {
        MonsterToken caster = token("Hatter");
        CastResolver.Outcome outcome = CastResolver.cast(caster, castable(ability("ward", 0, 0, "blessed")),
            List.of(), 0, 0);
        assertTrue(outcome.getMessage(), outcome.isSuccess());
        assertTrue(TokenSupport.combatOf(caster).getActiveEffects().stream()
            .anyMatch(e -> "blessed".equals(e.getEffectId())));
    }

    @Test
    public void auraConditionsLandOnEveryoneAroundButTheCaster() {
        MonsterToken caster = token("Hatter");
        MonsterToken a = token("A");
        MonsterToken b = token("B");
        CastResolver.Outcome outcome = CastResolver.cast(caster, castable(ability("sit-down", 0, 30, "restrained")),
            List.of(caster, a, b), 0, 0);
        assertTrue(outcome.getMessage(), outcome.isSuccess());
        for (MonsterToken t : List.of(a, b)) {
            assertTrue(TokenSupport.combatOf(t).getActiveEffects().stream()
                .anyMatch(e -> "restrained".equals(e.getEffectId()) && e.getRemainingRounds() > 0));
        }
        assertTrue(TokenSupport.combatOf(caster).getActiveEffects().isEmpty());
    }

    @Test
    public void untimedConditionSurvivesTheTargetsNextTurnStart() {
        MonsterToken caster = token("Hatter");
        MonsterToken target = token("Target");
        CastResolver.cast(caster, castable(ability("grab", 30, 0, "restrained")), List.of(target), 10, 0);
        CombatState state = TokenSupport.combatOf(target);
        state.tickEffects("Target");
        assertEquals(1, state.getActiveEffects().size());
    }

    private static Ability shovel() {
        Ability shovel = ability("shovel", 10, 0, CastResolver.LIFE_DRAIN_EFFECT);
        shovel.setDamage(new Damage(new ArrayList<>(List.of(new DiceRoll("d8", 2))), "bludgeoning"));
        return shovel;
    }

    @Test
    public void lifeDrainHealsTheCasterForTheDamageDealt() {
        MonsterToken caster = token("Caretaker");
        TokenSupport.combatOf(caster).setCurrentHitPoints(10);
        MonsterToken target = token("Target");
        CastResolver.Outcome outcome = CastResolver.cast(caster, castable(shovel()), List.of(target), 5, 8);
        assertTrue(outcome.getMessage(), outcome.isSuccess());
        assertEquals(22, TokenSupport.combatOf(target).getCurrentHitPoints());
        assertEquals(18, TokenSupport.combatOf(caster).getCurrentHitPoints());
    }

    @Test
    public void noHealingBlocksLifeDrain() {
        MonsterToken caster = token("Caretaker");
        CastResolver.cast(caster, castable(ability("stone", 0, 0, CastResolver.NO_HEALING_EFFECT)), List.of(), 0, 0);
        TokenSupport.combatOf(caster).setCurrentHitPoints(10);
        MonsterToken target = token("Target");
        CastResolver.Outcome outcome = CastResolver.cast(caster, castable(shovel()), List.of(target), 5, 8);
        assertTrue(outcome.getMessage(), outcome.isSuccess());
        assertEquals(22, TokenSupport.combatOf(target).getCurrentHitPoints());
        assertEquals(10, TokenSupport.combatOf(caster).getCurrentHitPoints());
    }

    @Test
    public void selfAbilityWithNoMechanicsStillShowsUpOnTheBoard() {
        MonsterToken caster = token("Hatter");
        CastResolver.Outcome outcome = CastResolver.cast(caster, castable(ability("hats", 0, 0)), List.of(), 0, 0);
        assertTrue(outcome.isSuccess());
        assertTrue(outcome.getLog().stream().anyMatch(line -> line.contains("Roll d4")));
        assertTrue(TokenSupport.combatOf(caster).getActiveEffects().stream()
            .anyMatch(e -> "hats".equals(e.getEffectId())));
    }
}
