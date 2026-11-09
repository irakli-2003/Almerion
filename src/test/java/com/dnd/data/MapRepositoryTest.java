package com.dnd.data;

import com.dnd.data.dto.MapCatalog;
import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.Position;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.junit.Assert.*;

public class MapRepositoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private MapRepository repo() {
        return new CampaignRepositories(temp.getRoot().toPath()).maps();
    }

    @Test
    public void allWritesRequireAUniqueNonBlankLocationName() {
        MapRepository maps = repo();
        maps.add(new GameMap("castle", "Queen's Castle", 2, 2));
        assertThrows(IllegalArgumentException.class, () -> maps.add(new GameMap("x", "", 2, 2)));
        assertThrows(IllegalArgumentException.class, () -> maps.save(new GameMap("x", " \t ", 2, 2)));
        assertThrows(IllegalArgumentException.class, () -> maps.save(new GameMap("x", "\u00a0", 2, 2)));
        assertThrows(IllegalArgumentException.class, () -> maps.add(new GameMap("x", "QUEEN'S  CASTLE ", 2, 2)));
        assertThrows(IllegalArgumentException.class, () -> maps.save(new GameMap("x", "Queen's Castle", 2, 2)));
        maps.save(new GameMap("chapel", "Chapel", 2, 2));
        GameMap chapel = maps.getById("chapel");
        chapel.setName("Queen's Castle");
        assertThrows(IllegalArgumentException.class, () -> maps.update(chapel));
        assertEquals("Chapel", maps.getById("chapel").getName());
        chapel.setName("Royal Chapel");
        maps.update(chapel);
        assertEquals("chapel", maps.getById("chapel").getId());
        assertEquals("Royal Chapel", maps.getById("chapel").getName());
    }

    @Test
    public void legacyDuplicateNamesDoNotBlockBattleSavesOrChangeIds() {
        GameMap first = new GameMap("old1", "Temple", 2, 2);
        GameMap second = new GameMap("old2", "Temple", 2, 2);
        MapCatalog old = new MapCatalog();
        old.setMaps(List.of(first, second));
        try {
            var path = new CampaignPaths(temp.getRoot().toPath()).mapsFile();
            java.nio.file.Files.createDirectories(path.getParent());
            JsonMappers.create().writeValue(path.toFile(), old);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        MapRepository maps = repo();
        GameMap battle = maps.getById("old1");
        battle.moveParty(1, 1);
        maps.save(battle);
        assertEquals(new Position(1, 1), maps.getById("old1").getPartyPosition());
        assertEquals(2, maps.list().size());
        assertThrows(IllegalArgumentException.class, () -> maps.add(new GameMap("new", "Temple", 2, 2)));
    }

    @Test
    public void renamedMapUsesItsNewNameInWorldPins() {
        MapRepository maps = repo();
        maps.add(new GameMap("castle", "Queen's Castle", 2, 2));
        GameMap world = new GameMap("world", "Kingdom", 2, 2);
        world.linkMap("castle", "Temple", 0, 0);
        maps.refreshPinNames(world);
        assertEquals("Queen's Castle", world.linkAt(0, 0).getName());
        assertEquals("castle", world.linkAt(0, 0).getMapId());
    }
}
