package com.dnd.model.world.map;

import com.dnd.model.creature.Beast;

import java.util.ArrayList;
import java.util.List;

/**
 * A live {@link Beast} placed on a {@link GameMap}.
 *
 * <p>Implements {@link MapActor} so the beast can
 * {@link #moveTo move}, {@link #pickUp pick up items}, and {@link #drop drop items}.</p>
 */
public class BeastToken implements MapActor {

    /** Discriminator for {@link MapObject} polymorphic (de)serialization. */
    private String kind = "beast";
    private Beast beast;
    private Position position;
    private List<MapItemToken> inventory = new ArrayList<>();
    private CombatState combat;
    private String instanceId = java.util.UUID.randomUUID().toString();
    @Override public String getInstanceId() { return instanceId; }
    @Override public void setInstanceId(String value) { instanceId = value; }

    public BeastToken() {
    }

    public BeastToken(Beast beast) {
        this.beast = beast;
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Beast getBeast() {
        return beast;
    }

    public void setBeast(Beast beast) {
        this.beast = beast;
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
        return "B";
    }

    @Override
    public String toString() {
        String label = beast != null ? beast.getName() : "Beast";
        return label + " [B]" + (position != null ? " @ " + position : "");
    }
}

