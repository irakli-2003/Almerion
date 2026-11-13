package com.dnd.model.world.map;

import com.dnd.model.character.PlayerCharacter;

import java.util.ArrayList;
import java.util.List;

/**
 * A live {@link PlayerCharacter} placed on a {@link GameMap}.
 *
 * <p>Implements {@link MapActor} so the player can
 * {@link #moveTo move}, {@link #pickUp pick up items}, and {@link #drop drop items}.</p>
 */
public class PlayerToken implements MapActor {

    /** Discriminator for {@link MapObject} polymorphic (de)serialization. */
    private String kind = "player";
    private PlayerCharacter character;
    private Position position;
    private List<MapItemToken> inventory = new ArrayList<>();
    private CombatState combat;
    private String instanceId = java.util.UUID.randomUUID().toString();
    @Override public String getInstanceId() { return instanceId; }
    @Override public void setInstanceId(String value) { instanceId = value; }

    public PlayerToken() {
    }

    public PlayerToken(PlayerCharacter character) {
        this.character = character;
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public PlayerCharacter getCharacter() {
        return character;
    }

    public void setCharacter(PlayerCharacter character) {
        this.character = character;
    }

    @Override public Position getPosition()               { return position; }
    @Override public void setPosition(Position position) { this.position = position; }
    @Override public List<MapItemToken> getInventory()   { return inventory; }
    public void setInventory(List<MapItemToken> inventory) {
        this.inventory = inventory != null ? inventory : new ArrayList<>();
    }

    public CombatState getCombat() {
        return combat;
    }

    public void setCombat(CombatState combat) {
        this.combat = combat;
    }

    // ── MapObject / Printable ───────────────────────────────────────────────

    @Override
    public String getSymbol() {
        return "P";
    }

    @Override
    public String toString() {
        String label = character != null ? character.getName() : "Player";
        return label + " [P]" + (position != null ? " @ " + position : "");
    }
}

