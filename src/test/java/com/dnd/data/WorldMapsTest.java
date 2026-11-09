package com.dnd.data;

import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.Position;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.junit.Assert.*;

public class WorldMapsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private CampaignRepositories repos() {
        return new CampaignRepositories(temp.getRoot().toPath());
    }

    @Test
    public void everyCampaignGetsAWorldMapWithoutTouchingOtherMaps() {
        CampaignRepositories repos = repos();
        repos.maps().add(new GameMap("castle", "World Map", 3, 3));
        List<GameMap> worlds = new WorldMaps(repos).ensure();
        assertEquals(1, worlds.size());
        assertEquals("World Map 2", worlds.get(0).getName());
        assertTrue(worlds.get(0).isWorldMap());
        assertFalse(repos.maps().getById("castle").isWorldMap());
        assertEquals(1, new WorldMaps(repos).ensure().size());
    }

    @Test
    public void mapsWithOldWorldDataBecomeWorldMapsAndPartyIsInOnePlace() {
        CampaignRepositories repos = repos();
        GameMap kingdom = new GameMap("kingdom", "Kingdom", 4, 4);
        kingdom.linkMap("castle", "Castle", 1, 1);
        kingdom.moveParty(2, 2);
        GameMap islands = new GameMap("islands", "Islands", 4, 4);
        islands.moveParty(0, 0);
        repos.maps().add(kingdom);
        repos.maps().add(islands);
        repos.maps().add(new GameMap("castle", "Castle", 2, 2));

        WorldMaps worlds = new WorldMaps(repos);
        assertEquals(List.of("Islands", "Kingdom"), worlds.ensure().stream().map(GameMap::getName).toList());
        long withParty = repos.maps().list().stream().filter(m -> m.getPartyPosition() != null).count();
        assertEquals(1, withParty);

        worlds.moveParty("kingdom", 3, 3);
        assertEquals(new Position(3, 3), repos.maps().getById("kingdom").getPartyPosition());
        assertNull(repos.maps().getById("islands").getPartyPosition());
        assertEquals("kingdom", worlds.partyMap().getId());
        worlds.hideParty();
        assertNull(worlds.partyMap());
    }

    @Test
    public void battleSavesKeepWorldStateAndLastWorldMapCannotBeDeleted() {
        CampaignRepositories repos = repos();
        repos.maps().add(new GameMap("castle", "Castle", 2, 2));
        WorldMaps worlds = new WorldMaps(repos);
        GameMap world = worlds.ensure().get(0);
        GameMap staleCopy = repos.maps().getById(world.getId());

        worlds.link(world.getId(), "castle", 1, 1);
        worlds.moveParty(world.getId(), 0, 0);
        repos.maps().save(staleCopy);
        GameMap stored = repos.maps().getById(world.getId());
        assertTrue(stored.isWorldMap());
        assertEquals("castle", stored.linkAt(1, 1).getMapId());
        assertEquals(new Position(0, 0), stored.getPartyPosition());

        assertThrows(IllegalStateException.class, () -> repos.maps().delete(world.getId()));
        repos.maps().delete("castle");
        assertNull(repos.maps().getById(world.getId()).linkAt(1, 1));

        GameMap second = worlds.create("Underdark", null, 5, 5);
        assertTrue(second.isWorldMap());
        assertThrows(IllegalArgumentException.class, () -> worlds.create("underdark ", null, 5, 5));
        assertTrue(repos.maps().delete(world.getId()));
        assertEquals(1, worlds.list().size());
    }

    @Test
    public void locationsAreCreatedOnAFreeWorldSquareAndUnplacedMapsAreFound() {
        CampaignRepositories repos = repos();
        WorldMaps worlds = new WorldMaps(repos);
        GameMap world = worlds.ensure().get(0);
        repos.maps().add(new GameMap("old", "Old Cave", 2, 2));
        worlds.ensure();
        assertEquals(List.of("Old Cave"), worlds.unplaced().stream().map(GameMap::getName).toList());

        GameMap chapel = worlds.createLocation("Royal Chapel", null, 6, 6, world.getId(), 4, 5);
        assertEquals(chapel.getId(), repos.maps().getById(world.getId()).linkAt(4, 5).getMapId());
        assertEquals(List.of(world.getName()), worlds.homes().get(chapel.getId()));

        int before = repos.maps().list().size();
        assertThrows(IllegalArgumentException.class,
            () -> worlds.createLocation("Crypt", null, 6, 6, world.getId(), 4, 5));
        assertThrows(IllegalArgumentException.class,
            () -> worlds.createLocation("Crypt", null, 6, 6, world.getId(), -1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> worlds.createLocation("Crypt", null, 6, 6, world.getId(), world.getWidth(), 0));
        assertThrows(IllegalArgumentException.class,
            () -> worlds.createLocation("Crypt", null, 6, 6, "old", 0, 0));
        assertEquals(before, repos.maps().list().size());

        worlds.link(world.getId(), "old", 0, 0);
        assertTrue(worlds.unplaced().isEmpty());
    }

    @Test
    public void upgradingPinsOldMapsAlongTheTopAndPinsCanBeMoved() {
        CampaignRepositories repos = repos();
        repos.maps().add(new GameMap("castle", "Castle", 2, 2));
        repos.maps().add(new GameMap("cave", "Cave", 2, 2));
        GameMap kingdom = new GameMap("kingdom", "Kingdom", 3, 3);
        kingdom.moveParty(0, 0);
        kingdom.linkMap("castle", "Castle", 1, 0);
        repos.maps().add(kingdom);

        WorldMaps worlds = new WorldMaps(repos);
        worlds.ensure();
        GameMap world = repos.maps().getById("kingdom");
        assertTrue(world.isWorldMap());
        assertEquals("cave", world.linkAt(2, 0).getMapId());
        assertEquals(1, world.getMapLinks().stream().filter(p -> p.getMapId().equals("castle")).count());
        assertTrue(worlds.unplaced().isEmpty());

        worlds.movePin("kingdom", 2, 0, 1, 2);
        world = repos.maps().getById("kingdom");
        assertNull(world.linkAt(2, 0));
        assertEquals("cave", world.linkAt(1, 2).getMapId());
        assertThrows(IllegalArgumentException.class, () -> worlds.movePin("kingdom", 1, 2, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> worlds.movePin("kingdom", 2, 2, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> worlds.movePin("kingdom", 1, 2, 3, 0));

        repos.maps().add(new GameMap("crypt", "Crypt", 2, 2));
        worlds.ensure();
        assertEquals("a map made after the upgrade stays flagged", List.of("Crypt"),
            worlds.unplaced().stream().map(GameMap::getName).toList());
        assertEquals(1, worlds.autoPlace(worlds.unplaced(), "kingdom"));
        assertTrue(worlds.unplaced().isEmpty());
    }
}
