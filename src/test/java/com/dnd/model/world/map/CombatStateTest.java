package com.dnd.model.world.map;

import org.junit.Test;

import static org.junit.Assert.*;

public class CombatStateTest {

    private CombatState withHp(int max) {
        CombatState state = new CombatState(max);
        return state;
    }

    @Test
    public void constructorStartsAtFullHealth() {
        CombatState state = withHp(20);
        assertEquals(20, state.getMaxHitPoints());
        assertEquals(20, state.getCurrentHitPoints());
        assertFalse(state.isDowned());
        assertFalse(state.isDead());
    }

    @Test
    public void damageClampsAtZeroAndKnocksDownRatherThanKilling() {
        CombatState state = withHp(10);
        state.applyDamage(25);
        assertEquals(0, state.getCurrentHitPoints());
        assertTrue(state.isDowned());
        assertFalse(state.isDead());
    }

    @Test
    public void healingAboveZeroRevivesAndClearsFailedSaves() {
        CombatState state = withHp(10);
        state.applyDamage(10);
        state.failDeathSave();
        assertTrue(state.isDowned());

        state.heal(4);
        assertEquals(4, state.getCurrentHitPoints());
        assertFalse(state.isDowned());
        assertEquals(0, state.getDeathSaveFailures());
    }

    @Test
    public void healingCannotExceedMaximum() {
        CombatState state = withHp(10);
        state.applyDamage(6);
        state.heal(100);
        assertEquals(10, state.getCurrentHitPoints());
    }

    @Test
    public void threeFailedSavesKillAndCountDownThreeTwoOne() {
        CombatState state = withHp(10);
        state.applyDamage(10);
        assertEquals(3, state.remainingDeathSaves());

        state.failDeathSave();
        assertEquals(2, state.remainingDeathSaves());
        assertTrue(state.isDowned());

        state.failDeathSave();
        assertEquals(1, state.remainingDeathSaves());
        assertTrue(state.isDowned());

        state.failDeathSave();
        assertEquals(0, state.remainingDeathSaves());
        assertTrue(state.isDead());
        assertFalse(state.isDowned());
    }

    @Test
    public void successfulSavesAreIndependentFromFailures() {
        CombatState state = withHp(10);
        state.applyDamage(10);
        state.failDeathSave();
        state.failDeathSave();
        state.succeedDeathSave();
        assertEquals(1, state.remainingDeathSaves());
        assertEquals(1, state.getDeathSaveSuccesses());
        assertFalse(state.isDead());
    }

    @Test
    public void deathSaveCountNeverGoesOutOfRange() {
        CombatState state = withHp(10);
        state.setDeathSaveFailures(-5);
        assertEquals(0, state.getDeathSaveFailures());
        state.setDeathSaveFailures(99);
        assertEquals(CombatState.MAX_DEATH_SAVES, state.getDeathSaveFailures());
        assertTrue(state.isDead());
    }

    @Test
    public void killingSetsDeadAndZeroHitPoints() {
        CombatState state = withHp(30);
        state.setDead(true);
        assertTrue(state.isDead());
        assertEquals(0, state.getCurrentHitPoints());
        assertFalse(state.isActingThisRound());
    }

    @Test
    public void revivingRestoresFullHealth() {
        CombatState state = withHp(30);
        state.setDead(true);
        state.revive();
        assertFalse(state.isDead());
        assertFalse(state.isDowned());
        assertEquals(30, state.getCurrentHitPoints());
        assertTrue(state.isActingThisRound());
    }

    @Test public void rolledRevivalClearsBothCountersAndClampsToMaximumHp() {
        CombatState state = withHp(3);
        state.applyDamage(3);
        state.failDeathSave();
        state.succeedDeathSave();
        assertEquals(1, state.copy().getDeathSaveSuccesses());
        state.revive(4);
        assertEquals(3, state.getCurrentHitPoints());
        assertEquals(0, state.getDeathSaveSuccesses());
        assertEquals(0, state.getDeathSaveFailures());
        assertFalse(state.isDowned());
        assertFalse(state.isDead());
        assertThrows(IllegalArgumentException.class, () -> state.revive(5));
        assertThrows(IllegalArgumentException.class, () -> state.revive(0));
    }

    @Test
    public void loweringMaxHitPointsPullsCurrentDownWithIt() {
        CombatState state = withHp(30);
        state.setMaxHitPoints(12);
        assertEquals(12, state.getCurrentHitPoints());
    }

    @Test
    public void manaAndGoldStayWithinBounds() {
        CombatState state = new CombatState();
        state.setMaxMana(10);
        state.setCurrentMana(50);
        assertEquals(10, state.getCurrentMana());
        state.setCurrentMana(-3);
        assertEquals(0, state.getCurrentMana());
        state.setGold(-7);
        assertEquals(0, state.getGold());
    }

    @Test
    public void healthFractionIsSafeWhenNoMaximumIsSet() {
        assertEquals(0.0, new CombatState().healthFraction(), 0.0001);
        CombatState state = withHp(8);
        state.applyDamage(4);
        assertEquals(0.5, state.healthFraction(), 0.0001);
    }
}
