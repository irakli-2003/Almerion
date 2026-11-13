package com.dnd.model.world.map;

import java.util.List;

/**
 * A {@link MapObject} that can act on the map: move to a new cell, pick up
 * {@link MapItemToken}s, and drop them.
 *
 * <p>All mutating operations require a reference to the {@link GameMap} so
 * that the grid's occupant lists remain consistent.</p>
 *
 * <p>Default implementations are provided for every action so that concrete
 * tokens (PlayerToken, NpcToken, …) only need to implement
 * {@link #getInventory()}.</p>
 */
public interface MapActor extends MapObject {
    String getInstanceId();
    void setInstanceId(String instanceId);

    /** The items this actor is currently carrying (mutable list). */
    List<MapItemToken> getInventory();

    // ── Movement ────────────────────────────────────────────────────────────

    /**
     * Moves this actor to {@code (x, y)} on {@code map}.
     *
     * @throws IndexOutOfBoundsException if the target position is outside
     *                                   the map boundaries
     * @throws IllegalStateException     if the target cell is impassable
     */
    default void moveTo(GameMap map, int x, int y) {
        map.moveObject(this, x, y);
    }

    /**
     * Moves this actor by {@code (dx, dy)} relative to the current position.
     *
     * @throws IllegalStateException     if this actor is not on the map
     * @throws IndexOutOfBoundsException if the resulting position is outside
     *                                   the map boundaries
     * @throws IllegalStateException     if the target cell is impassable
     */
    default void moveBy(GameMap map, int dx, int dy) {
        Position pos = getPosition();
        if (pos == null) {
            throw new IllegalStateException(this + " is not currently placed on the map.");
        }
        moveTo(map, pos.getX() + dx, pos.getY() + dy);
    }

    // ── Item interactions ───────────────────────────────────────────────────

    /**
     * Picks up a {@link MapItemToken} from the same cell as this actor.
     *
     * <p>The token must be on the map, on the same cell as the actor, and
     * its underlying item must be {@linkplain com.dnd.model.interfaces.Pickable
     * pickable}.</p>
     *
     * @throws IllegalStateException    if this actor is not on the map
     * @throws IllegalArgumentException if the token is not on the same cell,
     *                                  not found on the map grid, or is not
     *                                  pickable
     */
    default void pickUp(GameMap map, MapItemToken token) {
        Position myPos = getPosition();
        if (myPos == null) {
            throw new IllegalStateException(this + " is not currently placed on the map.");
        }
        Position itemPos = token.getPosition();
        if (itemPos == null || !itemPos.equals(myPos)) {
            throw new IllegalArgumentException(
                token + " is not on the same cell as " + this + ". "
                + "Actor is at " + myPos + ", item is at " + itemPos + ".");
        }
        if (!token.isPickable()) {
            throw new IllegalArgumentException(
                (token.getItem() != null ? token.getItem().getName() : "Item")
                + " cannot be picked up.");
        }
        boolean removed = map.removeObject(token);
        if (!removed) {
            throw new IllegalArgumentException(
                token + " was not found on the map grid.");
        }
        token.setPosition(null);
        getInventory().add(token);
    }

    /**
     * Picks up all pickable {@link MapItemToken}s from the actor's current
     * cell in one call.
     *
     * @return the number of items picked up
     * @throws IllegalStateException if this actor is not on the map
     */
    default int pickUpAll(GameMap map) {
        Position myPos = getPosition();
        if (myPos == null) {
            throw new IllegalStateException(this + " is not currently placed on the map.");
        }
        GridCell cell = map.getCell(myPos.getX(), myPos.getY());
        // snapshot to avoid ConcurrentModificationException
        List<MapObject> occupants = new java.util.ArrayList<>(cell.getOccupants());
        int count = 0;
        for (MapObject obj : occupants) {
            if (obj instanceof MapItemToken) {
                MapItemToken token = (MapItemToken) obj;
                if (token.isPickable()) {
                    pickUp(map, token);
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Drops a carried {@link MapItemToken} onto the actor's current cell.
     *
     * @throws IllegalStateException    if this actor is not on the map
     * @throws IllegalArgumentException if the actor is not carrying the token
     */
    default void drop(GameMap map, MapItemToken token) {
        if (!getInventory().remove(token)) {
            throw new IllegalArgumentException(
                this + " is not carrying " + token + ".");
        }
        Position myPos = getPosition();
        if (myPos == null) {
            // re-add to inventory before throwing so state remains consistent
            getInventory().add(token);
            throw new IllegalStateException(this + " is not currently placed on the map.");
        }
        map.placeObject(token, myPos.getX(), myPos.getY());
    }

    /**
     * Drops all carried items onto the actor's current cell.
     *
     * @throws IllegalStateException if this actor is not on the map
     */
    default void dropAll(GameMap map) {
        // snapshot so we don't modify the list we're iterating
        List<MapItemToken> carried = new java.util.ArrayList<>(getInventory());
        for (MapItemToken token : carried) {
            drop(map, token);
        }
    }
}

