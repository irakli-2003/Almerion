package com.dnd.cli.pages;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.core.CommandSpec;
import com.dnd.cli.core.ConsoleIO;
import com.dnd.cli.core.Page;
import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.creature.Beast;
import com.dnd.model.creature.Monster;
import com.dnd.model.creature.Npc;
import com.dnd.model.item.Item;
import com.dnd.model.world.map.BeastToken;
import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.GridCell;
import com.dnd.model.world.map.MapActor;
import com.dnd.model.world.map.MapItemToken;
import com.dnd.model.world.map.MapObject;
import com.dnd.model.world.map.MonsterToken;
import com.dnd.model.world.map.NpcToken;
import com.dnd.model.world.map.PlayerToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Interactive CLI page for a single {@link GameMap}.
 *
 * <p>The grid is re-rendered on every page refresh (via {@link #getBody()}).
 * Available commands: place, move, remove, pickup, drop, save.</p>
 *
 * <p>All map mutations are auto-saved to {@code world/maps.json} immediately.</p>
 */
public class MapSessionPage implements Page {

    private final GameMap map;
    private final CampaignRepositories repos;
    private final Page parent;

    public MapSessionPage(GameMap map, CampaignRepositories repos, Page parent) {
        this.map = map;
        this.repos = repos;
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
        sb.append(MapRenderer.renderFull(map));
        sb.append("\n");
        sb.append("Symbols: P=Player  N=NPC  M=Monster  B=Beast  i=Item  #=Wall  .=Open  *=Effect");
        return sb.toString();
    }

    @Override
    public Page getParent() {
        return parent;
    }

    @Override
    public List<CommandSpec> getCommands() {
        return Arrays.asList(
            new CommandSpec("place",   "Place a token on the map",              this::handlePlace),
            new CommandSpec("move",    "Move a token to another cell",           this::handleMove),
            new CommandSpec("remove",  "Remove a token from the map",            this::handleRemove),
            new CommandSpec("pickup",  "Actor picks up an item from their cell", this::handlePickup),
            new CommandSpec("drop",    "Actor drops a carried item",             this::handleDrop),
            new CommandSpec("tokens",  "List all tokens currently on the map",   this::handleListTokens),
            new CommandSpec("save",    "Save map to campaign file",              this::handleSave)
        );
    }

    // ── Command handlers ──────────────────────────────────────────────────────

    private Page handlePlace(CliSession session) {
        ConsoleIO console = session.getConsole();
        console.println("Types: player / npc / monster / beast / item");
        console.print("Type: ");
        String typeStr = console.readLine().trim().toLowerCase(Locale.ROOT);
        if (typeStr.isEmpty()) { console.println("Cancelled."); return this; }

        MapObject token = createTokenByType(console, typeStr);
        if (token == null) return this;

        int[] coords = askCoords(console);
        if (coords == null) return this;

        try {
            map.placeObject(token, coords[0], coords[1]);
            autoSave(console);
        } catch (Exception e) {
            console.println("Cannot place: " + e.getMessage());
        }
        return this;
    }

    private Page handleMove(CliSession session) {
        ConsoleIO console = session.getConsole();
        List<MapObject> all = listAllOccupants();
        if (all.isEmpty()) { console.println("No tokens on the map."); return this; }

        printTokenList(console, all);
        console.print("Token number: ");
        int idx = readInt(console) - 1;
        if (idx < 0 || idx >= all.size()) { console.println("Invalid number."); return this; }

        int[] coords = askCoords(console);
        if (coords == null) return this;

        try {
            map.moveObject(all.get(idx), coords[0], coords[1]);
            autoSave(console);
        } catch (Exception e) {
            console.println("Cannot move: " + e.getMessage());
        }
        return this;
    }

    private Page handleRemove(CliSession session) {
        ConsoleIO console = session.getConsole();
        List<MapObject> all = listAllOccupants();
        if (all.isEmpty()) { console.println("No tokens on the map."); return this; }

        printTokenList(console, all);
        console.print("Token number: ");
        int idx = readInt(console) - 1;
        if (idx < 0 || idx >= all.size()) { console.println("Invalid number."); return this; }

        boolean removed = map.removeObject(all.get(idx));
        if (removed) {
            autoSave(console);
        } else {
            console.println("Token not found on grid.");
        }
        return this;
    }

    private Page handlePickup(CliSession session) {
        ConsoleIO console = session.getConsole();
        List<MapActor> actors = listActors();
        if (actors.isEmpty()) { console.println("No actors (player/npc/monster/beast) on the map."); return this; }

        console.println("Actors:");
        for (int i = 0; i < actors.size(); i++) {
            console.println((i + 1) + ". " + actors.get(i));
        }
        console.print("Actor number: ");
        int aIdx = readInt(console) - 1;
        if (aIdx < 0 || aIdx >= actors.size()) { console.println("Invalid number."); return this; }

        MapActor actor = actors.get(aIdx);
        // Collect pickable items on the actor's cell
        List<MapItemToken> cellItems = getPickableItemsOnCell(actor);
        if (cellItems.isEmpty()) { console.println("No pickable items on this cell."); return this; }

        console.println("Items on cell:");
        for (int i = 0; i < cellItems.size(); i++) {
            console.println((i + 1) + ". " + cellItems.get(i));
        }
        console.print("Item number: ");
        int iIdx = readInt(console) - 1;
        if (iIdx < 0 || iIdx >= cellItems.size()) { console.println("Invalid number."); return this; }

        try {
            actor.pickUp(map, cellItems.get(iIdx));
            autoSave(console);
            console.println(actor + " picked up " + cellItems.get(iIdx).getItem() + ".");
        } catch (Exception e) {
            console.println("Cannot pick up: " + e.getMessage());
        }
        return this;
    }

    private Page handleDrop(CliSession session) {
        ConsoleIO console = session.getConsole();
        List<MapActor> actors = listActors();
        // Only actors that are carrying something
        List<MapActor> carrying = new ArrayList<>();
        for (MapActor a : actors) {
            if (!a.getInventory().isEmpty()) carrying.add(a);
        }
        if (carrying.isEmpty()) { console.println("No actor is currently carrying any items."); return this; }

        console.println("Actors carrying items:");
        for (int i = 0; i < carrying.size(); i++) {
            console.println((i + 1) + ". " + carrying.get(i) + "  [inventory: " + carrying.get(i).getInventory().size() + " item(s)]");
        }
        console.print("Actor number: ");
        int aIdx = readInt(console) - 1;
        if (aIdx < 0 || aIdx >= carrying.size()) { console.println("Invalid number."); return this; }

        MapActor actor = carrying.get(aIdx);
        List<MapItemToken> inv = actor.getInventory();
        console.println("Inventory:");
        for (int i = 0; i < inv.size(); i++) {
            console.println((i + 1) + ". " + inv.get(i));
        }
        console.print("Item number: ");
        int iIdx = readInt(console) - 1;
        if (iIdx < 0 || iIdx >= inv.size()) { console.println("Invalid number."); return this; }

        try {
            MapItemToken token = inv.get(iIdx);
            actor.drop(map, token);
            autoSave(console);
            console.println(actor + " dropped " + token.getItem() + ".");
        } catch (Exception e) {
            console.println("Cannot drop: " + e.getMessage());
        }
        return this;
    }

    private Page handleListTokens(CliSession session) {
        ConsoleIO console = session.getConsole();
        List<MapObject> all = listAllOccupants();
        if (all.isEmpty()) {
            console.println("No tokens on the map.");
        } else {
            console.println("Tokens on map (" + all.size() + "):");
            printTokenList(console, all);
        }
        // Also show actors' inventories
        List<MapActor> actors = listActors();
        for (MapActor actor : actors) {
            if (!actor.getInventory().isEmpty()) {
                console.println("  " + actor + " is carrying:");
                for (MapItemToken t : actor.getInventory()) {
                    console.println("    - " + t);
                }
            }
        }
        return this;
    }

    private Page handleSave(CliSession session) {
        save(session.getConsole());
        return this;
    }

    // ── Token creation ────────────────────────────────────────────────────────

    private MapObject createTokenByType(ConsoleIO console, String typeStr) {
        switch (typeStr) {
            case "player":  return createPlayerToken(console);
            case "npc":     return createNpcToken(console);
            case "monster": return createMonsterToken(console);
            case "beast":   return createBeastToken(console);
            case "item":    return createItemToken(console);
            default:
                console.println("Unknown type '" + typeStr + "'. Use: player / npc / monster / beast / item");
                return null;
        }
    }

    private PlayerToken createPlayerToken(ConsoleIO console) {
        PlayerCharacter pc = pickFromList(console, repos.players().list(), "player");
        if (pc == null) return null;
        return new PlayerToken(pc);
    }

    private NpcToken createNpcToken(ConsoleIO console) {
        Npc npc = pickFromList(console, repos.npcs().list(), "NPC");
        if (npc == null) return null;
        return new NpcToken(npc);
    }

    private MonsterToken createMonsterToken(ConsoleIO console) {
        Monster monster = pickFromList(console, repos.monsters().list(), "monster");
        if (monster == null) return null;
        return new MonsterToken(monster);
    }

    private BeastToken createBeastToken(ConsoleIO console) {
        Beast beast = pickFromList(console, repos.beasts().list(), "beast");
        if (beast == null) return null;
        return new BeastToken(beast);
    }

    private MapItemToken createItemToken(ConsoleIO console) {
        Item item = pickFromList(console, repos.items().list(), "item");
        if (item == null) return null;
        return new MapItemToken(item);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Shows a numbered list of available entries, then lets the user pick by
     * number OR by typing a name/prefix.  Returns {@code null} on cancellation
     * or if the catalog is empty.
     */
    private <T> T pickFromList(ConsoleIO console, List<T> list, String label) {
        if (list.isEmpty()) {
            console.println("No " + label + "s found in this campaign. Create one first via the GM menu.");
            return null;
        }
        console.println("Available " + label + "s:");
        for (int i = 0; i < list.size(); i++) {
            console.println("  " + (i + 1) + ". " + getEntityName(list.get(i)));
        }
        console.print("Number or name (blank to cancel): ");
        String input = console.readLine().trim();
        if (input.isEmpty()) {
            console.println("Cancelled.");
            return null;
        }
        // Try numeric pick first
        try {
            int idx = Integer.parseInt(input) - 1;
            if (idx >= 0 && idx < list.size()) return list.get(idx);
            console.println("Number out of range.");
            return null;
        } catch (NumberFormatException ignored) {
            // fall through to name search
        }
        // Name / prefix search
        String lower = input.toLowerCase(Locale.ROOT);
        T partial = null;
        for (T entity : list) {
            String name = getEntityName(entity).toLowerCase(Locale.ROOT);
            if (name.equals(lower)) return entity;
            if (name.startsWith(lower)) {
                if (partial != null) {
                    console.println("Ambiguous name '" + input + "'. Be more specific.");
                    return null;
                }
                partial = entity;
            }
        }
        if (partial == null) console.println("No " + label + " matching '" + input + "'.");
        return partial;
    }

    private String getEntityName(Object entity) {
        try {
            return (String) entity.getClass().getMethod("getName").invoke(entity);
        } catch (Exception e) {
            return "";
        }
    }

    private int[] askCoords(ConsoleIO console) {
        console.print("x (0-" + (map.getWidth() - 1) + "): ");
        int x = readInt(console);
        console.print("y (0-" + (map.getHeight() - 1) + "): ");
        int y = readInt(console);
        if (x < 0 || x >= map.getWidth() || y < 0 || y >= map.getHeight()) {
            console.println("Coordinates out of bounds (map is " + map.getWidth() + "x" + map.getHeight() + ").");
            return null;
        }
        return new int[]{x, y};
    }

    private int readInt(ConsoleIO console) {
        try {
            return Integer.parseInt(console.readLine().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Collects every occupant from every cell, in row-major order. */
    private List<MapObject> listAllOccupants() {
        List<MapObject> all = new ArrayList<>();
        for (List<GridCell> row : map.getGrid()) {
            for (GridCell cell : row) {
                all.addAll(cell.getOccupants());
            }
        }
        return all;
    }

    /** Returns all {@link MapActor}s currently on the map. */
    private List<MapActor> listActors() {
        List<MapActor> actors = new ArrayList<>();
        for (MapObject obj : listAllOccupants()) {
            if (obj instanceof MapActor) actors.add((MapActor) obj);
        }
        return actors;
    }

    /** Returns all pickable {@link MapItemToken}s on the same cell as {@code actor}. */
    private List<MapItemToken> getPickableItemsOnCell(MapActor actor) {
        List<MapItemToken> items = new ArrayList<>();
        if (actor.getPosition() == null) return items;
        GridCell cell = map.getCell(actor.getPosition().getX(), actor.getPosition().getY());
        for (MapObject obj : cell.getOccupants()) {
            if (obj instanceof MapItemToken && ((MapItemToken) obj).isPickable()) {
                items.add((MapItemToken) obj);
            }
        }
        return items;
    }

    private void printTokenList(ConsoleIO console, List<MapObject> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            MapObject t = tokens.get(i);
            String pos = t.getPosition() != null ? " @ " + t.getPosition() : "";
            console.println("  " + (i + 1) + ". [" + t.getSymbol() + "] " + t + pos);
        }
    }

    /**
     * Renders the map grid with numeric x/y coordinate labels along the edges.
     * See {@link MapRenderer} for the alignment details.
     */
    private String renderGridWithCoordinates() {
        return MapRenderer.renderFull(map);
    }

    private void autoSave(ConsoleIO console) {
        save(console);
    }

    private void save(ConsoleIO console) {
        try {
            if (repos.maps().getById(map.getId()) == null) {
                repos.maps().add(map);
            } else {
                repos.maps().update(map);
            }
            console.println("Map saved.");
        } catch (Exception e) {
            console.println("Failed to save map: " + e.getMessage());
        }
    }
}



