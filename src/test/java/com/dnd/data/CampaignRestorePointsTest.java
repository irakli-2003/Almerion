package com.dnd.data;

import com.dnd.cloud.CampaignMeta;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class CampaignRestorePointsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void capturesLocalBytesRestoresSelectedFilesAndKeepsPreRestoreCopy() throws Exception {
        Path campaign = temp.newFolder("campaign").toPath();
        Path file = campaign.resolve("notes.txt");
        Files.writeString(file, "Original notes");
        Path git = Files.createDirectory(campaign.resolve(".git"));
        Files.writeString(git.resolve("config"), "machine credential fixture");
        var points = new CampaignRestorePoints(temp.newFolder("device").toPath(), campaign);
        var old = points.capture("Before import");
        Files.writeString(file, "Newer notes");
        Files.writeString(campaign.resolve("new.txt"), "Keep this local-only file");
        var plan = points.plan(old.id());
        assertEquals("Original notes", points.preview(plan, "notes.txt", null));
        assertFalse(plan.files().stream().anyMatch(entry -> entry.path().startsWith(".git/")));
        assertEquals("Newer notes", Files.readString(file));
        var safety = points.restore(plan, List.of("notes.txt"), null);
        assertEquals("Original notes", Files.readString(file));
        assertEquals("Keep this local-only file", Files.readString(campaign.resolve("new.txt")));
        assertEquals("Newer notes", points.preview(points.plan(safety.id()), "notes.txt", null));
        assertEquals(2, points.list().size());
        assertEquals("machine credential fixture", Files.readString(git.resolve("config")));
    }

    @Test public void retainsExactlyLatestThreeAndRejectsChangedSource() throws Exception {
        Path campaign = temp.newFolder("campaign").toPath();
        Path file = campaign.resolve("notes.txt");
        Files.writeString(file, "notes");
        var points = new CampaignRestorePoints(temp.newFolder("device").toPath(), campaign);
        for (int i = 0; i < 5; i++) points.capture("Snapshot " + i);
        assertEquals(List.of("Snapshot 4", "Snapshot 3", "Snapshot 2"),
            points.list().stream().map(CampaignRestorePoints.Point::reason).toList());
        var plan = points.plan(points.list().getFirst().id());
        Files.writeString(file, "Changed after preview");
        assertThrows(IOException.class, () -> points.restore(plan, List.of("notes.txt"), null));
        assertEquals("Changed after preview", Files.readString(file));
        assertEquals(3, points.list().size());
        assertThrows(IllegalArgumentException.class, () -> points.plan("../another-campaign"));
        assertThrows(IllegalArgumentException.class, () -> new CampaignRestorePoints(campaign.resolve("backups"), campaign));
        String oldest = points.list().getLast().id();
        points.restore(points.plan(oldest), List.of("notes.txt"), null);
        assertEquals("notes", Files.readString(file));
        assertEquals(3, points.list().size());
        assertFalse(points.list().stream().anyMatch(point -> point.id().equals(oldest)));
    }

    @Test public void startupCleanupTrimsLegacyStoresWithoutCapturingOrTouchingCampaignFiles() throws Exception {
        Path device = temp.newFolder("device").toPath();
        Path live = temp.newFolder("live").toPath();
        Path deleted = temp.newFolder("deleted").toPath();
        Files.writeString(live.resolve("notes.txt"), "Keep my current notes");
        var livePoints = new CampaignRestorePoints(device, live);
        var deletedPoints = new CampaignRestorePoints(device, deleted);
        var livePoint = livePoints.capture("Live");
        var deletedPoint = deletedPoints.capture("Deleted");
        for (var point : List.of(livePoint, deletedPoint)) {
            Path store = device.resolve(StorylineDrafts.hash(point.campaignId()));
            Path original = store.resolve(point.id() + ".point");
            for (int i = 1; i < 20; i++) {
                long timestamp = point.createdAt() - i;
                String id = timestamp + "-" + java.util.UUID.randomUUID();
                Path legacy = Files.createDirectory(store.resolve(id + ".point"));
                Files.copy(original.resolve("campaign.zip"), legacy.resolve("campaign.zip"));
                var metadata = new CampaignRestorePoints.Point(1, id, timestamp, "Legacy " + i,
                    point.campaignPath(), point.campaignId(), point.name(), null);
                JsonMappers.create().writeValue(legacy.resolve("point.json").toFile(), metadata);
            }
        }
        Files.delete(deleted.resolve(CampaignMeta.FILE_NAME));
        Files.delete(deleted);
        Path unrelated = Files.writeString(device.resolve("keep.txt"), "Keep");

        CampaignRestorePoints.enforceDeviceRetention(device);
        CampaignRestorePoints.enforceDeviceRetention(device);

        assertEquals(List.of("Live", "Legacy 1", "Legacy 2"),
            livePoints.list().stream().map(CampaignRestorePoints.Point::reason).toList());
        assertEquals(List.of("Deleted", "Legacy 1", "Legacy 2"),
            deletedPoints.list().stream().map(CampaignRestorePoints.Point::reason).toList());
        assertEquals("Keep my current notes", Files.readString(live.resolve("notes.txt")));
        assertEquals("Keep", Files.readString(unrelated));
        assertFalse(Files.exists(deleted));
    }

    @Test public void missingCampaignRestoresIdentityWithoutRestoringGitInternals() throws Exception {
        Path campaign = temp.newFolder("campaign").toPath();
        Path file = campaign.resolve("notes.txt");
        Files.writeString(file, "Recover deleted notes");
        var points = new CampaignRestorePoints(temp.newFolder("device").toPath(), campaign);
        var point = points.capture("Before campaign deletion");
        String identity = CampaignMeta.idOf(campaign).orElseThrow();
        Files.delete(file);
        Files.delete(campaign.resolve(CampaignMeta.FILE_NAME));
        Files.delete(campaign);
        var plan = points.plan(point.id());
        assertNull(points.restore(plan, List.of("notes.txt"), null));
        assertEquals("Recover deleted notes", Files.readString(file));
        assertEquals(identity, CampaignMeta.idOf(campaign).orElseThrow());
        CampaignMeta.assignNewId(campaign);
        assertThrows(IOException.class, () -> points.plan(point.id()));
    }

    @Test public void corruptedSnapshotCannotReplaceCurrentFile() throws Exception {
        Path campaign = temp.newFolder("campaign").toPath();
        Files.writeString(campaign.resolve("notes.txt"), "Old");
        Path device = temp.newFolder("device").toPath();
        var points = new CampaignRestorePoints(device, campaign);
        var point = points.capture("Before change");
        Files.writeString(campaign.resolve("notes.txt"), "Current");
        var plan = points.plan(point.id());
        Path archive;
        try (var files = Files.walk(device)) {
            archive = files.filter(path -> path.getFileName().toString().equals("campaign.zip")).findFirst().orElseThrow();
        }
        Path replacement = device.resolve("corrupt.zip");
        try (ZipFile source = new ZipFile(archive.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(replacement))) {
            for (ZipEntry entry : java.util.Collections.list(source.entries())) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().equals("campaign/notes.txt")) output.write("Bad".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                else try (var input = source.getInputStream(entry)) { input.transferTo(output); }
                output.closeEntry();
            }
        }
        Files.move(replacement, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IOException.class, () -> points.restore(plan, List.of("notes.txt"), null));
        assertEquals("Current", Files.readString(campaign.resolve("notes.txt")));
    }

    @Test public void campaignRenameKeepsItsHistoryAndDeletionCreatesARecoverablePoint() throws Exception {
        var storage = new com.dnd.cli.storage.CampaignStorage(temp.getRoot().toPath());
        Path campaign = storage.createCampaignFromDefault("old-name", true).getPath();
        Files.writeString(campaign.resolve("notes.txt"), "Keep my notes");
        var beforeRename = CampaignRestorePoints.forCampaign(campaign).capture("Manual restore point");
        storage.renameCampaign("old-name", "new-name");
        Path renamed = storage.resolveCustomCampaignPath("new-name");
        var renamedStore = CampaignRestorePoints.forCampaign(renamed);
        assertEquals(beforeRename.id(), renamedStore.list().getFirst().id());
        assertEquals("Keep my notes", renamedStore.preview(renamedStore.plan(beforeRename.id()), "notes.txt", null));
        java.util.concurrent.atomic.AtomicBoolean registrationRemoved = new java.util.concurrent.atomic.AtomicBoolean();
        assertTrue(storage.deleteCampaign("new-name", () -> {
            try {
                assertEquals("Before campaign deletion", renamedStore.list().getFirst().reason());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            assertTrue(Files.exists(renamed.resolve("notes.txt")));
            registrationRemoved.set(true);
        }, "creator-uid"));
        assertTrue(registrationRemoved.get());
        assertFalse(Files.exists(renamed));
        var found = CampaignRestorePoints.discover(CampaignRestorePoints.deviceRoot(renamed));
        var latest = found.getFirst();
        assertEquals("Before campaign deletion", latest.reason());
        assertEquals("creator-uid", latest.creatorUid());
        var deleted = CampaignRestorePoints.forPoint(CampaignRestorePoints.deviceRoot(renamed), renamed, latest);
        deleted.restore(deleted.plan(latest.id()), List.of("notes.txt"), null);
        assertEquals("Keep my notes", Files.readString(renamed.resolve("notes.txt")));
    }

    @Test public void failedBackupPreventsDeletionAndCatalogueDeletionHasAutomaticHistory() throws Exception {
        var storage = new com.dnd.cli.storage.CampaignStorage(temp.getRoot().toPath());
        Path campaign = storage.createCampaignFromDefault("campaign", true).getPath();
        CampaignMeta.ensureId(campaign);
        Path npcs = campaign.resolve("world").resolve("npcs.json");
        Files.writeString(npcs, "{\"npcs\":[{\"id\":\"npc\",\"name\":\"Old foe\"}]}");
        var repos = new CampaignRepositories(campaign);
        assertTrue(repos.npcs().delete("npc"));
        var points = CampaignRestorePoints.forCampaign(campaign);
        assertEquals("Before deleting a catalogue entry", points.list().getFirst().reason());
        assertTrue(points.preview(points.plan(points.list().getFirst().id()), "world/npcs.json", null).contains("Old foe"));
        Path root = CampaignRestorePoints.deviceRoot(campaign);
        Path blocked = root.getParent().resolve("blocked");
        Files.move(root, blocked);
        Files.writeString(root, "A file blocks the backup directory");
        java.util.concurrent.atomic.AtomicBoolean registrationRemoved = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(DataAccessException.class,
            () -> storage.deleteCampaign("campaign", () -> registrationRemoved.set(true), "creator-uid"));
        assertFalse(registrationRemoved.get());
        assertTrue(Files.exists(npcs));
    }
}
