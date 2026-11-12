package com.dnd.model.world.map;

import com.dnd.model.creature.Npc;

import java.util.ArrayList;
import java.util.List;

/**
 * A live {@link Npc} placed on a {@link GameMap}.
 *
 * <p>Implements {@link MapActor} so the NPC can
 * {@link #moveTo move}, {@link #pickUp pick up items}, and {@link #drop drop items}.</p>
 */
public class NpcToken implements MapActor {

    /** Discriminator for {@link MapObject} polymorphic (de)serialization. */
    private String kind = "npc";
    private Npc npc;
    private Position position;
    private List<MapItemToken> inventory = new ArrayList<>();
    private CombatState combat;
    private String instanceId = java.util.UUID.randomUUID().toString();
    @Override public String getInstanceId() { return instanceId; }
    @Override public void setInstanceId(String value) { instanceId = value; }

    public NpcToken() {
    }

    public NpcToken(Npc npc) {
        this.npc = npc;
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Npc getNpc() {
        return npc;
    }

    public void setNpc(Npc npc) {
        this.npc = npc;
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
        return "N";
    }

    @Override
    public String toString() {
        String label = npc != null ? npc.getName() : "NPC";
        return label + " [N]" + (position != null ? " @ " + position : "");
    }
}

