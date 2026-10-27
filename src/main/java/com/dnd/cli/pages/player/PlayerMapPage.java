package com.dnd.cli.pages.player;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.core.CommandSpec;
import com.dnd.cli.core.ConsoleIO;
import com.dnd.cli.core.Page;
import com.dnd.cli.pages.MapRenderer;
import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.combat.Damage;
import com.dnd.model.combat.DiceRoll;
import com.dnd.model.combat.Effect;
import com.dnd.model.magic.Spell;
import com.dnd.model.world.Dice;
import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.GridCell;
import com.dnd.model.world.map.MapItemToken;
import com.dnd.model.world.map.MapObject;
import com.dnd.model.world.map.PlayerToken;
import com.dnd.model.world.map.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Player-mode view of a single {@link GameMap}: shows only the area within
 * the character's vision radius (fog-of-war outside it), and lets the
 * player move (bound by their race's speed), pick up pickable items from
 * their current cell, drop carried items, and cast their active spells/
 * abilities at a target cell within range.
 *
 * <p>Unlike the DM's {@link com.dnd.cli.pages.MapSessionPage}, this page
 * only ever acts on the player's own {@link PlayerToken} - it exposes no
 * way to place/move/remove other tokens.</p>
 */
public class PlayerMapPage implements Page {
    private static final Random RANDOM = new Random();

    private final GameMap map;
    private final PlayerToken token;
    private final CampaignRepositories repos;
    private final int visionRadius;
    private final Page parent;

    public PlayerMapPage(GameMap map, PlayerToken token, CampaignRepositories repos, int visionRadius, Page parent) {
        this.map = map;
        this.token = token;
        this.repos = repos;
        this.visionRadius = visionRadius;
        this.parent = parent;
    }

    // ── Page ─────────────────────────────────────────────────────────────────

    @Override
    public String getTitle() {
        return "Map: " + (map.getName() != null ? map.getName() : map.getId());
    }

    @Override
    public String getBody() {
        StringBuilder sb = new StringBuilder();
        sb.append(MapRenderer.renderWithFog(map, token.getPosition(), visionRadius));
        sb.append("\n\n");
        sb.append("Your position: ").append(token.getPosition())
          .append("   Speed: ").append(speedCells()).append(" squares/turn\n");
        sb.append("Symbols: P=Player  N=NPC  M=Monster  B=Beast  i=Item  #=Wall  .=Open  *=Effect  (blank)=Unseen");
        return sb.toString();
    }

    @Override
    public Page getParent() {
        return parent;
    }

    @Override
    public List<CommandSpec> getCommands() {
        return Arrays.asList(
            new CommandSpec("stats",    "View your character's stats",          this::handleStats),
            new CommandSpec("move",     "Move to a new cell",                    this::handleMove),
            new CommandSpec("pickup",   "Pick up a pickable item on your cell",   this::handlePickup),
            new CommandSpec("drop",     "Drop a carried item",                   this::handleDrop),
            new CommandSpec("carrying", "List items you're currently carrying",  this::handleCarrying),
            new CommandSpec("cast",     "Cast a spell/ability at a target",      this::handleCast)
        );
    }

    // ── Command handlers ──────────────────────────────────────────────────────

    private Page handleStats(CliSession session) {
        ConsoleIO console = session.getConsole();
        PlayerCharacter pc = token.getCharacter();
        if (pc == null) {
            console.println("No character data available.");
            return this;
        }
        PlayerModeSupport.printStats(console, pc, repos);
        return this;
    }

    private Page handleMove(CliSession session) {
        ConsoleIO console = session.getConsole();
        Position current = token.getPosition();
        if (current == null) {
            console.println("You are not currently placed on this map.");
            return this;
        }

        console.print("x (0-" + (map.getWidth() - 1) + "): ");
        int x = readInt(console);
        console.print("y (0-" + (map.getHeight() - 1) + "): ");
        int y = readInt(console);
        if (x < 0 || x >= map.getWidth() || y < 0 || y >= map.getHeight()) {
            console.println("Coordinates out of bounds.");
            return this;
        }

        int distance = Math.max(Math.abs(x - current.getX()), Math.abs(y - current.getY()));
        int speed = speedCells();
        if (distance > speed) {
            console.println("Too far! Your speed only lets you move " + speed
                + " square(s) per turn (that would be " + distance + ").");
            return this;
        }

        try {
            token.moveTo(map, x, y);
            save(console);
        } catch (Exception e) {
            console.println("Cannot move there: " + e.getMessage());
        }
        return this;
    }

    private Page handlePickup(CliSession session) {
        ConsoleIO console = session.getConsole();
        List<MapItemToken> items = itemsOnCurrentCell();
        if (items.isEmpty()) {
            console.println("There is nothing pickable here.");
            return this;
        }

        console.println("Items here:");
        for (int i = 0; i < items.size(); i++) {
            console.println("  " + (i + 1) + ". " + items.get(i));
        }
        console.print("Number to pick up (blank to cancel): ");
        int idx = readOptionalIndex(console, items.size());
        if (idx < 0) {
            return this;
        }

        try {
            MapItemToken picked = items.get(idx);
            token.pickUp(map, picked);
            save(console);
            console.println("Picked up " + picked.getItem() + ".");
        } catch (Exception e) {
            console.println("Cannot pick up: " + e.getMessage());
        }
        return this;
    }

    private Page handleDrop(CliSession session) {
        ConsoleIO console = session.getConsole();
        List<MapItemToken> carried = token.getInventory();
        if (carried.isEmpty()) {
            console.println("You aren't carrying anything.");
            return this;
        }

        console.println("Carrying:");
        for (int i = 0; i < carried.size(); i++) {
            console.println("  " + (i + 1) + ". " + carried.get(i));
        }
        console.print("Number to drop (blank to cancel): ");
        int idx = readOptionalIndex(console, carried.size());
        if (idx < 0) {
            return this;
        }

        try {
            MapItemToken dropped = carried.get(idx);
            token.drop(map, dropped);
            save(console);
            console.println("Dropped " + dropped.getItem() + ".");
        } catch (Exception e) {
            console.println("Cannot drop: " + e.getMessage());
        }
        return this;
    }

    private Page handleCarrying(CliSession session) {
        ConsoleIO console = session.getConsole();
        List<MapItemToken> carried = token.getInventory();
        if (carried.isEmpty()) {
            console.println("You aren't carrying anything.");
        } else {
            console.println("Carrying:");
            for (MapItemToken t : carried) {
                console.println("  - " + t);
            }
        }
        return this;
    }

    private Page handleCast(CliSession session) {
        ConsoleIO console = session.getConsole();
        PlayerCharacter pc = token.getCharacter();
        if (pc == null) {
            console.println("No character data available.");
            return this;
        }

        List<PlayerCharacter.PlayerSpell> known = pc.getSpells();
        List<PlayerCharacter.PlayerSpell> active = new ArrayList<>();
        if (known != null) {
            for (PlayerCharacter.PlayerSpell ps : known) {
                if (ps.isActive()) {
                    active.add(ps);
                }
            }
        }
        if (active.isEmpty()) {
            console.println("You have no active spells or abilities to use.");
            return this;
        }

        List<Spell> resolved = new ArrayList<>();
        console.println("Active spells/abilities:");
        for (int i = 0; i < active.size(); i++) {
            Spell spell = repos.spells().getById(active.get(i).getSpellId());
            resolved.add(spell);
            String name = spell != null ? spell.getName() : active.get(i).getSpellId();
            String rangeInfo = spell != null ? " (range " + spell.getRange() + " ft.)" : "";
            console.println("  " + (i + 1) + ". " + name + rangeInfo);
        }
        console.print("Number to use (blank to cancel): ");
        int idx = readOptionalIndex(console, active.size());
        if (idx < 0) {
            return this;
        }

        Spell spell = resolved.get(idx);
        if (spell == null) {
            console.println("Spell details not found.");
            return this;
        }

        Position current = token.getPosition();
        if (current == null) {
            console.println("You are not currently placed on this map.");
            return this;
        }

        console.print("Target x (blank to target yourself): ");
        String xInput = console.readLine().trim();
        int tx;
        int ty;
        if (xInput.isEmpty()) {
            tx = current.getX();
            ty = current.getY();
        } else {
            try {
                tx = Integer.parseInt(xInput);
            } catch (NumberFormatException e) {
                console.println("Invalid number.");
                return this;
            }
            console.print("Target y: ");
            ty = readInt(console);
        }

        if (tx < 0 || tx >= map.getWidth() || ty < 0 || ty >= map.getHeight()) {
            console.println("Target out of bounds.");
            return this;
        }

        int distanceSquares = Math.max(Math.abs(tx - current.getX()), Math.abs(ty - current.getY()));
        int rangeSquares = Math.max(spell.getRange() / 5, 0);
        if (distanceSquares > rangeSquares) {
            console.println("Out of range! " + spell.getName() + " has a range of " + rangeSquares
                + " square(s) (that target is " + distanceSquares + " away).");
            return this;
        }

        console.println();
        console.println(displayName(pc) + " casts " + spell.getName() + " at (" + tx + ", " + ty + ")!");

        Damage damage = spell.getDamage();
        if (damage != null && damage.hasDice()) {
            int rolled = 0;
            for (DiceRoll roll : damage.getDice()) {
                Dice dice = repos.dice().getById(roll.getDiceId());
                int sides = dice != null ? dice.getSides() : 0;
                for (int i = 0; i < roll.getCount(); i++) {
                    rolled += sides > 0 ? 1 + RANDOM.nextInt(sides) : 0;
                }
            }
            String damageType = damage.getTypeId() != null ? damage.getTypeId() : "damage";
            console.println("Deals " + rolled + " " + damageType + " damage.");
        }

        List<Effect> effects = spell.getEffects();
        if (effects != null) {
            for (Effect effect : effects) {
                console.println("Effect: " + effect);
            }
        }

        List<String> affected = new ArrayList<>();
        GridCell targetCell = map.getCell(tx, ty);
        for (MapObject obj : targetCell.getOccupants()) {
            if (obj != token) {
                affected.add(obj.toString());
            }
        }
        if (!affected.isEmpty()) {
            console.println("Affects: " + String.join(", ", affected));
        } else if (tx != current.getX() || ty != current.getY()) {
            console.println("Nothing was there.");
        }

        if (spell.getManaCost() > 0) {
            console.println("Mana cost: " + spell.getManaCost());
        }
        console.println();
        return this;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String displayName(PlayerCharacter pc) {
        return PlayerModeSupport.displayName(pc);
    }

    /** 5 ft. per grid square is the standard tabletop conversion. */
    private int speedCells() {
        PlayerCharacter pc = token.getCharacter();
        CharacterRace race = pc != null ? repos.races().getById(pc.getRaceId()) : null;
        return PlayerModeSupport.speedCells(race);
    }

    private List<MapItemToken> itemsOnCurrentCell() {
        List<MapItemToken> items = new ArrayList<>();
        Position pos = token.getPosition();
        if (pos == null) {
            return items;
        }
        GridCell cell = map.getCell(pos.getX(), pos.getY());
        for (MapObject obj : cell.getOccupants()) {
            if (obj instanceof MapItemToken && ((MapItemToken) obj).isPickable()) {
                items.add((MapItemToken) obj);
            }
        }
        return items;
    }

    private int readInt(ConsoleIO console) {
        try {
            return Integer.parseInt(console.readLine().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Reads a 1-based index, returns -1 (and prints a message) on blank/invalid input. */
    private int readOptionalIndex(ConsoleIO console, int size) {
        String input = console.readLine().trim();
        if (input.isEmpty()) {
            return -1;
        }
        try {
            int idx = Integer.parseInt(input) - 1;
            if (idx < 0 || idx >= size) {
                console.println("Invalid number.");
                return -1;
            }
            return idx;
        } catch (NumberFormatException e) {
            console.println("Invalid number.");
            return -1;
        }
    }

    private void save(ConsoleIO console) {
        try {
            repos.maps().update(map);
        } catch (Exception e) {
            console.println("Warning: failed to save map changes: " + e.getMessage());
        }
    }
}

