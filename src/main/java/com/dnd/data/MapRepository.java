package com.dnd.data;

import com.dnd.data.dto.MapCatalog;
import com.dnd.model.world.map.GameMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Campaign maps have unique location names; templates never enter this repository. */
public class MapRepository extends JsonRepository<GameMap, MapCatalog> {
    public MapRepository(Path path, ObjectMapper mapper) {
        super(path, mapper, MapCatalog::new, MapCatalog.class,
            MapCatalog::getMaps, MapCatalog::setMaps, GameMap::getId);
    }

    public void validateName(String name, String exceptId) {
        validateName(name, exceptId, list());
    }

    private static void validateName(String name, String exceptId, List<GameMap> maps) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A unique map name is required.");
        String key = nameKey(name);
        if (key.isEmpty()) throw new IllegalArgumentException("A unique map name is required.");
        for (GameMap map : maps) {
            if (!Objects.equals(exceptId, map.getId()) && key.equals(nameKey(map.getName()))) {
                throw new IllegalArgumentException("A map with this name already exists. Choose a different location name.");
            }
        }
    }

    private void validate(GameMap map, boolean creating) {
        if (map == null) throw new IllegalArgumentException("Map is required.");
        List<GameMap> maps = list();
        GameMap previous = creating ? null : maps.stream()
            .filter(m -> Objects.equals(m.getId(), map.getId())).findFirst().orElse(null);
        // Old campaigns may already contain duplicate/blank names. Saving their battle state
        // must still work; creating or renaming a map always enforces the new rule.
        if (previous != null && Objects.equals(previous.getName(), map.getName())) return;
        validateName(map.getName(), creating ? null : map.getId(), maps);
        map.setName(map.getName().strip());
    }

    @Override public void add(GameMap map) { validate(map, true); super.add(map); }
    @Override public void update(GameMap map) { validate(map, false); keepWorldState(map); super.update(map); }
    @Override public void save(GameMap map) { validate(map, false); keepWorldState(map); super.save(map); }

    /**
     * World pins, the world flag and the party marker belong to the World Map panel. Battle and
     * terrain editors save whole maps from older in-memory copies, so they must not overwrite them.
     */
    private void keepWorldState(GameMap map) {
        GameMap stored = getById(map.getId());
        if (stored == null) return;
        map.setWorldMap(stored.isWorldMap());
        map.setMapLinks(stored.getMapLinks());
        map.setPartyPosition(stored.getPartyPosition());
    }

    /** Saves a map including its world state; only {@link WorldMaps} should call this. */
    void saveWorldState(GameMap map) {
        validate(map, false);
        super.save(map);
    }

    /** Refuses to delete the campaign's last world map and removes pins leading to a deleted map. */
    @Override
    public boolean delete(String id) {
        GameMap target = id == null ? null : getById(id);
        if (target != null && target.isWorldMap()
            && list().stream().filter(GameMap::isWorldMap).count() <= 1) {
            throw new IllegalStateException("Every campaign needs at least one world map. Create another world map before deleting this one.");
        }
        boolean deleted = super.delete(id);
        if (deleted) {
            for (GameMap map : list()) {
                if (map.getMapLinks().removeIf(pin -> id.equals(pin.getMapId()))) super.save(map);
            }
        }
        return deleted;
    }

    private static String nameKey(String name) {
        return name == null ? "" : Normalizer.normalize(name, Normalizer.Form.NFKC)
            .strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public void refreshPinNames(GameMap map) {
        var names = names();
        for (var pin : map.getMapLinks()) {
            String name = names.get(pin.getMapId());
            if (name != null && !name.isBlank()) pin.setName(name);
        }
    }

    public java.util.Map<String, String> names() {
        var names = new java.util.LinkedHashMap<String, String>();
        for (GameMap target : list()) names.put(target.getId(), target.getName());
        return names;
    }
}
