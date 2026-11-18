package com.dnd.model.combat;

import com.dnd.model.creature.Monster;
import com.dnd.model.world.map.MapObject;
import com.dnd.model.world.map.MonsterToken;
import com.dnd.model.world.map.TokenSupport;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class InitiativeTrackerTest {

    private InitiativeTracker tracker;
    private MonsterToken fast;
    private MonsterToken middle;
    private MonsterToken slow;

    private MonsterToken monster(String name, int initiative) {
        Monster monster = new Monster();
        monster.setId(name);
        monster.setName(name);
        MonsterToken token = new MonsterToken(monster);
        TokenSupport.combatOf(token).setInitiative(initiative);
        TokenSupport.combatOf(token).setMaxHitPoints(10);
        TokenSupport.combatOf(token).setCurrentHitPoints(10);
        return token;
    }

    @Before
    public void setUp() {
        tracker = new InitiativeTracker();
        fast = monster("Fast", 20);
        middle = monster("Middle", 12);
        slow = monster("Slow", 3);
        tracker.setCombatants(List.of(slow, fast, middle));
    }

    @Test
    public void ordersByInitiativeHighestFirst() {
        List<MapObject> order = tracker.order();
        assertEquals(fast, order.get(0));
        assertEquals(middle, order.get(1));
        assertEquals(slow, order.get(2));
        assertEquals(fast, tracker.current());
    }

    @Test
    public void turnOrderCyclesForeverAndCountsRounds() {
        assertEquals(fast, tracker.current());
        assertEquals(1, tracker.round());

        assertEquals(middle, tracker.next());
        assertEquals(slow, tracker.next());
        assertEquals(fast, tracker.next());
        assertEquals("wrapping back to the top starts a new round", 2, tracker.round());
        assertEquals(middle, tracker.next());
    }

    @Test
    public void savedInitiativeResumesOnTheSameTokenAndRound() throws Exception {
        tracker.restore(middle, 4);
        var mapper = com.dnd.data.JsonMappers.create();
        var map = new com.dnd.model.world.map.GameMap("arena", "Arena", 1, 1);
        map.setInitiativePosition(tracker.position());
        var reopened = mapper.readValue(mapper.writeValueAsString(map), map.getClass());
        var reopenedToken = monster("Middle again", 12);
        reopenedToken.setInstanceId(middle.getInstanceId());
        var restored = new InitiativeTracker();
        restored.setCombatants(List.of(fast, reopenedToken, slow));
        restored.restorePosition(reopened.getInitiativePosition());
        assertSame(reopenedToken, restored.current());
        assertEquals(4, restored.round());
    }

    @Test
    public void playerDistancesSkipDeadTokensAndWrapAround() {
        var pc = new com.dnd.model.character.PlayerCharacter();
        pc.setId("hero");
        pc.setName("Hero");
        var hero = new com.dnd.model.world.map.PlayerToken(pc);
        pc.setMaxHitPoints(10);
        pc.setCurrentHitPoints(10);
        TokenSupport.combatOf(hero).setInitiative(30);
        TokenSupport.combatOf(slow).setDead(true);
        tracker.setCombatants(List.of(hero, fast, middle, slow));
        tracker.restore(fast, 3);
        assertEquals(java.util.Map.of("hero", 2), tracker.playerDistances());
        tracker.restore(hero, 4);
        assertEquals(java.util.Map.of("hero", 0), tracker.playerDistances());
    }

    @Test
    public void deadCombatantsAreSkippedButStayInTheRoster() {
        TokenSupport.combatOf(middle).setDead(true);
        assertEquals(slow, tracker.next());
        assertEquals("the dead stay on the map as bodies", 3, tracker.order().size());
    }

    @Test
    public void upcomingShowsOnlyTheNextFewLivingCombatants() {
        assertEquals(List.of(middle, slow), tracker.upcoming(2));

        TokenSupport.combatOf(middle).setDead(true);
        assertEquals(List.of(slow), tracker.upcoming(2));
    }

    @Test
    public void upcomingNeverRepeatsTheCurrentCombatant() {
        for (MapObject token : tracker.upcoming(5)) {
            assertNotEquals(tracker.current(), token);
        }
    }

    @Test
    public void removingATokenDropsItFromTheOrder() {
        tracker.remove(fast);
        assertEquals(2, tracker.order().size());
        assertEquals(middle, tracker.current());
    }

    @Test
    public void currentIsNullOnlyWhenNothingIsAlive() {
        for (MapObject token : tracker.order()) TokenSupport.combatOf(token).setDead(true);
        assertNull(tracker.current());
        assertNull(tracker.next());
    }

    @Test
    public void rollingInitiativeReordersAndRestartsAtRoundOne() {
        tracker.next();
        tracker.next();
        tracker.next();
        assertTrue(tracker.round() > 1);

        tracker.rollAll();
        assertEquals(1, tracker.round());
        List<MapObject> order = tracker.order();
        for (int i = 1; i < order.size(); i++) {
            assertTrue("order must stay sorted by initiative",
                TokenSupport.combatOf(order.get(i - 1)).getInitiative()
                    >= TokenSupport.combatOf(order.get(i)).getInitiative());
        }
    }

    @Test
    public void tokensExcludedFromInitiativeAreNotInTheOrder() {
        MonsterToken scenery = monster("Statue", 5);
        TokenSupport.combatOf(scenery).setInInitiative(false);
        tracker.setCombatants(List.of(fast, scenery));
        assertEquals(1, tracker.order().size());
        assertEquals(fast, tracker.order().get(0));
    }

    @Test
    public void emptyRosterIsHandledGracefully() {
        InitiativeTracker empty = new InitiativeTracker();
        empty.setCombatants(List.of());
        assertNull(empty.current());
        assertNull(empty.next());
        assertTrue(empty.upcoming(3).isEmpty());
    }
}
