package com.dnd.data;

import com.dnd.cli.storage.CampaignStorage;
import com.dnd.cloud.CampaignMeta;
import com.dnd.sync.ManualFiles;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.RandomAccessFile;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class CampaignArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path source(String name) throws Exception {
        Path root = temp.newFolder(name).toPath();
        CampaignMeta.ensureId(root);
        Files.createDirectories(root.resolve("world"));
        Files.writeString(root.resolve("world").resolve("npcs.json"), "[\"NPC\"]");
        return root;
    }

    private CampaignStorage storage(String name) throws Exception {
        CampaignStorage storage = new CampaignStorage(temp.newFolder(name).toPath());
        storage.ensureInitialized();
        return storage;
    }

    @Test public void fullExportIncludesLargeAssetsIdentityAndNestedContentButNoGitInternals() throws Exception {
        Path root = source("campaign"), zip = temp.getRoot().toPath().resolve("campaign.zip");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"), "machine-specific");
        Files.createDirectories(root.resolve("music"));
        try (RandomAccessFile file = new RandomAccessFile(root.resolve("music").resolve("theme.mp3").toFile(), "rw")) {
            file.setLength(ManualFiles.MAX_GIT_BYTES + 1);
        }
        CampaignArchive.exportCampaign(root, zip);
        try (ZipFile file = new ZipFile(zip.toFile())) {
            assertNull(file.getEntry("campaign/.git/config"));
            assertEquals(ManualFiles.MAX_GIT_BYTES + 1, file.getEntry("campaign/music/theme.mp3").getSize());
        }
        var result = CampaignArchive.importCampaign(storage("other-device"), zip);
        assertEquals(CampaignMeta.idOf(root), CampaignMeta.idOf(result.campaign()));
        assertEquals("[\"NPC\"]", Files.readString(result.campaign().resolve("world").resolve("npcs.json")));
        assertEquals(ManualFiles.hash(root.resolve("music").resolve("theme.mp3")),
            ManualFiles.hash(result.campaign().resolve("music").resolve("theme.mp3")));
        assertFalse(Files.exists(result.campaign().resolve(".git")));
    }

    @Test public void newerImportsUpdateSameIdDespiteRenamingAndKeepLocalOnlyAndNewerFiles() throws Exception {
        Path root = source("campaign"), zip = temp.getRoot().toPath().resolve("campaign.zip");
        Files.writeString(root.resolve("story.txt"), "old story");
        Files.setLastModifiedTime(root.resolve("story.txt"), FileTime.fromMillis(10000));
        CampaignArchive.exportCampaign(root, zip);
        CampaignStorage device = storage("other-device");
        Path imported = CampaignArchive.importCampaign(device, zip).campaign();
        Path renamed = imported.resolveSibling("renamed");
        Files.move(imported, renamed);
        Files.writeString(renamed.resolve("local-only.txt"), "retain this");
        Files.writeString(renamed.resolve("world").resolve("npcs.json"), "newer local NPCs");
        Files.setLastModifiedTime(renamed.resolve("world").resolve("npcs.json"), FileTime.fromMillis(30000));
        Files.createDirectories(renamed.resolve(".git"));
        Files.writeString(renamed.resolve(".git").resolve("config"), "keep local Git config");
        Files.writeString(root.resolve("story.txt"), "new story");
        Files.setLastModifiedTime(root.resolve("story.txt"), FileTime.fromMillis(20000));
        Files.setLastModifiedTime(root.resolve("world").resolve("npcs.json"), FileTime.fromMillis(20000));
        Files.writeString(root.resolve("new.txt"), "new file");
        CampaignArchive.exportCampaign(root, zip);
        var result = CampaignArchive.importCampaign(device, zip);
        assertEquals(renamed, result.campaign());
        assertEquals(1, device.listCustomCampaigns().size());
        assertEquals("new story", Files.readString(renamed.resolve("story.txt")));
        assertEquals("retain this", Files.readString(renamed.resolve("local-only.txt")));
        assertEquals("newer local NPCs", Files.readString(renamed.resolve("world").resolve("npcs.json")));
        assertEquals("new file", Files.readString(renamed.resolve("new.txt")));
        assertEquals("keep local Git config", Files.readString(renamed.resolve(".git").resolve("config")));
        assertEquals("old story", Files.readString(result.backup().resolve("story.txt")));
        assertEquals(1, result.updated());
        assertEquals(1, result.kept());
    }

    @Test public void oldSnapshotCannotRollBackNewerLocalFilesAndSameFolderNameDoesNotMeanSameCampaign() throws Exception {
        Path root = source("campaign"), zip = temp.getRoot().toPath().resolve("campaign.zip");
        Files.setLastModifiedTime(root.resolve("world").resolve("npcs.json"), FileTime.fromMillis(10000));
        CampaignArchive.exportCampaign(root, zip);
        CampaignStorage device = storage("other-device");
        Path target = CampaignArchive.importCampaign(device, zip).campaign();
        Files.writeString(target.resolve("world").resolve("npcs.json"), "latest");
        Files.setLastModifiedTime(target.resolve("world").resolve("npcs.json"), FileTime.fromMillis(20000));
        var again = CampaignArchive.importCampaign(device, zip);
        assertEquals(0, again.updated());
        assertEquals(1, again.kept());
        assertEquals("latest", Files.readString(target.resolve("world").resolve("npcs.json")));
        CampaignMeta.assignNewId(root);
        CampaignArchive.exportCampaign(root, zip);
        var different = CampaignArchive.importCampaign(device, zip);
        assertNotEquals(target, different.campaign());
        assertEquals(2, device.listCustomCampaigns().size());
    }

    @Test public void checksumFailureDoesNotTouchExistingCampaignAndTraversalIsRejected() throws Exception {
        Path root = source("campaign"), zip = temp.getRoot().toPath().resolve("campaign.zip");
        CampaignArchive.exportCampaign(root, zip);
        CampaignStorage device = storage("other-device");
        Path target = CampaignArchive.importCampaign(device, zip).campaign();
        var snapshot = CampaignArchive.inspect(zip);
        Path broken = temp.getRoot().toPath().resolve("broken.zip");
        try (ZipFile original = new ZipFile(zip.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(broken))) {
            var entries = original.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                output.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().endsWith("npcs.json")) output.write("[\"bad\"]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                else try (var input = original.getInputStream(entry)) { input.transferTo(output); }
                output.closeEntry();
            }
        }
        assertThrows(java.io.IOException.class, () -> CampaignArchive.importCampaign(device, broken));
        assertEquals("[\"NPC\"]", Files.readString(target.resolve("world").resolve("npcs.json")));
        Path malicious = temp.getRoot().toPath().resolve("malicious.zip");
        var unsafe = new CampaignArchive.Snapshot(1, snapshot.campaignId(), "campaign",
            List.of(new CampaignArchive.FileEntry("../outside", 1, 0, "0".repeat(64))));
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(malicious))) {
            output.putNextEntry(new ZipEntry(CampaignArchive.METADATA));
            output.write(JsonMappers.create().writeValueAsBytes(unsafe));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("campaign/../outside"));
            output.write(1);
            output.closeEntry();
        }
        assertThrows(java.io.IOException.class, () -> CampaignArchive.importCampaign(device, malicious));
        assertFalse(Files.exists(device.getCustomCampaignRoot().resolve("outside")));
        assertThrows(java.io.IOException.class, () -> CampaignArchive.exportCampaign(root, root.resolve("self.zip")));
    }
}
