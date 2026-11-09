package com.dnd.data;

import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.MapPin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The campaign's world maps: every campaign has at least one, any number can be created, and
 * the party stands on exactly one square of one world map at a time.
 */
public final class WorldMaps {
    public static final String DEFAULT_NAME = "World Map";
    public static final int DEFAULT_WIDTH = 40;
    public static final int DEFAULT_HEIGHT = 30;

    private final CampaignRepositories repos;

    public WorldMaps(CampaignRepositories repos) {
        this.repos = Objects.requireNonNull(repos);
    }

    /** World maps sorted by name. */
    public List<GameMap> list() {
        List<GameMap> worlds = new ArrayList<>();
        for (GameMap map : repos.maps().list()) {
            if (map.isWorldMap()) worlds.add(map);
        }
        worlds.sort(Comparator.comparing(m -> m.getName() == null ? "" : m.getName(), String.CASE_INSENSITIVE_ORDER));
        return worlds;
    }

    /**
     * Makes sure the campaign has a world map. Maps that already carried world pins or the party
     * marker (made in 1.2.4's battle-map WORLD tools) become world maps; otherwise a blank one is
     * created. Location maps made before world maps existed are then pinned in rows from the top
     * edge of the world map so every map belongs somewhere; the GM drags them where they belong.
     * Also keeps the party on a single map.
     */
    public List<GameMap> ensure() {
        MapRepository maps = repos.maps();
        boolean upgrading = list().isEmpty();
        if (upgrading) {
            for (GameMap map : maps.list()) {
                if (!map.getMapLinks().isEmpty() || map.getPartyPosition() != null) {
                    map.setWorldMap(true);
                    maps.saveWorldState(map);
                }
            }
        }
        if (list().isEmpty()) {
            create(uniqueDefaultName(), null, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }
        if (upgrading) autoPlace(unplaced());
        GameMap partyMap = partyMap();
        for (GameMap map : maps.list()) {
            boolean strayParty = map.getPartyPosition() != null
                && (partyMap == null || !Objects.equals(map.getId(), partyMap.getId()));
            if (strayParty) {
                map.setPartyPosition(null);
                maps.saveWorldState(map);
            }
        }
        return list();
    }

    /** Creates a new world map from a template (null = blank) with a unique name. */
    public GameMap create(String name, MapTemplates.Template template, int width, int height) {
        repos.maps().validateName(name, null);
        String clean = name.strip();
        var ids = new IdHandler(new CampaignPaths(repos.campaignRoot()).idRegistryFile());
        String id = ids.generateId(clean, "world/maps.json");
        GameMap map = MapTemplates.create(template == null ? new MapTemplates.Template("Blank map", null) : template,
            id, clean, width, height);
        map.setWorldMap(true);
        repos.maps().add(map);
        return map;
    }

    /**
     * Creates a location map and pins it on square {@code (x, y)} of world map {@code worldId}:
     * every location belongs to a world map. Nothing is saved unless the square is valid and free.
     */
    public GameMap createLocation(String name, MapTemplates.Template template, int width, int height,
                                  String worldId, int x, int y) {
        GameMap world = requireWorld(worldId);
        if (x < 0 || y < 0 || x >= world.getWidth() || y >= world.getHeight()) {
            throw new IllegalArgumentException("Click the square on the world map where this location is.");
        }
        MapPin taken = world.linkAt(x, y);
        if (taken != null) {
            throw new IllegalArgumentException("That square already leads to " + taken.getName() + ". Choose another square.");
        }
        repos.maps().validateName(name, null);
        String clean = name.strip();
        var ids = new IdHandler(new CampaignPaths(repos.campaignRoot()).idRegistryFile());
        String id = ids.generateId(clean, "world/maps.json");
        GameMap map = MapTemplates.create(template == null ? new MapTemplates.Template("Blank map", null) : template,
            id, clean, width, height);
        repos.maps().add(map);
        try {
            link(worldId, id, x, y);
        } catch (RuntimeException e) {
            repos.maps().delete(id);
            throw e;
        }
        return map;
    }

    /**
     * Pins {@code locations} on free squares, row by row from the top-left of the party's world
     * map (else the first), spilling onto other world maps when one is full. Returns how many
     * were placed.
     */
    public int autoPlace(List<GameMap> maps) {
        GameMap partyMap = partyMap();
        return autoPlace(maps, partyMap == null ? null : partyMap.getId());
    }

    /** Like {@link #autoPlace(List)} but starts on world map {@code firstWorldId}. */
    public int autoPlace(List<GameMap> maps, String firstWorldId) {
        List<GameMap> locations = maps.stream().filter(m -> !m.isWorldMap()).toList();
        List<GameMap> targets = new ArrayList<>(list());
        GameMap first = targets.stream().filter(m -> Objects.equals(m.getId(), firstWorldId)).findFirst().orElse(null);
        if (first != null) {
            targets.remove(first);
            targets.add(0, first);
        }
        int placed = 0;
        int next = 0;
        for (GameMap world : targets) {
            world.ensureGridSize();
            boolean changed = false;
            for (int y = 0; y < world.getHeight() && next < locations.size(); y++) {
                for (int x = 0; x < world.getWidth() && next < locations.size(); x++) {
                    var party = world.getPartyPosition();
                    if (world.linkAt(x, y) != null || (party != null && party.getX() == x && party.getY() == y)) continue;
                    GameMap location = locations.get(next++);
                    String name = location.getName() == null || location.getName().isBlank() ? location.getId() : location.getName();
                    world.linkMap(location.getId(), name, x, y);
                    changed = true;
                    placed++;
                }
            }
            if (changed) repos.maps().saveWorldState(world);
            if (next >= locations.size()) break;
        }
        return placed;
    }

    /** Moves the pin on {@code (fromX, fromY)} of a world map to the free square {@code (toX, toY)}. */
    public MapPin movePin(String worldMapId, int fromX, int fromY, int toX, int toY) {
        GameMap world = requireWorld(worldMapId);
        MapPin pin = world.linkAt(fromX, fromY);
        if (pin == null) throw new IllegalArgumentException("There is no pin on that square.");
        if (toX < 0 || toY < 0 || toX >= world.getWidth() || toY >= world.getHeight()) {
            throw new IllegalArgumentException("That square is outside the world map.");
        }
        if (fromX == toX && fromY == toY) return pin;
        MapPin taken = world.linkAt(toX, toY);
        if (taken != null) {
            throw new IllegalArgumentException("That square already leads to " + taken.getName() + ". Choose another square.");
        }
        world.unlinkAt(fromX, fromY);
        MapPin moved = world.linkMap(pin.getMapId(), pin.getName(), toX, toY);
        repos.maps().saveWorldState(world);
        return moved;
    }

    /** For each map id, the names of the world maps that have a pin leading to it. */
    public java.util.Map<String, List<String>> homes() {
        java.util.Map<String, List<String>> homes = new java.util.HashMap<>();
        for (GameMap world : list()) {
            for (MapPin pin : world.getMapLinks()) {
                List<String> names = homes.computeIfAbsent(pin.getMapId(), k -> new ArrayList<>());
                if (!names.contains(world.getName())) names.add(world.getName());
            }
        }
        return homes;
    }

    /** Location maps not pinned on any world map yet (e.g. made before 1.2.5), sorted by name. */
    public List<GameMap> unplaced() {
        var homes = homes();
        List<GameMap> result = new ArrayList<>();
        for (GameMap map : repos.maps().list()) {
            if (!map.isWorldMap() && !homes.containsKey(map.getId())) result.add(map);
        }
        result.sort(Comparator.comparing(m -> m.getName() == null ? "" : m.getName(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** The world map the party is on, or null when the party marker is hidden. */
    public GameMap partyMap() {
        for (GameMap map : list()) {
            if (map.getPartyPosition() != null) return map;
        }
        return null;
    }

    /** Moves the party to {@code (x, y)} of world map {@code mapId}, removing it from every other map. */
    public void moveParty(String mapId, int x, int y) {
        GameMap target = requireWorld(mapId);
        target.moveParty(x, y);
        for (GameMap map : repos.maps().list()) {
            if (!Objects.equals(map.getId(), mapId) && map.getPartyPosition() != null) {
                map.setPartyPosition(null);
                repos.maps().saveWorldState(map);
            }
        }
        repos.maps().saveWorldState(target);
    }

    public void hideParty() {
        for (GameMap map : repos.maps().list()) {
            if (map.getPartyPosition() != null) {
                map.setPartyPosition(null);
                repos.maps().saveWorldState(map);
            }
        }
    }

    public MapPin link(String worldMapId, String targetMapId, int x, int y) {
        GameMap world = requireWorld(worldMapId);
        GameMap target = repos.maps().getById(targetMapId);
        if (target == null) throw new IllegalArgumentException("Choose a map to link to.");
        String name = target.getName() == null || target.getName().isBlank() ? target.getId() : target.getName();
        MapPin pin = world.linkMap(target.getId(), name, x, y);
        repos.maps().saveWorldState(world);
        return pin;
    }

    public boolean unlink(String worldMapId, int x, int y) {
        GameMap world = requireWorld(worldMapId);
        boolean removed = world.unlinkAt(x, y);
        if (removed) repos.maps().saveWorldState(world);
        return removed;
    }

    private String uniqueDefaultName() {
        var taken = repos.maps().names().values();
        String name = DEFAULT_NAME;
        for (int i = 2; containsIgnoreCase(taken, name); i++) name = DEFAULT_NAME + " " + i;
        return name;
    }

    private static boolean containsIgnoreCase(java.util.Collection<String> names, String name) {
        for (String existing : names) {
            if (existing != null && existing.strip().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private GameMap requireWorld(String mapId) {
        GameMap map = mapId == null ? null : repos.maps().getById(mapId);
        if (map == null || !map.isWorldMap()) throw new IllegalArgumentException("That world map no longer exists.");
        map.ensureGridSize();
        return map;
    }
}
