package com.dnd.model.combat;

import com.dnd.model.world.map.CombatState;
import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.MapObject;
import com.dnd.model.world.map.Position;
import com.dnd.model.world.map.TokenSupport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Remembers what the battlefield looked like at the start of each turn so time can be
 * turned back - the Dungeonland rabbit's pocket watch rewinds the fight to its own
 * previous turn, undoing every hit, spell, effect and step taken since.
 *
 * <p>Only creatures are recorded (positions and full combat state); walls and terrain are
 * the DM's to edit and are left alone. Tokens added after the snapshot was taken are
 * removed on rewind; tokens removed since are put back, unless explicitly preserved.</p>
 */
public class TurnHistory {

    /** How many turn starts to keep; plenty for a round or two of a big fight. */
    public static final int CAPACITY = 60;

    private final Deque<Snapshot> snapshots = new ArrayDeque<>();

    private static final class Entry {
        final MapObject token;
        final int x;
        final int y;
        final CombatState state;

        Entry(MapObject token, int x, int y, CombatState state) {
            this.token = token;
            this.x = x;
            this.y = y;
            this.state = state;
        }
    }

    /** The board at the start of one creature's turn. */
    public static final class Snapshot {
        private final MapObject actor;
        private final int round;
        private final List<Entry> entries;

        private Snapshot(MapObject actor, int round, List<Entry> entries) {
            this.actor = actor;
            this.round = round;
            this.entries = entries;
        }

        public MapObject getActor() {
            return actor;
        }

        public int getRound() {
            return round;
        }

        public List<MapObject> getTokens() {
            return entries.stream().map(entry -> entry.token).toList();
        }
    }

    /** Records the board as it is right now, as the start of {@code actor}'s turn. */
    public void record(GameMap map, MapObject actor, int round) {
        List<Entry> entries = new ArrayList<>();
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                for (MapObject token : map.getCell(x, y).getOccupants()) {
                    if (!TokenSupport.isCreature(token)) continue;
                    entries.add(new Entry(token, x, y, TokenSupport.combatOf(token).copy()));
                }
            }
        }
        snapshots.addLast(new Snapshot(actor, round, entries));
        while (snapshots.size() > CAPACITY) snapshots.removeFirst();
    }

    public boolean isEmpty() {
        return snapshots.isEmpty();
    }

    public void clear() {
        snapshots.clear();
    }

    /**
     * The start of {@code actor}'s previous turn: skips the snapshot of its current turn
     * (the most recent one it owns) and returns the one before. Null when there is none.
     */
    public Snapshot previousTurnOf(MapObject actor) {
        return previousTurnOf(actor, true);
    }

    /** Out-of-turn casts use the most recent recorded turn rather than skipping it. */
    public Snapshot previousTurnOf(MapObject actor, boolean turnInProgress) {
        boolean skippedCurrent = !turnInProgress;
        Iterator<Snapshot> it = snapshots.descendingIterator();
        while (it.hasNext()) {
            Snapshot snapshot = it.next();
            if (snapshot.actor != actor) continue;
            if (!skippedCurrent) {
                skippedCurrent = true;
                continue;
            }
            return snapshot;
        }
        return null;
    }

    /**
     * Puts every creature back where and how it was in {@code snapshot}, and forgets every
     * later snapshot (they describe a future that no longer happened). The snapshot itself
     * is kept, so it becomes the actor's "current turn" again.
     */
    public void restore(GameMap map, Snapshot snapshot, InitiativeTracker tracker) {
        restore(map, snapshot, tracker, java.util.Set.of());
    }

    /** Successful Arcana checks preserve the token's current state, position, or absence. */
    public void restore(GameMap map, Snapshot snapshot, InitiativeTracker tracker,
                        java.util.Set<MapObject> preserved) {
        List<MapObject> present = new ArrayList<>();
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                for (MapObject token : map.getCell(x, y).getOccupants()) {
                    if (TokenSupport.isCreature(token)) present.add(token);
                }
            }
        }
        List<MapObject> recorded = new ArrayList<>();
        for (Entry entry : snapshot.entries) recorded.add(entry.token);
        for (MapObject token : present) {
            if (!recorded.contains(token) && !preserved.contains(token)) map.removeObject(token);
        }
        for (Entry entry : snapshot.entries) {
            if (preserved.contains(entry.token)) continue;
            map.removeObject(entry.token);
            map.getCell(entry.x, entry.y).addOccupant(entry.token);
            entry.token.setPosition(new Position(entry.x, entry.y));
            TokenSupport.setCombat(entry.token, entry.state.copy());
        }
        while (!snapshots.isEmpty() && snapshots.peekLast() != snapshot) snapshots.removeLast();
        if (tracker != null) tracker.restore(snapshot.actor, snapshot.round);
    }
}
