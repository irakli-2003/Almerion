package com.dnd.model.world.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class GameMapTest {
    @Test
    public void acceptsValidDimensions() {
        GameMap map = new GameMap("map1", "Test Map", 1, 1);
        assertEquals(1, map.getWidth());
        assertEquals(1, map.getHeight());

        map = new GameMap("map2", "Large Map", 50, 100);
        assertEquals(50, map.getWidth());
        assertEquals(100, map.getHeight());

        map = new GameMap("map3", "Max Map", 1000, 1000);
        assertEquals(1000, map.getWidth());
        assertEquals(1000, map.getHeight());
    }

    @Test
    public void rejectsZeroWidth() {
        assertThrows(IllegalArgumentException.class, () -> new GameMap("map", "Test", 0, 10));
    }

    @Test
    public void rejectsZeroHeight() {
        assertThrows(IllegalArgumentException.class, () -> new GameMap("map", "Test", 10, 0));
    }

    @Test
    public void rejectsNegativeDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new GameMap("map", "Test", -5, 10));
        assertThrows(IllegalArgumentException.class, () -> new GameMap("map", "Test", 10, -5));
    }

    @Test
    public void rejectsWidthExceedingMaximum() {
        assertThrows(IllegalArgumentException.class, () -> new GameMap("map", "Test", 1001, 10));
    }

    @Test
    public void rejectsHeightExceedingMaximum() {
        assertThrows(IllegalArgumentException.class, () -> new GameMap("map", "Test", 10, 1001));
    }

    @Test
    public void setWidthValidatesDimension() {
        GameMap map = new GameMap("map", "Test", 20, 20);
        assertThrows(IllegalArgumentException.class, () -> map.setWidth(0));
        assertThrows(IllegalArgumentException.class, () -> map.setWidth(1001));
    }

    @Test
    public void setHeightValidatesDimension() {
        GameMap map = new GameMap("map", "Test", 20, 20);
        assertThrows(IllegalArgumentException.class, () -> map.setHeight(0));
        assertThrows(IllegalArgumentException.class, () -> map.setHeight(1001));
    }

    @Test
    public void constructorBuildsGridWithCorrectDimensions() {
        GameMap map = new GameMap("map", "Test", 5, 3);
        assertEquals(3, map.getGrid().size());        // 3 rows
        assertEquals(5, map.getGrid().get(0).size()); // 5 columns in first row
        assertEquals(5, map.getGrid().get(2).size()); // 5 columns in last row
    }

    @Test
    public void gridCellsArePassableByDefault() {
        GameMap map = new GameMap("map", "Test", 3, 3);
        for (java.util.List<GridCell> row : map.getGrid()) {
            for (GridCell cell : row) {
                assertEquals(true, cell.isPassable());
            }
        }
    }

    @Test
    public void worldMapPinsAndPartySurviveSavingAndOldMapsStillLoad() throws Exception {
        var json = com.dnd.data.JsonMappers.create();
        GameMap world = new GameMap("world", "World", 10, 10);
        world.linkMap("town", "Town", 3, 4);
        world.linkMap("cave", "Cave", 3, 4);
        world.moveParty(5, 6);

        GameMap loaded = json.readValue(json.writeValueAsString(world), GameMap.class);
        assertEquals(1, loaded.getMapLinks().size());
        assertEquals("cave", loaded.linkAt(3, 4).getMapId());
        assertEquals(new Position(5, 6), loaded.getPartyPosition());
        assertThrows(IllegalArgumentException.class, () -> loaded.linkMap("world", "Self", 1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> loaded.moveParty(10, 0));

        var oldJson = json.valueToTree(new GameMap("old", "Old", 2, 2));
        ((com.fasterxml.jackson.databind.node.ObjectNode) oldJson).remove(java.util.List.of("mapLinks", "partyPosition"));
        GameMap old = json.treeToValue(oldJson, GameMap.class);
        assertEquals(0, old.getMapLinks().size());
        org.junit.Assert.assertNull(old.getPartyPosition());
    }
}
