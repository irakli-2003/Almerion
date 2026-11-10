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

    @Test public void upgradeDefinitionsAndEarnedRewardsReachJoinedSheets() throws Exception {
        Path root = temp.newFolder("upgrades").toPath();
        var repos = new CampaignRepositories(root);
        var cls = new com.dnd.model.character.CharacterClass();
        cls.setId("barbarian");
        cls.getUpgradeTree().getNodes().getFirst().getReward().getSpellIds().add("upgrade-ability");
        repos.classes().save(cls);
        var spell = new com.dnd.model.magic.Spell();
        spell.setId("upgrade-ability"); spell.setLevel(1);
        repos.spells().save(spell);
        var pc = new com.dnd.model.character.PlayerCharacter();
        pc.setId("hero"); pc.setClassId("barbarian"); pc.setLevel(1);
        pc.setMaxHitPoints(10); pc.setCurrentHitPoints(10);
        com.dnd.model.character.UpgradeRules.initialize(pc, cls);
        com.dnd.model.character.Progression.apply(pc, com.dnd.model.character.Progression.plan(pc, cls));
        com.dnd.model.character.UpgradeRules.unlock(pc, cls, "potency-1", repos.spells()::getById);
        repos.players().save(pc);
        Path joined = SessionSnapshot.hydrate(SessionSnapshot.capture(root, "Camp"), "test-upgrades-" + System.nanoTime());
        try {
            var received = new CampaignRepositories(joined);
            var hero = received.players().getById("hero");
            assertEquals(1, hero.getUpgrades().getEarned());
            assertEquals(0, com.dnd.model.character.UpgradeRules.available(hero));
            assertEquals(1, com.dnd.model.character.UpgradeRules.bonus(hero,
                com.dnd.model.character.UpgradeTree.Reward::getRageDamage));
            assertEquals(14, received.classes().getById("barbarian").getUpgradeTree().getNodes().size());
            assertEquals("upgrade-ability", received.spells().getById("upgrade-ability").getId());
        } finally {
            try (var walk = Files.walk(joined)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

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

    @Test public void liveSessionCarriesCustomStatDefinitionsAndBindings() throws Exception {
        Path root = temp.newFolder("custom-stats").toPath();
        var schema = new com.dnd.model.character.stats.StatSchema();
        schema.getAbilities().add(new com.dnd.model.character.stats.StatSchema.Ability("agility", "Agility", "AGI"));
        schema.getAbilityBindings().put("dexterity", "agility");
        StatSchemas.save(root, schema);
        var pc = new com.dnd.model.character.PlayerCharacter();
        pc.setId("hero");
        pc.setStats(new com.dnd.model.character.stats.CoreStats(10, 10, 10, 10, 10, 10));
        pc.getStats().score("agility", 18);
        new CampaignRepositories(root).players().add(pc);
        Path joined = SessionSnapshot.hydrate(SessionSnapshot.capture(root, "Camp"), "test-" + System.nanoTime());
        try {
            var received = new CampaignRepositories(joined).players().getById("hero");
            assertEquals(7, received.getStats().schema().getAbilities().size());
            assertEquals(4, com.dnd.model.rules.SheetValues.initiative(received));
        } finally {
            try (var walk = Files.walk(joined)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
