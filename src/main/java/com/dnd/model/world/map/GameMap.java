package com.dnd.model.world.map;

import com.dnd.model.interfaces.Printable;

import java.util.ArrayList;
import java.util.List;

/**
 * A rectangular grid of {@link GridCell} boxes belonging to a {@link com.dnd.model.world.Place}.
 * Coordinates follow {@link Position}'s convention: {@code (0, 0)} is the
 * top-left box, {@code x} grows rightward, {@code y} grows downward.
 */
public class GameMap implements Printable {
    public static final int MIN_DIMENSION = 1;
    public static final int MAX_DIMENSION = 1000;

    private String id;
    private String name;
    private int width;
    private int height;
    private List<List<GridCell>> grid = new ArrayList<>();
    private List<MapLayer> layers = new ArrayList<>();
    private List<Drawing> drawings = new ArrayList<>();
    private List<MapObjectGroup> groups = new ArrayList<>();
    /** Squares that lead to other maps, when this is a region or world map. */
    private List<MapPin> mapLinks = new ArrayList<>();
    /** Where the party is on this (world) map right now; null = not shown. */
    private Position partyPosition;
    /** True for world/region maps managed from the GM page's World Map panel. */
    private boolean worldMap;
    /** Music playlist (from the campaign's music.json) that starts when this map is opened; null = none. */
    private String musicPlaylistId;

    public String getMusicPlaylistId() {
        return musicPlaylistId;
    }

    public void setMusicPlaylistId(String musicPlaylistId) {
        this.musicPlaylistId = musicPlaylistId;
    }

    public GameMap() {
    }

    public GameMap(String id, String name, int width, int height) {
        this.id = id;
        this.name = name;
        setWidth(width);
        setHeight(height);
        this.grid = buildGrid(width, height);
    }

    private static List<List<GridCell>> buildGrid(int width, int height) {
        List<List<GridCell>> rows = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            List<GridCell> row = new ArrayList<>();
            for (int x = 0; x < width; x++) {
                row.add(new GridCell());
            }
            rows.add(row);
        }
        return rows;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        if (width < MIN_DIMENSION || width > MAX_DIMENSION) {
            throw new IllegalArgumentException("Map width must be between " + MIN_DIMENSION + " and " + MAX_DIMENSION + " but was " + width);
        }
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        if (height < MIN_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Map height must be between " + MIN_DIMENSION + " and " + MAX_DIMENSION + " but was " + height);
        }
        this.height = height;
    }

    public List<List<GridCell>> getGrid() {
        return grid;
    }

    public void setGrid(List<List<GridCell>> grid) {
        this.grid = grid != null ? grid : new ArrayList<>();
    }

    public List<MapLayer> getLayers() { return layers; }
    public void setLayers(List<MapLayer> layers) { this.layers = layers != null ? layers : new ArrayList<>(); }

    public List<Drawing> getDrawings() { return drawings; }
    public void setDrawings(List<Drawing> drawings) { this.drawings = drawings != null ? drawings : new ArrayList<>(); }

    public List<MapPin> getMapLinks() { return mapLinks; }
    public void setMapLinks(List<MapPin> mapLinks) { this.mapLinks = mapLinks != null ? mapLinks : new ArrayList<>(); }

    public Position getPartyPosition() { return partyPosition; }
    public void setPartyPosition(Position partyPosition) { this.partyPosition = partyPosition; }

    public boolean isWorldMap() { return worldMap; }
    public void setWorldMap(boolean worldMap) { this.worldMap = worldMap; }

    /** The link on square {@code (x, y)}, or null. */
    public MapPin linkAt(int x, int y) {
        for (MapPin link : mapLinks) {
            if (link.getX() == x && link.getY() == y) return link;
        }
        return null;
    }

    /** Links square {@code (x, y)} to {@code mapId}, replacing any link already there. */
    public MapPin linkMap(String mapId, String name, int x, int y) {
        requireInBounds(x, y);
        if (mapId == null || mapId.isBlank()) throw new IllegalArgumentException("Choose a map to link to.");
        if (mapId.equals(id)) throw new IllegalArgumentException("A map can't lead to itself.");
        mapLinks.removeIf(link -> link.getX() == x && link.getY() == y);
        MapPin link = new MapPin(mapId, name, x, y);
        mapLinks.add(link);
        return link;
    }

    public boolean unlinkAt(int x, int y) {
        return mapLinks.removeIf(link -> link.getX() == x && link.getY() == y);
    }

    /** Puts the party marker on square {@code (x, y)}. */
    public void moveParty(int x, int y) {
        requireInBounds(x, y);
        partyPosition = new Position(x, y);
    }

    public List<MapObjectGroup> getGroups() { return groups; }
    public void setGroups(List<MapObjectGroup> groups) { this.groups = groups != null ? groups : new ArrayList<>(); }

    /**
     * Resizes {@link #grid} to exactly {@code width} x {@code height}, padding any missing
     * rows/columns with fresh {@link GridCell}s and trimming any extra ones, while preserving
     * existing cells (and their occupants) wherever the old and new dimensions overlap.
     *
     * <p>Needed because {@link #setWidth(int)}/{@link #setHeight(int)} only validate and store
     * the new dimensions - they don't touch {@link #grid} - so a map created via the no-arg
     * constructor (whose grid starts empty) or resized after creation would otherwise end up
     * with a {@code grid} that doesn't match its declared {@code width}/{@code height}, causing
     * {@link #getCell(int, int)} to throw {@link IndexOutOfBoundsException} the moment anything
     * tries to render or edit it.</p>
     */
    public void ensureGridSize() {
        List<List<GridCell>> resized = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            List<GridCell> oldRow = y < grid.size() ? grid.get(y) : null;
            List<GridCell> newRow = new ArrayList<>();
            for (int x = 0; x < width; x++) {
                GridCell existing = (oldRow != null && x < oldRow.size()) ? oldRow.get(x) : null;
                newRow.add(existing != null ? existing : new GridCell());
            }
            resized.add(newRow);
        }
        this.grid = resized;
    }

    public GridCell getCell(int x, int y) {
        requireInBounds(x, y);
        return grid.get(y).get(x);
    }

    private void requireInBounds(int x, int y) {
        if (y < 0 || y >= grid.size() || x < 0 || x >= grid.get(y).size()) {
            throw new IndexOutOfBoundsException(
                "Position (" + x + ", " + y + ") is outside the " + width + "x" + height + " map.");
        }
    }

    /**
     * Places {@code object} at {@code (x, y)}, updating its {@link Position}
     * and registering it as an occupant of that box.
     *
     * @throws IndexOutOfBoundsException if the position is outside the grid
     * @throws IllegalStateException     if the box at that position is impassable
     */
    public void placeObject(MapObject object, int x, int y) {
        requireInBounds(x, y);
        GridCell cell = getCell(x, y);
        if (!cell.isPassable()) {
            throw new IllegalStateException("Cannot place an object on an impassable box at (" + x + ", " + y + ").");
        }
        object.setPosition(new Position(x, y));
        cell.addOccupant(object);
    }

    /**
     * Removes {@code object} from whichever box its current {@link Position} points to.
     *
     * @return true if the object was found and removed
     */
    public boolean removeObject(MapObject object) {
        Position position = object.getPosition();
        if (position == null) {
            return false;
        }
        int x = position.getX();
        int y = position.getY();
        if (y < 0 || y >= grid.size() || x < 0 || x >= grid.get(y).size()) {
            return false;
        }
        return getCell(x, y).removeOccupant(object);
    }

    /**
     * Moves {@code object} to {@code (newX, newY)}, removing it from its
     * current box first. If placement at the new position fails, the object
     * is left un-positioned (removed from the old box) rather than silently
     * staying put, so callers can detect the failure via the thrown exception.
     */
    public void moveObject(MapObject object, int newX, int newY) {
        removeObject(object);
        placeObject(object, newX, newY);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isEmpty()) {
            sb.append(name).append(System.lineSeparator());
        }
        for (int y = 0; y < grid.size(); y++) {
            List<GridCell> row = grid.get(y);
            for (GridCell cell : row) {
                sb.append(cell.getDisplaySymbol());
            }
            if (y < grid.size() - 1) {
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
    }
}
