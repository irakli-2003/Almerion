package com.dnd.data;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionSnapshotTest {

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static void write(Path file, String json) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    @Test
    public void captureCarriesOnlyWhatPlayersNeed() throws Exception {
        Path root = temp.newFolder("camp").toPath();
        CampaignPaths paths = new CampaignPaths(root);
        write(paths.playersFile(), "{\"players\":[{\"id\":\"p1\",\"name\":\"Hero\",\"passwordHash\":\"h\","
            + "\"passwordSalt\":\"s\",\"inventory\":[\"sword\"]}]}");
        write(paths.itemsFile(), "{\"items\":[{\"id\":\"sword\",\"spellIds\":[\"bolt\"]},{\"id\":\"secret-relic\"}]}");
        write(paths.spellsFile(), "{\"spells\":[{\"id\":\"bolt\",\"effectIds\":[\"shock\"]},{\"id\":\"villain-curse\"}]}");
        write(paths.effectsFile(), "{\"effects\":[{\"id\":\"shock\"},{\"id\":\"doom\"}]}");
        write(paths.npcsFile(), "{\"npcs\":[{\"id\":\"traitor\"}]}");
        write(paths.mapsFile(), "{\"maps\":[{\"id\":\"lair\"}]}");
        write(paths.monstersFile(), "{\"monsters\":[{\"id\":\"boss\"}]}");

        JsonNode snap = JsonMappers.create().readTree(SessionSnapshot.capture(root, "Camp"));

        assertEquals("Camp", snap.get("campaign").asText());
        JsonNode hero = snap.get("players").get(0);
        assertFalse(hero.has("passwordHash"));
        assertFalse(hero.has("passwordSalt"));
        assertNull(snap.get("npcs"));
        assertNull(snap.get("maps"));
        assertNull(snap.get("monsters"));
        assertEquals(1, snap.get("items").size());
        assertEquals("sword", snap.get("items").get(0).get("id").asText());
        assertEquals(1, snap.get("spells").size());
        assertEquals("bolt", snap.get("spells").get(0).get("id").asText());
        assertEquals(1, snap.get("effects").size());
        assertEquals("shock", snap.get("effects").get(0).get("id").asText());
    }

    @Test
    public void hydrateWritesEmptyFilesForCatalogsLeftOut() throws Exception {
        Path root = temp.newFolder("camp2").toPath();
        write(new CampaignPaths(root).playersFile(), "{\"players\":[{\"id\":\"p1\",\"name\":\"Hero\"}]}");
        Path joined = SessionSnapshot.hydrate(SessionSnapshot.capture(root, "Camp"), "test-" + System.nanoTime());
        try {
            CampaignPaths jp = new CampaignPaths(joined);
            assertTrue(Files.exists(jp.npcsFile()));
            assertTrue(Files.readString(jp.npcsFile()).contains("\"npcs\""));
            assertEquals(1, new CampaignRepositories(joined).players().list().size());
        } finally {
            try (var walk = Files.walk(joined)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
