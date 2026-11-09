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
     * created. Also keeps the party on a single map.
     */
    public List<GameMap> ensure() {
        MapRepository maps = repos.maps();
        if (list().isEmpty()) {
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
