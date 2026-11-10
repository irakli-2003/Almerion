package com.dnd.data;

import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.stats.*;
import com.dnd.model.rules.FeatureRules;
import com.dnd.model.rules.SheetValues;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public class StatSchemasTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void existingCampaignsKeepDefaultsWithoutWritingAnything() {
        var root = temp.getRoot().toPath();
        var schema = StatSchemas.load(root);
        assertEquals(6, schema.getAbilities().size());
        assertEquals(18, schema.getSkills().size());
        assertFalse(Files.exists(root.resolve("world").resolve("stats.json")));
    }

    @Test public void removedAbilitiesRequireRuleAndSkillRemapping() {
        var schema = new StatSchema();
        schema.getAbilities().removeIf(a -> a.id().equals("dexterity"));
        assertThrows(IllegalArgumentException.class, schema::validate);
        schema.getAbilities().add(new StatSchema.Ability("agility", "Agility", "AGI"));
        schema.getAbilityBindings().put("dexterity", "agility");
        assertThrows(IllegalArgumentException.class, schema::validate);
        schema.getSkills().stream().filter(s -> s.ability().equals("dexterity")).forEach(s -> s.setAbility("agility"));
        schema.validate();
        schema.getSkills().removeIf(s -> s.id().equals("perception"));
        assertThrows(IllegalArgumentException.class, schema::validate);
        schema.getSkillBindings().put("perception", "insight");
        schema.validate();
    }

    @Test public void customValuesAndRulesPersistWithoutLosingRetiredValues() throws Exception {
        var root = temp.getRoot().toPath();
        var schema = new StatSchema();
        schema.getAbilities().removeIf(a -> a.id().equals("dexterity"));
        schema.getAbilities().add(new StatSchema.Ability("agility", "Agility", "AGI"));
        schema.getAbilityBindings().put("dexterity", "agility");
        schema.getSkills().stream().filter(s -> s.ability().equals("dexterity")).forEach(s -> s.setAbility("agility"));
        schema.getSkills().add(new StatSchema.Skill("sailing", "Sailing", "agility"));
        schema.getSkills().removeIf(s -> s.id().equals("animalHandling"));
        StatSchemas.save(root, schema);
        var pc = new PlayerCharacter();
        pc.setId("hero");
        pc.setName("Hero");
        var stats = new CoreStats(10, 12, 10, 10, 10, 10);
        stats.configure(schema);
        stats.score("agility", 18);
        pc.setStats(stats);
        pc.getSkills().configure(schema);
        pc.getSkills().set("sailing", -2);
        var repos = new CampaignRepositories(root);
        repos.players().add(pc);
        var loaded = repos.players().getById("hero");
        assertEquals(18, loaded.getStats().getDexterity());
        assertEquals(12, loaded.getStats().score("dexterity"));
        assertEquals(4, SheetValues.initiative(loaded));
        assertEquals(4, FeatureRules.mod(loaded.getStats(), "dex"));
        assertEquals(4, SheetValues.savingThrow(loaded, null, "agility"));
        assertEquals(-2, loaded.getSkills().get("sailing"));
        assertEquals(18, loaded.getSkills().schema().getSkills().size());
        assertFalse(loaded.getSkills().schema().getSkills().stream().anyMatch(s -> s.id().equals("animalHandling")));
        String json = Files.readString(new CampaignPaths(root).playersFile());
        assertFalse(json.contains("\"schema\""));
        assertTrue(json.contains("\"agility\""));
        assertEquals(18, JsonMappers.forCampaign(root).readValue(
            JsonMappers.create().writeValueAsString(loaded.getStats()), CoreStats.class).getDexterity());
    }

    @Test public void removingReferencedCustomSaveRequiresExplicitAliasAndFailurePreservesFile() throws Exception {
        var root = temp.getRoot().toPath();
        var schema = new StatSchema();
        schema.getAbilities().add(new StatSchema.Ability("luck", "Luck", "LCK"));
        StatSchemas.save(root, schema);
        var spell = new com.dnd.model.magic.Spell();
        spell.setId("test");
        spell.getChecks().setSavingThrow("luck");
        new CampaignRepositories(root).spells().add(spell);
        String before = Files.readString(root.resolve("world").resolve("stats.json"));
        schema.getAbilities().removeIf(a -> a.id().equals("luck"));
        assertThrows(IllegalArgumentException.class, () -> StatSchemas.save(root, schema));
        assertEquals(before, Files.readString(root.resolve("world").resolve("stats.json")));
        schema.getAbilityBindings().put("luck", "wisdom");
        StatSchemas.save(root, schema);
        assertEquals("wisdom", StatSchemas.load(root).resolveAbility("luck"));
    }

    @Test public void remappedRolesAndVisibleFieldsAreDistinctAndClassSavesFollowRules() {
        var pc = new PlayerCharacter();
        var schema = new StatSchema();
        schema.getAbilities().add(new StatSchema.Ability("agility", "Agility", "AGI"));
        schema.getAbilityBindings().put("dexterity", "agility");
        var stats = new CoreStats(10, 12, 10, 10, 10, 10);
        stats.configure(schema);
        stats.score("agility", 18);
        pc.setStats(stats);
        var cls = new com.dnd.model.character.CharacterClass();
        cls.setSavingThrowBonuses(java.util.Map.of("dexterity", 1));
        assertEquals(4, SheetValues.initiative(pc));
        assertEquals(1, SheetValues.mod(pc, "dexterity"));
        assertEquals(6, SheetValues.savingThrow(pc, cls, "agility"));
        assertEquals(1, SheetValues.savingThrow(pc, cls, "dexterity"));
        pc.getSheet().setSpellcastingAbility("dexterity");
        assertEquals(3, SheetValues.spellAttackBonus(pc, cls));
        pc.getSheet().setSpellcastingAbility("agility");
        assertEquals(6, SheetValues.spellAttackBonus(pc, cls));
    }

    @Test public void customSchemaFlowsToLanAndPaginatedPdfWithoutCurrencyFields() throws Exception {
        var root = temp.getRoot().toPath();
        var schema = new StatSchema();
        for (int i = 0; i < 12; i++) schema.getAbilities().add(
            new StatSchema.Ability("focus" + i, "Focus " + i, "F" + i));
        for (int i = 0; i < 65; i++) schema.getSkills().add(
            new StatSchema.Skill("craft" + i, "Craft " + i, "focus0"));
        StatSchemas.save(root, schema);
        var repos = new CampaignRepositories(root);
        var pc = new PlayerCharacter();
        pc.setId("hero");
        pc.setName("Hero");
        pc.setStats(new CoreStats(10, 10, 10, 10, 10, 10));
        pc.getStats().configure(schema);
        pc.getStats().score("focus0", 18);
        pc.getSkills().set("craft64", 7);
        pc.getSheet().setSpellcastingAbility("focus0");
        pc.getSheet().setGold(321);
        var money = new com.dnd.model.item.crafting.Material();
        money.setType("material");
        money.setId("money");
        money.setName("Inventory Money");
        repos.items().add(money);
        pc.setItems(List.of(new PlayerCharacter.PlayerItem("money", new PlayerCharacter.ItemCondition(100), false)));
        repos.players().add(pc);
        pc = repos.players().getById("hero");
        var json = com.dnd.lan.LanSheet.build(pc, repos, "Test", s -> s, JsonMappers.create());
        assertEquals(18, json.path("abilities").size());
        assertEquals(83, json.path("skills").size());
        assertEquals("F0", json.path("casting").path("ability").asText());
        assertEquals(14, json.path("casting").path("saveDc").asInt());
        assertEquals("+7", json.path("skills").get(82).path("bonus").asText());
        assertFalse(json.has("coins"));
        assertEquals("Inventory Money", json.path("items").get(0).path("name").asText());
        var output = root.resolve("sheet.pdf");
        com.dnd.ui.print.CharacterSheetPdf.write(pc, repos, output);
        try (var doc = org.apache.pdfbox.Loader.loadPDF(output.toFile())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertTrue(text.contains("Focus 11"));
            assertTrue(text.contains("Craft 64"));
            assertTrue(text.contains("Inventory Money"));
            assertFalse(text.contains("321"));
            assertTrue(doc.getNumberOfPages() > 2);
        }
    }

    @Test public void jsonRejectsInvalidScoresAndSchemasRejectAmbiguousIds() {
        var mapper = JsonMappers.create();
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
            () -> mapper.readValue("{\"strength\":31}", CoreStats.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
            () -> mapper.readValue("{\"custom\":{\"luck\":0}}", CoreStats.class));
        var schema = new StatSchema();
        schema.getAbilities().add(new StatSchema.Ability("str", "Other Strength", "OTH"));
        assertThrows(IllegalArgumentException.class, schema::validate);
    }
}
