package com.dnd.model.world.map;

import com.dnd.model.creature.Monster;

import java.util.ArrayList;
import java.util.List;

/**
 * A live {@link Monster} placed on a {@link GameMap}.
 *
 * <p>Implements {@link MapActor} so the monster can
 * {@link #moveTo move}, {@link #pickUp pick up items}, and {@link #drop drop items}.</p>
 */
public class MonsterToken implements MapActor {

    /** Discriminator for {@link MapObject} polymorphic (de)serialization. */
    private String kind = "monster";
    private Monster monster;
    private Position position;
    private List<MapItemToken> inventory = new ArrayList<>();
    private CombatState combat;
    private String instanceId = java.util.UUID.randomUUID().toString();
    @Override public String getInstanceId() { return instanceId; }
    @Override public void setInstanceId(String value) { instanceId = value; }

    public MonsterToken() {
    }

    public MonsterToken(Monster monster) {
        this.monster = monster;
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Monster getMonster() {
        return monster;
    }

    public void setMonster(Monster monster) {
        this.monster = monster;
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
        return "M";
    }

    @Override
    public String toString() {
        String label = monster != null ? monster.getName() : "Monster";
        return label + " [M]" + (position != null ? " @ " + position : "");
    }
}

