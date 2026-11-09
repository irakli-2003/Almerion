package com.dnd.model.world.map;

/**
 * A square of a bigger map (a region or world map) that leads to another map of the campaign:
 * a town, a dungeon, a forest. Double-clicking it on the map opens that map.
 */
public class MapPin {
    private String mapId;
    /** The linked map's name when the link was made, drawn under the pin. */
    private String name;
    private int x;
    private int y;

    public MapPin() {
    }

    public MapPin(String mapId, String name, int x, int y) {
        this.mapId = mapId;
        this.name = name;
        this.x = x;
        this.y = y;
    }

    public String getMapId() { return mapId; }
    public void setMapId(String mapId) { this.mapId = mapId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }
    public int getY() { return y; }
    public void setY(int y) { this.y = y; }
}
