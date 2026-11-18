package com.dnd.model.combat;

import com.dnd.model.world.map.CombatState;
import com.dnd.model.world.map.MapObject;
import com.dnd.model.world.map.PlayerToken;
import com.dnd.model.world.map.MapActor;
import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.TokenSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turn order for a battle: an endless cycle through the living combatants, highest
 * initiative first.
 *
 * <p>The cycle is computed rather than stored as a queue so that adding a reinforcement
 * mid-fight, killing something, or re-rolling initiative all take effect immediately
 * without the DM having to rebuild anything. Dead combatants are skipped but deliberately
 * kept in the roster, because they stay on the map as bodies and can be revived.</p>
 */
public class InitiativeTracker {

    private final List<MapObject> roster = new ArrayList<>();
    private MapObject current;
    private int round = 1;

    /**
     * Replaces the roster with the given tokens, ordered by initiative.
     *
     * <p>Ties are broken by dexterity and then by name so the order is stable across
     * rebuilds; an order that reshuffles itself every time a token is added would be
     * unusable at the table.</p>
     */
    public void setCombatants(List<MapObject> tokens) {
        roster.clear();
        if (tokens != null) {
            for (MapObject token : tokens) {
                if (token != null && TokenSupport.combatOf(token).isInInitiative()) roster.add(token);
            }
        }
        roster.sort(Comparator
            .comparingInt((MapObject t) -> TokenSupport.combatOf(t).getInitiative()).reversed()
            .thenComparing(t -> {
                var stats = TokenSupport.statsOf(t);
                return stats == null ? 0 : -stats.getDexterity();
            })
            .thenComparing(TokenSupport::nameOf)
            .thenComparing(t -> t instanceof MapActor actor ? java.util.Objects.toString(actor.getInstanceId(), "") : ""));
        if (current == null || !roster.contains(current)) current = firstLiving();
    }

    /** The full roster in turn order, including the dead, for display in the roster panel. */
    public List<MapObject> order() {
        return List.copyOf(roster);
    }

    public MapObject current() {
        if (current == null || !roster.contains(current) || !alive(current)) current = firstLiving();
        return current;
    }

    public int round() {
        return round;
    }

    /** Rolls fresh initiative for every combatant and restarts the order at the top. */
    public void rollAll() {
        for (MapObject token : roster) {
            TokenSupport.combatOf(token).setInitiative(TokenSupport.rollInitiative(token));
        }
        setCombatants(new ArrayList<>(roster));
        round = 1;
        current = firstLiving();
    }

    /**
     * Rolls initiative for everything the DM is running, leaving player tokens alone.
     *
     * <p>Players roll their own dice at the table and the DM types the results in, so
     * overwriting them with computer rolls would throw away the numbers the table just
     * rolled. The order is rebuilt afterwards so the typed and rolled values interleave
     * correctly.</p>
     *
     * @return how many non-player combatants were rolled for
     */
    public int rollNonPlayers() {
        int rolled = 0;
        for (MapObject token : roster) {
            if (token instanceof PlayerToken) continue;
            TokenSupport.combatOf(token).setInitiative(TokenSupport.rollInitiative(token));
            rolled++;
        }
        setCombatants(new ArrayList<>(roster));
        round = 1;
        current = firstLiving();
        return rolled;
    }

    /**
     * Advances to the next living combatant, wrapping around and incrementing the round
     * counter. Returns null only when nothing is left alive.
     */
    public MapObject next() {
        if (roster.isEmpty()) return current = null;
        int start = roster.indexOf(current());
        if (start < 0) return current = firstLiving();
        for (int step = 1; step <= roster.size(); step++) {
            int index = (start + step) % roster.size();
            if (index == 0) round++;
            MapObject candidate = roster.get(index);
            if (alive(candidate)) {
                beginTurn(candidate);
                return current = candidate;
            }
        }
        return current = null;
    }

    /**
     * Everything that happens the moment a creature's turn comes round: movement is
     * refreshed, lingering effects tick and possibly wear off, and recharging spells and
     * abilities come one round closer to being usable.
     *
     * <p>Effects and cooldowns are counted per the bearer's own turn rather than per round
     * of the battle, which is what the table expects - "frost for 3 turns" means the frozen
     * creature suffers three of <em>its</em> turns, no matter where it sits in the order.</p>
     */
    private void beginTurn(MapObject token) {
        CombatState state = TokenSupport.combatOf(token);
        state.resetMovement();
        lastTurnLog = state.tickEffects(TokenSupport.nameOf(token));
        state.tickCooldowns();
    }

    private List<String> lastTurnLog = new ArrayList<>();

    /**
     * What the effects running on the current combatant did as its turn began - damage
     * taken, healing received, effects that wore off. Empty when nothing happened.
     */
    public List<String> lastTurnLog() {
        return List.copyOf(lastTurnLog);
    }

    /**
     * The next {@code count} combatants after the current one, so the panel can show a short
     * "coming up" stack instead of an infinite list.
     */
    public List<MapObject> upcoming(int count) {
        List<MapObject> result = new ArrayList<>();
        if (roster.isEmpty() || count <= 0) return result;
        int start = roster.indexOf(current());
        if (start < 0) start = 0;
        for (int step = 1; step <= roster.size() && result.size() < count; step++) {
            MapObject candidate = roster.get((start + step) % roster.size());
            if (candidate != current && alive(candidate)) result.add(candidate);
        }
        return result;
    }

    /** Drops a token from the order, e.g. when the DM removes it from the map. */
    public void remove(MapObject token) {
        roster.remove(token);
        if (current == token) current = firstLiving();
    }

    /** Jumps back to a recorded turn without ticking effects (used by time rewind). */
    public void restore(MapObject actor, int round) {
        this.current = actor;
        this.round = Math.max(1, round);
    }

    public GameMap.InitiativePosition position() {
        return new GameMap.InitiativePosition(current() instanceof MapActor actor ? actor.getInstanceId() : null, round);
    }

    public void restorePosition(GameMap.InitiativePosition position) {
        if (position == null) return;
        var saved = roster.stream().filter(t -> t instanceof MapActor actor && position.actorId() != null
            && position.actorId().equals(actor.getInstanceId())).findFirst().orElse(null);
        restore(saved == null ? firstLiving() : saved, position.round());
    }

    public java.util.Map<String, Integer> playerDistances() {
        var result = new java.util.LinkedHashMap<String, Integer>();
        int start = roster.indexOf(current());
        if (start < 0) return result;
        int distance = 0;
        for (int step = 0; step < roster.size(); step++) {
            var token = roster.get((start + step) % roster.size());
            if (!alive(token)) continue;
            if (token instanceof PlayerToken pc && pc.getCharacter() != null && pc.getCharacter().getId() != null) {
                result.putIfAbsent(pc.getCharacter().getId(), distance);
            }
            distance++;
        }
        return result;
    }

    private MapObject firstLiving() {
        for (MapObject token : roster) {
            if (alive(token)) return token;
        }
        return null;
    }

    private boolean alive(MapObject token) {
        CombatState state = TokenSupport.combatOf(token);
        return state.isActingThisRound();
    }
}
