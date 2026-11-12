package com.dnd.data;

import com.dnd.cloud.CampaignMeta;
import com.dnd.cloud.LocalCloudStore;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.session.TrackedCreature;
import com.dnd.security.PrivateAccount;
import com.dnd.security.PrivateSessionFiles;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import static org.junit.Assert.*;

public class OperationHistoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path campaign() throws Exception {
        Path root = Files.createDirectories(temp.getRoot().toPath().resolve("data").resolve("custom-campaigns")
            .resolve(java.util.UUID.randomUUID().toString()));
        CampaignMeta.ensureId(root);
        return root;
    }

    private PlayerCharacter character(CampaignRepositories repos) {
        PlayerCharacter pc = new PlayerCharacter();
        pc.setId("hero");
        pc.setName("Hero");
        pc.setMaxHitPoints(5000);
        pc.setCurrentHitPoints(2000);
        pc.setMaxMana(10);
        pc.setCurrentMana(10);
        pc.setDescription("Do not journal this description");
        pc.setBackstory("Do not journal this backstory");
        pc.setPassword("private password");
        pc.getSheet().setNotes("Do not journal sheet notes");
        repos.players().add(pc);
        return pc;
    }

    @Test public void capturesMechanicalDeltasAndUndoPreservesOtherFieldsWithoutRepeating() throws Exception {
        Path root = campaign();
        var repos = new CampaignRepositories(root);
        var pc = character(repos);
        pc.applyDamage(7);
        repos.players().save(pc);
        var history = new OperationHistory(root);
        var action = history.list().getFirst();
        assertEquals(1, history.changes(action, null).size());
        assertEquals("currentHitPoints", history.changes(action, null).getFirst().field());
        pc.setCurrentMana(4);
        pc.setDescription("Newer description");
        repos.players().save(pc);
        history.undo(action, null);
        var restored = repos.players().getById("hero");
        assertEquals(2000, restored.getCurrentHitPoints());
        assertEquals(4, restored.getCurrentMana());
        assertEquals("Newer description", restored.getDescription());
        assertTrue(restored.checkPassword("private password"));
        assertThrows(IOException.class, () -> history.undo(action, null));
        assertEquals(action.id(), history.list().getFirst().undoOf());
        try (var files = Files.walk(CampaignRestorePoints.deviceRoot(root).resolveSibling("action-history"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String json = Files.readString(file);
                assertFalse(json.contains("Do not journal"));
                assertFalse(json.contains("Newer description"));
                assertFalse(json.contains("passwordHash"));
                assertFalse(json.contains("passwordSalt"));
            }
        }
    }

    @Test public void changedValuesBlockEntireUndoAndInventoryChangesAreAtomic() throws Exception {
        Path root = campaign();
        var repos = new CampaignRepositories(root);
        var pc = character(repos);
        pc.setItems(List.of(new PlayerCharacter.PlayerItem("potion", null, false)));
        pc.applyDamage(2);
        repos.players().save(pc);
        var history = new OperationHistory(root);
        var action = history.list().getFirst();
        pc.applyDamage(1);
        repos.players().save(pc);
        String current = Files.readString(root.resolve("players").resolve("players.json"));
        assertThrows(IOException.class, () -> history.undo(action, null));
        assertEquals(current, Files.readString(root.resolve("players").resolve("players.json")));
        assertEquals(1, repos.players().getById("hero").getItems().size());
    }

    @Test public void keepsLatestFiveHundredAndRecoversInterruptedCommittedAction() throws Exception {
        Path root = campaign();
        var repos = new CampaignRepositories(root);
        var pc = character(repos);
        for (int i = 0; i < 502; i++) {
            pc.applyDamage(1);
            repos.players().save(pc);
        }
        var history = new OperationHistory(root);
        assertEquals(500, history.list().size());
        var latest = history.list().getFirst();
        Path directory = CampaignRestorePoints.deviceRoot(root).resolveSibling("action-history").resolve(StorylineDrafts.hash(CampaignMeta.idOf(root).orElseThrow()));
        Path pending = directory.resolve(latest.id() + ".pending");
        Files.move(directory.resolve(latest.id() + ".action"), pending);
        assertEquals(500, history.list().size());
        assertFalse(Files.exists(pending));
        assertEquals(latest, history.list().getFirst());
        history.undo(latest, null);
        assertEquals(1499, repos.players().getById("hero").getCurrentHitPoints());
        assertEquals(500, history.list().size());
    }

    @Test public void privateEncounterHistoryRemainsEncryptedAndUndoPreservesNotesAndCurrentGrants() throws Exception {
        Path root = campaign();
        LocalCloudStore store = new LocalCloudStore();
        try (var owner = PrivateAccount.finishSetup(store, PrivateAccount.prepare("owner", "owner@example.test", "Owner", "password"), "password");
             var reader = PrivateAccount.finishSetup(store, PrivateAccount.prepare("reader", "reader@example.test", "Reader", "password"), "password")) {
            StorylineService service = new StorylineService(root, () -> owner);
            service.ensureRoot();
            Path file = service.createFile(service.getRoot(), "Secret.txt");
            service.writeText(file, "Secret storyline never journalled");
            var creature = new TrackedCreature(TrackedCreature.Kind.MONSTER, "secret-dragon", "Hidden dragon", 100, 10, 15, null);
            creature.setNotes("Secret creature notes never journalled");
            service.writeRoster(file, List.of(creature));
            service.setAccess(file, List.of(reader.identity()));
            service = new StorylineService(root, () -> owner);
            var roster = service.readRoster(file);
            roster.getFirst().setCurrentHitPoints(80);
            service.writeRoster(file, roster);
            var history = new OperationHistory(root);
            var action = history.list().getFirst();
            assertNull(action.changes());
            assertFalse(JsonMappers.create().writeValueAsString(action).contains("secret-dragon"));
            assertFalse(JsonMappers.create().writeValueAsString(action).contains("Hidden dragon"));
            assertEquals(1, history.changes(action, reader).size());
            service.writeText(file, "Newer private storyline");
            service.setAccess(file, List.of());
            var header = service.permissions(file);
            assertThrows(SecurityException.class, () -> history.changes(action, reader));
            assertThrows(SecurityException.class, () -> history.undo(action, reader));
            history.undo(action, owner);
            var reopened = new StorylineService(root, () -> owner);
            assertEquals(header, reopened.permissions(file));
            assertEquals("Newer private storyline", reopened.readText(file));
            assertEquals(100, reopened.readRoster(file).getFirst().getCurrentHitPoints());
            assertEquals("Secret creature notes never journalled", reopened.readRoster(file).getFirst().getNotes());
            var correction = history.list().getFirst();
            Files.writeString(file, PrivateSessionFiles.create(
                new PrivateSessionFiles.Content("Replacement", reopened.readRoster(file)), owner));
            assertThrows(SecurityException.class, () -> history.changes(action, owner));
            assertThrows(SecurityException.class, () -> history.undo(correction, owner));
        }
    }

    @Test public void battleUndoUsesStableTokenIdentityAndNeverRewindsTerrainOrNotes() throws Exception {
        Path root = campaign();
        Path file = Files.createDirectories(root.resolve("world")).resolve("maps.json");
        Files.writeString(file, """
            {"maps":[{"id":"map","name":"Map","width":2,"height":1,"grid":[[
            {"occupants":[{"kind":"monster","monster":{"id":"goblin","name":"Goblin"},"position":{"x":0,"y":0},
            "combat":{"maxHitPoints":10,"currentHitPoints":10,"notes":"Secret combat note"}}]},{"occupants":[]}]]}]}
            """);
        var repos = new CampaignRepositories(root);
        var map = repos.maps().getById("map");
        var token = (com.dnd.model.world.map.MonsterToken) map.getCell(0, 0).getOccupants().getFirst();
        String identity = token.getInstanceId();
        token.getCombat().applyDamage(3);
        repos.maps().save(map);
        var history = new OperationHistory(root);
        var action = history.list().getFirst();
        assertEquals(OperationHistory.Kind.MAPS, action.kind());
        map.moveObject(token, 1, 0);
        token.getCombat().setNotes("Newer secret note");
        repos.maps().save(map);
        assertEquals(1, history.list().size());
        history.undo(action, null);
        var current = repos.maps().getById("map");
        assertTrue(current.getCell(0, 0).getOccupants().isEmpty());
        var moved = (com.dnd.model.world.map.MonsterToken) current.getCell(1, 0).getOccupants().getFirst();
        assertEquals(identity, moved.getInstanceId());
        assertEquals(10, moved.getCombat().getCurrentHitPoints());
        assertEquals("Newer secret note", moved.getCombat().getNotes());
    }

    @Test public void interruptedWritesRecoverOrRequireExplicitDiscardWithoutTouchingCurrentData() throws Exception {
        Path root = campaign();
        var repos = new CampaignRepositories(root);
        var pc = character(repos);
        Path players = root.resolve("players").resolve("players.json");
        byte[] before = Files.readAllBytes(players);
        pc.applyDamage(1);
        repos.players().save(pc);
        var history = new OperationHistory(root);
        var action = history.list().getFirst();
        Path directory = CampaignRestorePoints.deviceRoot(root).resolveSibling("action-history")
            .resolve(StorylineDrafts.hash(CampaignMeta.idOf(root).orElseThrow()));
        Path pending = directory.resolve(action.id() + ".pending");
        Path committed = directory.resolve(action.id() + ".action");
        byte[] record = Files.readAllBytes(committed);
        Files.move(committed, pending);
        Files.write(players, before);
        assertTrue(history.list().isEmpty());
        assertFalse(Files.exists(pending));

        Files.write(pending, record);
        var external = JsonMappers.create().readTree(Files.readAllBytes(players));
        ((com.fasterxml.jackson.databind.node.ObjectNode) external.path("players").get(0)).put("currentHitPoints", 1998);
        Files.write(players, JsonMappers.create().writeValueAsBytes(external));
        byte[] externalBytes = Files.readAllBytes(players);
        assertThrows(IOException.class, history::list);
        var interrupted = history.interrupted();
        assertEquals(List.of(action), interrupted);
        assertThrows(DataAccessException.class, () -> repos.players().save(pc));
        assertArrayEquals(externalBytes, Files.readAllBytes(players));
        history.discardInterrupted(interrupted);
        assertTrue(history.list().isEmpty());
        assertArrayEquals(externalBytes, Files.readAllBytes(players));
        repos.players().save(pc);
        assertEquals(1, history.list().size());
    }

    @Test public void historyFailurePreventsDataMutation() throws Exception {
        Path root = campaign();
        var repos = new CampaignRepositories(root);
        var pc = character(repos);
        Path players = root.resolve("players").resolve("players.json");
        byte[] before = Files.readAllBytes(players);
        Path historyRoot = CampaignRestorePoints.deviceRoot(root).resolveSibling("action-history");
        Files.createDirectories(historyRoot.getParent());
        Files.writeString(historyRoot, "Cannot create a journal directory here.");
        pc.applyDamage(1);
        assertThrows(DataAccessException.class, () -> repos.players().save(pc));
        assertArrayEquals(before, Files.readAllBytes(players));
    }

    @Test public void formerlyPublicEncounterRequiresCurrentAccessAndItsCorrectionIsEncrypted() throws Exception {
        Path root = campaign();
        Path file = Files.createDirectories(root.resolve("storyline")).resolve("Encounter.txt");
        Files.writeString(file, "Public session");
        var creature = new TrackedCreature(TrackedCreature.Kind.MONSTER, "goblin", "Goblin", 20, 0, 10, null);
        SessionRosterStore.save(file, List.of(creature));
        creature.setCurrentHitPoints(12);
        SessionRosterStore.save(file, List.of(creature));
        var history = new OperationHistory(root);
        var action = history.list().getFirst();
        LocalCloudStore store = new LocalCloudStore();
        try (var owner = PrivateAccount.finishSetup(store, PrivateAccount.prepare("owner", "owner@example.test", "Owner", "password"), "password")) {
            Files.writeString(file, PrivateSessionFiles.create(new PrivateSessionFiles.Content("Now private", List.of(creature)), owner));
            Files.delete(SessionRosterStore.rosterFile(file));
            assertThrows(SecurityException.class, () -> history.changes(action, null));
            assertThrows(SecurityException.class, () -> history.undo(action, null));
            history.undo(action, owner);
            assertEquals(20, PrivateSessionFiles.read(Files.readString(file), owner).roster().getFirst().getCurrentHitPoints());
            assertEquals("Now private", PrivateSessionFiles.read(Files.readString(file), owner).text());
            assertEquals(OperationHistory.Kind.PRIVATE_ROSTER, history.list().getFirst().kind());
            assertNull(history.list().getFirst().changes());
            assertFalse(Files.exists(SessionRosterStore.rosterFile(file)));
            owner.close();
            assertThrows(SecurityException.class, () -> history.changes(action, owner));
        }
    }

    @Test public void legacyMapMigrationIsStableAndRejectsDuplicateTokenIdentities() throws Exception {
        Path root = campaign();
        Path file = Files.createDirectories(root.resolve("world")).resolve("maps.json");
        Files.writeString(file, """
            {"maps":[{"id":"map","grid":[[{"occupants":[
            {"kind":"npc","npc":{"id":"npc"}},{"kind":"player","playerId":"hero"}]}]]}]}
            """);
        OperationHistory.ensureMapIds(file);
        byte[] migrated = Files.readAllBytes(file);
        var json = JsonMappers.create().readTree(migrated);
        var tokens = json.path("maps").get(0).path("grid").get(0).get(0).path("occupants");
        String identity = tokens.get(0).path("instanceId").asText();
        assertFalse(identity.isBlank());
        assertNotEquals(identity, tokens.get(1).path("instanceId").asText());
        OperationHistory.ensureMapIds(file);
        assertArrayEquals(migrated, Files.readAllBytes(file));
        assertTrue(new OperationHistory(root).list().isEmpty());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tokens.get(1)).put("instanceId", identity);
        Files.write(file, JsonMappers.create().writeValueAsBytes(json));
        byte[] ambiguous = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> OperationHistory.ensureMapIds(file));
        assertArrayEquals(ambiguous, Files.readAllBytes(file));
    }

    @Test public void oversizedActionIsRejectedBeforeReplacingCatalogue() throws Exception {
        Path root = campaign();
        var repos = new CampaignRepositories(root);
        var pc = character(repos);
        Path file = root.resolve("players").resolve("players.json");
        byte[] before = Files.readAllBytes(file);
        pc.setItems(List.of(new PlayerCharacter.PlayerItem("x".repeat(17 * 1024 * 1024), null, false)));
        var failure = assertThrows(DataAccessException.class, () -> repos.players().save(pc));
        assertEquals("Action record is too large.", failure.getCause().getMessage());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(new OperationHistory(root).list().isEmpty());
    }
}
