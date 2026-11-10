package com.dnd.model.combat;

import com.dnd.data.JsonMappers;
import com.dnd.model.creature.Monster;
import com.dnd.model.world.map.*;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class CastChecksTest {
    private MonsterToken token(String name) {
        Monster m = new Monster();
        m.setId(name);
        m.setName(name);
        MonsterToken token = new MonsterToken(m);
        CombatState state = TokenSupport.combatOf(token);
        state.setMaxHitPoints(100);
        state.setCurrentHitPoints(100);
        state.setMaxMana(20);
        state.setCurrentMana(20);
        return token;
    }
    private Ability ability() {
        Ability a = new Ability();
        a.setId("flame");
        a.setName("Flame");
        a.setRange(60);
        a.setRadius(10);
        a.setManaCost(2);
        a.setRecharge(3);
        Damage damage = new Damage();
        damage.setConstant(11);
        damage.setTypeId("fire");
        a.setDamage(damage);
        return a;
    }
    @Test public void savesResolvePerTargetBeforeResistanceAndSpendOnce() {
        var caster = token("caster");
        var saved = token("saved");
        var failed = token("failed");
        TokenSupport.combatOf(saved).getResistances().add("fire");
        Ability a = ability();
        a.getChecks().setSavingThrow("dexterity");
        var outcome = CastResolver.cast(caster, CastResolver.of(a, id -> null), List.of(saved, failed), 20, 0,
            List.of(), a.getChecks(), Map.of(
                saved, new CastChecks.Rolls(1, 0, 0, 10, 5, 15),
                failed, new CastChecks.Rolls(1, 0, 0, 9, 5, 15)));
        assertTrue(outcome.getMessage(), outcome.isSuccess());
        assertEquals(98, TokenSupport.combatOf(saved).getCurrentHitPoints());
        assertEquals(89, TokenSupport.combatOf(failed).getCurrentHitPoints());
        assertEquals(18, TokenSupport.combatOf(caster).getCurrentMana());
        assertEquals(3, TokenSupport.combatOf(caster).cooldownFor("flame"));
    }
    @Test public void saveNegatesDamageAndConditionsButStillSpendsCost() {
        var caster = token("caster");
        var target = token("target");
        Ability a = ability();
        a.setEffects(List.of("burn"));
        a.getChecks().setSavingThrow("wisdom");
        a.getChecks().setSuccessfulSaveDamage(CastChecks.SavedDamage.NONE);
        Effect burn = new Effect("burn", "Burn", "", true, false, 8, 0);
        burn.setDurationRounds(3);
        var outcome = CastResolver.cast(caster, CastResolver.of(a, id -> burn), List.of(target), 20, 0,
            List.of(), a.getChecks(), Map.of(target, new CastChecks.Rolls(1, 0, 0, 15, 0, 15)));
        assertTrue(outcome.isSuccess());
        assertEquals(100, TokenSupport.combatOf(target).getCurrentHitPoints());
        assertTrue(TokenSupport.combatOf(target).getActiveEffects().isEmpty());
        assertEquals(18, TokenSupport.combatOf(caster).getCurrentMana());
    }
    @Test public void attackMissNaturalOneAndNaturalTwentyWorkWithoutChangingDamageRoll() {
        for (int die : List.of(1, 2, 20)) {
            var caster = token("caster");
            var target = token("target");
            Ability a = ability();
            a.getChecks().setAttackRoll(true);
            var outcome = CastResolver.cast(caster, CastResolver.of(a, id -> null), List.of(target), 20, 0,
                List.of(), a.getChecks(), Map.of(target, new CastChecks.Rolls(die, 50, 100, 1, 0, 0)));
            assertTrue(outcome.isSuccess());
            assertEquals(die == 20 ? 89 : 100, TokenSupport.combatOf(target).getCurrentHitPoints());
            assertEquals(18, TokenSupport.combatOf(caster).getCurrentMana());
        }
    }
    @Test public void missingRollsAndOutOfRangeCastsDoNotSpendAnything() {
        var caster = token("caster");
        var target = token("target");
        Ability a = ability();
        a.getChecks().setAttackRoll(true);
        assertFalse(CastResolver.cast(caster, CastResolver.of(a, id -> null), List.of(target), 10, 0).isSuccess());
        assertFalse(CastResolver.cast(caster, CastResolver.of(a, id -> null), List.of(target), 100, 0,
            List.of(), a.getChecks(), Map.of(target, new CastChecks.Rolls(20, 5, 10, 1, 0, 0))).isSuccess());
        assertEquals(20, TokenSupport.combatOf(caster).getCurrentMana());
        assertEquals(0, TokenSupport.combatOf(caster).cooldownFor("flame"));
    }
    @Test public void configuredChecksSurviveJsonAndChargeBoosting() throws Exception {
        var spell = new com.dnd.model.magic.Spell();
        spell.getChecks().setAttackRoll(true);
        spell.getChecks().setSavingThrow("agility");
        spell.getChecks().setSaveDc(17);
        var mapper = JsonMappers.create();
        var back = mapper.readValue(mapper.writeValueAsString(spell), com.dnd.model.magic.Spell.class);
        assertTrue(back.getChecks().isAttackRoll());
        assertEquals("agility", CastResolver.of(back).onCharge(3).getChecks().getSavingThrow());
        assertEquals(Integer.valueOf(17), back.getChecks().getSaveDc());
    }

    @Test public void selfTargetChecksAreValidatedBeforeCostsAndSuccessfulSaveLeavesNoMarker() {
        var caster = token("caster");
        Ability a = ability();
        a.setRange(0);
        a.setRadius(0);
        a.setDamage(null);
        a.setDescription("A condition.");
        a.getChecks().setSavingThrow("wisdom");
        a.getChecks().setSuccessfulSaveDamage(CastChecks.SavedDamage.NONE);
        assertFalse(CastResolver.cast(caster, CastResolver.of(a, id -> null), List.of(), 0, 0).isSuccess());
        assertEquals(20, TokenSupport.combatOf(caster).getCurrentMana());
        var outcome = CastResolver.cast(caster, CastResolver.of(a, id -> null), List.of(), 0, 0,
            List.of(), a.getChecks(), Map.of(caster, new CastChecks.Rolls(1, 0, 0, 15, 0, 15)));
        assertTrue(outcome.isSuccess());
        assertTrue(TokenSupport.combatOf(caster).getActiveEffects().isEmpty());
        assertEquals(18, TokenSupport.combatOf(caster).getCurrentMana());
    }
}
