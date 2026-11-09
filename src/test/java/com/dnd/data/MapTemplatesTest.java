package com.dnd.data;

import com.dnd.model.world.map.GameMap;
import org.junit.Test;

import static org.junit.Assert.*;

public class MapTemplatesTest {
    @Test
    public void bundledLayoutsAreAvailableWithoutAnExistingCampaign() {
        var templates = MapTemplates.builtIns();
        assertEquals(13, templates.size());
        assertNull(templates.get(0).layout());
        for (var template : templates) {
            GameMap map = MapTemplates.create(template, "unique", "Named location", 4, 3);
            assertEquals("unique", map.getId());
            assertEquals("Named location", map.getName());
            assertFalse(map.getGrid().isEmpty());
            assertTrue(map.getMapLinks().isEmpty());
            assertNull(map.getPartyPosition());
            for (var row : map.getGrid()) for (var cell : row) assertTrue(cell.getOccupants().isEmpty());
        }
    }

    @Test
    public void reusingATemplateMakesIndependentLocationMapsWithoutChangingTheTemplate() {
        var temple = MapTemplates.builtIns().stream().filter(t -> t.name().equals("Temple")).findFirst().orElseThrow();
        GameMap castle = MapTemplates.create(temple, "castle", "Queen's Castle", 1, 1);
        GameMap chapel = MapTemplates.create(temple, "chapel", "Royal Chapel", 1, 1);
        assertNotSame(castle.getGrid(), chapel.getGrid());
        assertNotSame(castle.getCell(0, 0), chapel.getCell(0, 0));
        castle.getCell(0, 0).setPassable(!chapel.getCell(0, 0).isPassable());
        castle.moveParty(1, 1);
        assertEquals(temple.layout().getCell(0, 0).isPassable(), chapel.getCell(0, 0).isPassable());
        assertNull(chapel.getPartyPosition());
        assertNull(temple.layout().getPartyPosition());
    }
}
