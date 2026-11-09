package com.dnd.data;

import com.dnd.data.dto.MapCatalog;
import com.dnd.model.world.map.GameMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only layouts bundled with the app, available even in a blank or older campaign. */
public final class MapTemplates {
    public record Template(String name, GameMap layout) {
        @Override public String toString() { return name; }
    }

    private MapTemplates() {}

    public static List<Template> builtIns() {
        Map<String, String> labels = Map.ofEntries(
            Map.entry("gilded-stag-ground", "Tavern - Common Room"),
            Map.entry("gilded-stag-upper", "Tavern - Guest Rooms"),
            Map.entry("greenhollow-square", "Village Square"),
            Map.entry("ironhand-smithy-map", "Smithy"),
            Map.entry("temple-dawnfather-map", "Temple"),
            Map.entry("stonekeep-ground-floor", "Castle - Ground Floor"),
            Map.entry("forest-clearing-map", "Forest Clearing"),
            Map.entry("barrow-crypt-map", "Crypt"),
            Map.entry("blackfen-cave-map", "Cave"),
            Map.entry("bandit-camp-map", "Camp"),
            Map.entry("orins-tower-map", "Tower"),
            Map.entry("open-field-map", "Open Field"));
        try (var in = MapTemplates.class.getResourceAsStream("/data/default-campaign/world/maps.json")) {
            if (in == null) throw new DataAccessException("Bundled map templates are missing.");
            List<Template> templates = new ArrayList<>();
            templates.add(new Template("Blank map", null));
            for (GameMap layout : JsonMappers.create().readValue(in, MapCatalog.class).getMaps()) {
                String label = labels.get(layout.getId());
                if (label != null) templates.add(new Template(label, layout));
            }
            templates.subList(1, templates.size()).sort(java.util.Comparator.comparing(Template::name));
            return templates;
        } catch (IOException e) {
            throw new DataAccessException("Could not load bundled map templates.", e);
        }
    }

    public static GameMap create(Template template, String id, String name, int width, int height) {
        GameMap map;
        if (template.layout() == null) {
            map = new GameMap(id, name, width, height);
        } else {
            map = JsonMappers.create().convertValue(template.layout(), GameMap.class);
            map.setId(id);
            map.setName(name);
            map.ensureGridSize();
            for (var row : map.getGrid()) {
                for (var cell : row) cell.getOccupants().clear();
            }
            map.getMapLinks().clear();
            map.setPartyPosition(null);
            map.setMusicPlaylistId(null);
        }
        return map;
    }
}
