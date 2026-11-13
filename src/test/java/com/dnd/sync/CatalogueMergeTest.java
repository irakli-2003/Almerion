package com.dnd.sync;

import com.dnd.data.JsonMappers;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.*;

public class CatalogueMergeTest {
    private static CatalogueMerge.Result merge(String before, String mine, String remote,
                                                Map<String, CampaignRepo.Choice> choices) throws Exception {
        return CatalogueMerge.merge("players/players.json", bytes(before), bytes(mine), bytes(remote), choices).orElseThrow();
    }

    private static byte[] bytes(String text) { return text == null ? null : text.getBytes(StandardCharsets.UTF_8); }

    @Test public void independentCharactersNestedFieldsAdditionsAndDeletionsArePreserved() throws Exception {
        var merged = merge(
            "{\"players\":[{\"id\":\"a\",\"stats\":{\"hp\":10,\"mana\":5}},{\"id\":\"b\"}],\"future\":{\"enabled\":false}}",
            "{\"players\":[{\"id\":\"a\",\"stats\":{\"hp\":7,\"mana\":5}},{\"id\":\"b\"},{\"id\":\"c\"}],\"future\":{\"enabled\":false}}",
            "{\"players\":[{\"id\":\"a\",\"stats\":{\"hp\":10,\"mana\":9}},{\"id\":\"d\"}],\"future\":{\"enabled\":true}}", Map.of());
        assertTrue(merged.conflicts().isEmpty());
        var root = JsonMappers.create().readTree(merged.bytes());
        assertEquals(7, root.at("/players/0/stats/hp").asInt());
        assertEquals(9, root.at("/players/0/stats/mana").asInt());
        assertEquals("c", root.at("/players/1/id").asText());
        assertEquals("d", root.at("/players/2/id").asText());
        assertEquals(3, root.path("players").size());
        assertTrue(root.at("/future/enabled").asBoolean());
    }

    @Test public void competingFieldsHaveSeparateRevisionBoundChoicesAndKeepOtherEdits() throws Exception {
        String base = "{\"players\":[{\"id\":\"a/b~c\",\"name\":\"Hero\",\"hp\":10,\"mana\":5,\"level\":1}]}";
        String mine = "{\"players\":[{\"id\":\"a/b~c\",\"name\":\"Hero\",\"hp\":7,\"mana\":2,\"level\":2}]}";
        String remote = "{\"players\":[{\"id\":\"a/b~c\",\"name\":\"Hero\",\"hp\":4,\"mana\":1,\"level\":1}]}";
        var conflicts = merge(base, mine, remote, Map.of()).conflicts();
        assertEquals(2, conflicts.size());
        assertEquals("/players/a~1b~0c/hp", conflicts.get(0).pointer());
        assertEquals("Hero [a/b~c]", conflicts.get(0).entity());
        Map<String, CampaignRepo.Choice> choices = Map.of(conflicts.get(0).key(), CampaignRepo.Choice.GITHUB,
            conflicts.get(1).key(), CampaignRepo.Choice.MINE);
        var settled = merge(base, mine, remote, choices);
        assertTrue(settled.conflicts().isEmpty());
        var hero = JsonMappers.create().readTree(settled.bytes()).path("players").get(0);
        assertEquals(4, hero.path("hp").asInt());
        assertEquals(2, hero.path("mana").asInt());
        assertEquals(2, hero.path("level").asInt());
        assertEquals(2, merge(base, mine, remote.replace("\"hp\":4", "\"hp\":3"), choices).conflicts().size());
        assertEquals(2, merge(base, mine, remote, Map.of(conflicts.getFirst().key(), CampaignRepo.Choice.BOTH)).conflicts().size());
    }

    @Test public void deleteVersusEditAndCollidingNewIdsRequireEntryChoice() throws Exception {
        var deleted = merge("{\"players\":[{\"id\":\"a\",\"hp\":10}]}", "{\"players\":[]}",
            "{\"players\":[{\"id\":\"a\",\"hp\":9}]}", Map.of());
        var field = deleted.conflicts().getFirst();
        assertEquals("/players/a", field.pointer());
        assertNull(field.mine());
        var kept = merge("{\"players\":[{\"id\":\"a\",\"hp\":10}]}", "{\"players\":[]}",
            "{\"players\":[{\"id\":\"a\",\"hp\":9}]}", Map.of(field.key(), CampaignRepo.Choice.GITHUB));
        assertEquals(9, JsonMappers.create().readTree(kept.bytes()).at("/players/0/hp").asInt());
        assertEquals(1, merge(null, "{\"players\":[{\"id\":\"a\",\"hp\":10}]}",
            "{\"players\":[{\"id\":\"a\",\"hp\":9}]}", Map.of()).conflicts().size());
    }

    @Test public void orderedListsAreAtomicAndJsonNullDiffersFromMissing() throws Exception {
        var result = merge("{\"players\":[{\"id\":\"a\",\"items\":[1],\"note\":\"old\"}]}",
            "{\"players\":[{\"id\":\"a\",\"items\":[1,2],\"note\":null}]}",
            "{\"players\":[{\"id\":\"a\",\"items\":[3,1]}]}", Map.of());
        assertEquals(2, result.conflicts().size());
        assertEquals("/players/a/items", result.conflicts().get(0).pointer());
        assertEquals("null", result.conflicts().get(1).mine());
        assertNull(result.conflicts().get(1).github());
    }

    @Test public void unsupportedOrAmbiguousShapesNeverGetAnAutomaticSemanticMerge() throws Exception {
        assertTrue(CatalogueMerge.merge("players/players.json", bytes("{\"players\":[]}"),
            bytes("{\"players\":[{\"id\":\"a\"},{\"id\":\"a\"}]}"), bytes("{\"players\":[]}"), Map.of()).isEmpty());
        assertTrue(CatalogueMerge.merge("world/npcs.json", bytes("[]"), bytes("[1]"), bytes("[2]"), Map.of()).isEmpty());
        assertTrue(CatalogueMerge.merge("storyline/secret.json", bytes("{}"), bytes("{}"), bytes("{}"), Map.of()).isEmpty());
        assertThrows(java.io.IOException.class, () -> CatalogueMerge.merge("players/players.json", bytes("{\"players\":[]}"),
            bytes("{\"players\":[],\"players\":[]}"), bytes("{\"players\":[]}"), Map.of()));
    }
}
