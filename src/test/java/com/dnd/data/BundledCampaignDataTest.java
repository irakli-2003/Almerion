package com.dnd.data;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/** Every bundled campaign must load cleanly - e.g. no leftover merge-conflict markers in its JSON. */
public class BundledCampaignDataTest {

    private static final Path CAMPAIGNS = Paths.get("src", "main", "resources", "data", "custom-campaigns");

    @Test
    public void jsonFilesHaveNoMergeConflictMarkers() throws IOException {
        try (Stream<Path> files = Files.walk(CAMPAIGNS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    assertFalse("Merge conflict left in " + file,
                        line.startsWith("<<<<<<<") || line.startsWith(">>>>>>>") || line.equals("======="));
                }
            }
        }
    }

    @Test
    public void everyBundledSubclassHasAnOverview() throws IOException {
        List<Path> campaigns;
        try (Stream<Path> dirs = Files.list(CAMPAIGNS)) {
            campaigns = new java.util.ArrayList<>(dirs.filter(Files::isDirectory).toList());
        }
        campaigns.add(Paths.get("src", "main", "resources", "data", "default-campaign"));
        for (Path campaign : campaigns) {
            for (var cls : new CampaignRepositories(campaign).classes().list()) {
                for (String sub : cls.getSubclasses()) {
                    String text = cls.subclassDescription(sub);
                    assertNotNull(campaign.getFileName() + ": " + sub, text);
                    assertTrue(sub, text.length() > 80);
                }
            }
        }
    }

    @Test
    public void everyRepositoryLoads() throws IOException {
        List<Path> campaigns;
        try (Stream<Path> dirs = Files.list(CAMPAIGNS)) {
            campaigns = dirs.filter(Files::isDirectory).toList();
        }
        assertFalse(campaigns.isEmpty());
        for (Path campaign : campaigns) {
            CampaignRepositories repos = new CampaignRepositories(campaign);
            String name = campaign.getFileName().toString();
            assertNotNull(name, repos.players().list());
            assertNotNull(name, repos.classes().list());
            assertNotNull(name, repos.races().list());
            assertNotNull(name, repos.items().list());
            assertNotNull(name, repos.spells().list());
            assertNotNull(name, repos.places().list());
            assertNotNull(name, repos.effects().list());
            assertNotNull(name, repos.damageTypes().list());
            assertNotNull(name, repos.npcs().list());
            assertNotNull(name, repos.monsters().list());
            assertNotNull(name, repos.beasts().list());
            assertNotNull(name, repos.languages().list());
            assertNotNull(name, repos.alchemyIngredients().list());
            assertNotNull(name, repos.books().list());
            assertNotNull(name, repos.dice().list());
            assertNotNull(name, repos.maps().list());
        }
    }

    @Test
    public void artificerContentIsComplete() {
        for (Path campaign : List.of(CAMPAIGNS.resolve("zurabaach"), CAMPAIGNS.getParent().resolve("default-campaign"))) {
            CampaignRepositories repos = new CampaignRepositories(campaign);
            var artificer = repos.classes().getById("artificer");
            assertNotNull(campaign.toString(), artificer);
            assertEquals(com.dnd.model.magic.SpellcastingType.ENERGY, artificer.getSpellcasting());

            List<com.dnd.model.item.crafting.Blueprint> blueprints = repos.items().list().stream()
                .filter(i -> i instanceof com.dnd.model.item.crafting.Blueprint)
                .map(i -> (com.dnd.model.item.crafting.Blueprint) i).toList();
            assertEquals(18, blueprints.size());
            for (var bp : blueprints) {
                if (bp.getProducesChargeLevel() > 0) assertNull(bp.getId(), bp.getProductId());
                else assertNotNull(bp.getId(), repos.items().getById(bp.getProductId()));
                assertFalse(bp.getId() + " needs materials", bp.getMaterials().isEmpty());
                assertFalse(bp.getId() + " needs tools", bp.getToolIds().isEmpty());
                bp.getMaterials().forEach(m -> assertNotNull(bp.getId() + " " + m.getItemId(), repos.items().getById(m.getItemId())));
                bp.getToolIds().forEach(t -> assertNotNull(bp.getId() + " " + t, repos.items().getById(t)));
            }
            for (var item : repos.items().list()) {
                if (item instanceof com.dnd.model.item.crafting.Device d && d.getSpellId() != null) {
                    assertNotNull(d.getId() + " -> " + d.getSpellId(), repos.spells().getById(d.getSpellId()));
                }
            }
            for (var spell : repos.spells().list()) {
                assertFalse(spell.getId() + " needs components", spell.getComponents() == null || spell.getComponents().isEmpty());
            }
            for (var cls : repos.classes().list()) {
                cls.getDefaultSpells().forEach(s -> assertNotNull(cls.getId() + " " + s, repos.spells().getById(s)));
                cls.getDefaultItems().forEach(i -> assertNotNull(cls.getId() + " " + i, repos.items().getById(i)));
            }
        }
        CampaignRepositories zurabaach = new CampaignRepositories(CAMPAIGNS.resolve("zurabaach"));
        var toren = zurabaach.players().getById("player-toren");
        assertNotNull(toren);
        assertEquals(com.dnd.model.magic.CastingResource.ENERGY_CHARGES, toren.getCastingResource());
        assertEquals(13, toren.getKnownBlueprints().size());
        assertTrue(com.dnd.model.crafting.Workshop.furnaceTier(toren, zurabaach.items()::getById) >= 2);
    }
}
