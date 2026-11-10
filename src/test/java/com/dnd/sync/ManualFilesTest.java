package com.dnd.sync;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.List;

import static org.junit.Assert.*;

public class ManualFilesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    static void large(Path path, long size) throws Exception {
        Files.createDirectories(path.getParent());
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) { file.setLength(size); }
    }

    @Test public void thresholdIsExactAndLargeBytesNeverEnterTheIndex() throws Exception {
        Path root = temp.newFolder("campaign").toPath();
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            large(root.resolve("exact.mp3"), ManualFiles.MAX_GIT_BYTES);
            large(root.resolve("music").resolve("large [1] !.mp3"), ManualFiles.MAX_GIT_BYTES + 1);
            Files.writeString(root.resolve("story.txt"), "story");
            Files.createDirectories(root.resolve(".git").resolve("info"));
            Files.writeString(root.resolve(".git").resolve("info").resolve("exclude"), "# Custom rule\n*.bak\n");
            ManualFiles.prepare(root, git);
            git.add().addFilepattern(".").call();
            assertNotNull(git.getRepository().readDirCache().getEntry("exact.mp3"));
            assertNull(git.getRepository().readDirCache().getEntry("music/large [1] !.mp3"));
            assertNotNull(git.getRepository().readDirCache().getEntry(ManualFiles.MANIFEST));
            assertEquals(1, ManualFiles.inspect(root).available().size());
            assertTrue(Files.readString(root.resolve(".git").resolve("info").resolve("exclude")).contains("*.bak"));
            assertEquals(ManualFiles.MAX_GIT_BYTES + 1, Files.size(root.resolve("music").resolve("large [1] !.mp3")));
        }
    }

    @Test public void trackedLargeFileIsUnstagedWithoutDeletingLocalBytes() throws Exception {
        Path root = temp.newFolder("campaign").toPath();
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            Files.writeString(root.resolve("theme.mp3"), "small");
            git.add().addFilepattern(".").call();
            large(root.resolve("theme.mp3"), ManualFiles.MAX_GIT_BYTES + 1);
            ManualFiles.prepare(root, git);
            git.add().addFilepattern(".").call();
            assertNull(git.getRepository().readDirCache().getEntry("theme.mp3"));
            assertTrue(Files.exists(root.resolve("theme.mp3")));
        }
    }

    @Test public void exportImportPreservesPathsChecksumsAndMissingManifestEntries() throws Exception {
        Path root = temp.newFolder("campaign").toPath(), target = temp.newFolder("target").toPath();
        try (Git git = Git.init().setDirectory(root.toFile()).call();
             Git other = Git.init().setDirectory(target.toFile()).call()) {
            large(root.resolve("music").resolve("theme.mp3"), ManualFiles.MAX_GIT_BYTES + 1);
            large(root.resolve("other").resolve("theme.mp3"), ManualFiles.MAX_GIT_BYTES + 2);
            ManualFiles.prepare(root, git);
            Files.copy(root.resolve(ManualFiles.MANIFEST), target.resolve(ManualFiles.MANIFEST));
            ManualFiles.prepare(target, other);
            assertEquals(2, ManualFiles.inspect(target).missing().size());
            Path bundle = ManualFiles.exportFiles(root, temp.newFolder("desktop").toPath());
            assertTrue(Files.isRegularFile(bundle.resolve("music").resolve("theme.mp3")));
            assertTrue(Files.isRegularFile(bundle.resolve("other").resolve("theme.mp3")));
            assertEquals(2, ManualFiles.importFiles(target, bundle));
            assertEquals(0, ManualFiles.inspect(target).missing().size());
            assertEquals(ManualFiles.read(root), ManualFiles.read(target));
            assertThrows(java.io.IOException.class, () -> ManualFiles.importFiles(target, bundle));
        }
    }

    @Test public void damagedBundleAndExistingDifferentFilesAreNotOverwritten() throws Exception {
        Path root = temp.newFolder("campaign").toPath(), target = temp.newFolder("target").toPath();
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            large(root.resolve("theme.mp3"), ManualFiles.MAX_GIT_BYTES + 1);
            ManualFiles.prepare(root, git);
            Path bundle = ManualFiles.exportFiles(root, temp.newFolder("desktop").toPath());
            Files.copy(root.resolve(ManualFiles.MANIFEST), target.resolve(ManualFiles.MANIFEST));
            Files.writeString(target.resolve("theme.mp3"), "my other version");
            assertThrows(java.io.IOException.class, () -> ManualFiles.importFiles(target, bundle));
            assertEquals("my other version", Files.readString(target.resolve("theme.mp3")));
            Files.delete(target.resolve("theme.mp3"));
            Files.writeString(bundle.resolve("theme.mp3"), "damaged");
            assertThrows(java.io.IOException.class, () -> ManualFiles.importFiles(target, bundle));
            assertFalse(Files.exists(target.resolve("theme.mp3")));
            assertThrows(java.io.IOException.class, () -> ManualFiles.exportFiles(root, root.resolve("exports")));
        }
    }

    @Test public void unsafeManifestPathsAreRejected() throws Exception {
        Path root = temp.newFolder("campaign").toPath();
        for (String path : List.of("../outside.mp3", "/absolute.mp3", ".git/config", "a/../b", "C:/file", "a\\b")) {
            var manifest = new ManualFiles.Manifest(1, List.of(
                new ManualFiles.Entry(path, ManualFiles.MAX_GIT_BYTES + 1, "0".repeat(64))));
            com.dnd.data.JsonMappers.create().writeValue(root.resolve(ManualFiles.MANIFEST).toFile(), manifest);
            assertThrows(path, java.io.IOException.class, () -> ManualFiles.read(root));
        }
    }

    @Test public void historicalBlobsIncludingDeletedFilesAreFoundAndRepairKeepsBackupAndWorkingFiles() throws Exception {
        Path root = temp.newFolder("campaign").toPath();
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "GM");
            config.setString("user", null, "email", "gm@example.test");
            config.save();
            Files.writeString(root.resolve("story.txt"), "published");
            git.add().addFilepattern(".").call();
            var base = git.commit().setMessage("Published").call();
            var remoteRef = git.getRepository().updateRef("refs/remotes/origin/main");
            remoteRef.setNewObjectId(base);
            remoteRef.update();
            large(root.resolve("deleted.mp3"), ManualFiles.MAX_GIT_BYTES + 1);
            git.add().addFilepattern(".").call();
            var oversizedCommit = git.commit().setMessage("Unpublished oversized file").call();
            Files.delete(root.resolve("deleted.mp3"));
            large(root.resolve("keep.mp3"), ManualFiles.MAX_GIT_BYTES + 1);
            Files.writeString(root.resolve("story.txt"), "local chosen version");
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            git.commit().setMessage("Keep latest campaign").call();
            var original = git.getRepository().resolve(Constants.HEAD);
            assertEquals(List.of("deleted.mp3", "keep.mp3"), LargeFileHistory.oversized(git.getRepository()));
            ManualFiles.prepare(root, git);
            git.add().addFilepattern(".").call();
            var tip = git.commit().setMessage("Manual transfer manifest").call();
            try (var inserter = git.getRepository().newObjectInserter()) {
                var side = new org.eclipse.jgit.lib.CommitBuilder();
                side.setTreeId(oversizedCommit.getTree());
                side.setParentId(base);
                side.setAuthor(base.getAuthorIdent());
                side.setCommitter(base.getCommitterIdent());
                side.setMessage("Unpublished side branch with large file");
                var sideId = inserter.insert(side);
                var merge = new org.eclipse.jgit.lib.CommitBuilder();
                merge.setTreeId(tip.getTree());
                merge.setParentIds(tip, sideId);
                merge.setAuthor(tip.getAuthorIdent());
                merge.setCommitter(tip.getCommitterIdent());
                merge.setMessage("Local conflict choices, preserving both parents");
                var mergeId = inserter.insert(merge);
                inserter.flush();
                var update = git.getRepository().updateRef(git.getRepository().getFullBranch());
                update.setExpectedOldObjectId(tip);
                update.setNewObjectId(mergeId);
                assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD, update.update());
            }
            original = git.getRepository().resolve(Constants.HEAD);
            String backup = LargeFileHistory.repair(git.getRepository());
            assertNotNull(backup);
            assertEquals(original, git.getRepository().resolve(backup));
            assertTrue(LargeFileHistory.oversized(git.getRepository()).isEmpty());
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                assertEquals(2, walk.parseCommit(git.getRepository().resolve(Constants.HEAD)).getParentCount());
            }
            assertEquals(base, git.getRepository().resolve("refs/remotes/origin/main"));
            assertEquals("local chosen version", Files.readString(root.resolve("story.txt")));
            assertEquals(ManualFiles.MAX_GIT_BYTES + 1, Files.size(root.resolve("keep.mp3")));
            assertFalse(Files.exists(root.resolve("deleted.mp3")));
            assertNull(git.getRepository().readDirCache().getEntry("keep.mp3"));
            assertNotNull(git.getRepository().readDirCache().getEntry("story.txt"));
            assertTrue(git.status().call().isClean());
        }
    }
}
