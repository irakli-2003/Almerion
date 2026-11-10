package com.dnd.sync;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CampaignRepoTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final CampaignRepo.Identity GM = new CampaignRepo.Identity("gm", "1+gm@users.noreply.github.com");
    private static final UsernamePasswordCredentialsProvider CREDS = new UsernamePasswordCredentialsProvider("x", "y");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T18:30:00Z"), ZoneOffset.UTC);

    private String remote;

    @Before
    public void createBareRemote() throws Exception {
        File bare = temp.newFolder("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close();
        remote = bare.toURI().toString();
    }

    private Path campaign(String name) throws Exception {
        Path folder = temp.newFolder(name).toPath();
        Files.createDirectories(folder.resolve("world"));
        write(folder, "world/npcs.json", "[]");
        write(folder, "campaign-meta.json", "{\"id\":\"abc\"}");
        return folder;
    }

    private static void write(Path folder, String path, String text) throws Exception {
        Path file = folder.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String read(Path folder, String path) throws Exception {
        return Files.readString(folder.resolve(path), StandardCharsets.UTF_8);
    }

    private static CampaignRepo repo(Path folder) {
        return new CampaignRepo(folder, CREDS, GM, CLOCK);
    }

    private Path cloneOf(String name) {
        Path target = temp.getRoot().toPath().resolve(name);
        CampaignRepo.Result result = CampaignRepo.cloneInto(remote, target, CREDS, GM);
        assertEquals(result.message(), CampaignRepo.Outcome.SYNCED, result.outcome());
        return target;
    }

    @Test public void rejectedUploadIncludesServerExplanationAndLocalSafetyNotice() {
        String explanation = "remote: error: File music/theme.wav is 150 MB; exceeds the file size limit.";
        var result = CampaignRepo.pushFailure(
            org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
            "pre-receive hook declined", explanation);
        assertEquals(CampaignRepo.Outcome.FAILED, result.outcome());
        assertTrue(result.message().contains("pre-receive hook declined"));
        assertTrue(result.message().contains(explanation));
        assertTrue(result.message().contains("local commits are kept on this device"));
        assertTrue(result.message().contains("GitHub has not accepted"));
    }

    @Test public void rejectedUploadWithoutServerExplanationDoesNotGuessTheCause() {
        var result = CampaignRepo.pushFailure(
            org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON, null, " ");
        assertEquals(CampaignRepo.Outcome.FAILED, result.outcome());
        assertTrue(result.message().contains("did not provide a detailed explanation"));
        assertFalse(result.message().contains("null"));
    }

    @Test
    public void connectPushesAndCloneReceivesTheCampaign() throws Exception {
        Path first = campaign("first");
        CampaignRepo.Result connected = repo(first).connect(remote);
        assertEquals(connected.message(), CampaignRepo.Outcome.SYNCED, connected.outcome());
        assertTrue(CampaignRepo.isSynced(first));
        assertEquals(remote, CampaignRepo.remoteUrl(first).orElseThrow());
        assertTrue(read(first, ".gitignore").contains("Thumbs.db"));

        Path second = cloneOf("second");
        assertEquals("[]", read(second, "world/npcs.json"));
        assertTrue(CampaignRepo.isSynced(second));
    }

    @Test
    public void changesTravelBetweenComputers() throws Exception {
        Path first = campaign("first");
        repo(first).connect(remote);
        Path second = cloneOf("second");

        write(first, "world/npcs.json", "[{\"name\":\"Bob\"}]");
        write(first, "storyline/chapter1.md", "# Chapter 1");
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).commitAndPush().outcome());

        assertEquals(CampaignRepo.Outcome.SYNCED, repo(second).sync().outcome());
        assertEquals("[{\"name\":\"Bob\"}]", read(second, "world/npcs.json"));
        assertEquals("# Chapter 1", read(second, "storyline/chapter1.md"));

        Files.delete(second.resolve("storyline/chapter1.md"));
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(second).sync().outcome());
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());
        assertFalse(Files.exists(first.resolve("storyline/chapter1.md")));
    }

    @Test public void oversizedFilesStayLocalAndRecipientGetsTransferNotice() throws Exception {
        Path first = campaign("first");
        ManualFilesTest.large(first.resolve("music").resolve("theme.mp3"), ManualFiles.MAX_GIT_BYTES + 1);
        var result = repo(first).connect(remote);
        assertEquals(result.message(), CampaignRepo.Outcome.SYNCED, result.outcome());
        Path second = cloneOf("second");
        assertFalse(Files.exists(second.resolve("music").resolve("theme.mp3")));
        assertEquals(1, ManualFiles.inspect(second).missing().size());
        assertEquals(1, ManualFiles.inspect(first).available().size());
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(second).sync().outcome());
        assertEquals(ManualFiles.read(first), ManualFiles.read(second));
    }

    @Test public void rejectedOldHistoryCanBeRepairedWithoutForcePushOrLosingFiles() throws Exception {
        Path first = campaign("first");
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).connect(remote).outcome());
        org.eclipse.jgit.lib.ObjectId published;
        try (Git git = Git.open(first.toFile())) {
            published = git.getRepository().resolve("HEAD");
            ManualFilesTest.large(first.resolve("theme.mp3"), ManualFiles.MAX_GIT_BYTES + 1);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Old app committed a large file").call();
        }

        var blocked = repo(first).sync();
        assertEquals(CampaignRepo.Outcome.HISTORY_REPAIR_REQUIRED, blocked.outcome());
        assertTrue(blocked.message(), blocked.message().contains("Repair oversized history"));
        try (Git git = Git.open(new File(java.net.URI.create(remote)))) {
            assertEquals(published, git.getRepository().resolve("refs/heads/main"));
        }
        var repaired = repo(first).repairOversizedHistory();
        assertEquals(repaired.message(), CampaignRepo.Outcome.SYNCED, repaired.outcome());
        assertTrue(repaired.message().contains("refs/almerion/large-file-backups/"));
        assertEquals(ManualFiles.MAX_GIT_BYTES + 1, Files.size(first.resolve("theme.mp3")));
        Path second = cloneOf("second");
        assertFalse(Files.exists(second.resolve("theme.mp3")));
        assertEquals(1, ManualFiles.inspect(second).missing().size());
        try (Git git = Git.open(second.toFile())) {
            assertTrue(git.getRepository().getRefDatabase().getRefsByPrefix("refs/almerion/").isEmpty());
        }
    }

    @Test
    public void largeFilesAddedOnDifferentDevicesMergeTheirManifestsWithoutDroppingEither() throws Exception {
        Path first = campaign("first");
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).connect(remote).outcome());
        Path second = cloneOf("second");
        ManualFilesTest.large(first.resolve("first.mp3"), ManualFiles.MAX_GIT_BYTES + 1);
        ManualFilesTest.large(second.resolve("second.mp3"), ManualFiles.MAX_GIT_BYTES + 1);
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());
        var result = repo(second).sync();
        assertEquals(result.message(), CampaignRepo.Outcome.SYNCED, result.outcome());
        assertEquals(2, ManualFiles.read(second).size());
        assertEquals(1, ManualFiles.inspect(second).missing().size());
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());
        assertEquals(ManualFiles.read(second), ManualFiles.read(first));
        assertEquals(1, ManualFiles.inspect(first).missing().size());
    }

    @Test
    public void pushWhileRemoteIsAheadIsReportedNotForced() throws Exception {
        Path first = campaign("first");
        repo(first).connect(remote);
        Path second = cloneOf("second");
        write(second, "world/npcs.json", "[1]");
        repo(second).commitAndPush();

        write(first, "world/places.json", "[]");
        assertEquals(CampaignRepo.Outcome.REMOTE_AHEAD, repo(first).commitAndPush().outcome());
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());
        assertEquals("[1]", read(first, "world/npcs.json"));
        assertEquals("[]", read(first, "world/places.json"));
    }

    @Test
    public void staleLocalLargeFileCannotUndoRemoteManifestUpdate() throws Exception {
        Path first = campaign("first");
        ManualFilesTest.large(first.resolve("theme.mp3"), ManualFiles.MAX_GIT_BYTES + 1);
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).connect(remote).outcome());
        Path second = cloneOf("second");
        Path bundle = ManualFiles.exportFiles(first, temp.newFolder("transfer").toPath());
        assertEquals(1, ManualFiles.importFiles(second, bundle));
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(second).sync().outcome());
        try (var file = new java.io.RandomAccessFile(first.resolve("theme.mp3").toFile(), "rw")) { file.write(42); }
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(second).sync().outcome());
        assertEquals(1, ManualFiles.inspect(second).missing().size());
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(second).sync().outcome());
        assertEquals(ManualFiles.read(first), ManualFiles.read(second));
        assertEquals(1, ManualFiles.inspect(second).missing().size());
    }

    @Test
    public void conflictIsNotDecidedWithoutTheGm() throws Exception {
        Path first = campaign("first");
        repo(first).connect(remote);
        Path second = cloneOf("second");

        write(first, "world/npcs.json", "[\"from first\"]");
        write(first, "world/items.json", "[\"only first touched this\"]");
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());

        write(second, "world/npcs.json", "[\"from second\"]");
        CampaignRepo.Result result = repo(second).sync();
        assertEquals(result.message(), CampaignRepo.Outcome.CONFLICT, result.outcome());
        assertEquals(1, result.conflicts().size());
        CampaignRepo.Conflict conflict = result.conflicts().get(0);
        assertEquals("world/npcs.json", conflict.path());
        assertEquals("gm", conflict.mine().author());
        assertEquals("gm", conflict.github().author());
        assertFalse(conflict.github().deleted());
        // Nothing was overwritten or merged half-way.
        assertEquals("[\"from second\"]", read(second, "world/npcs.json"));
        assertFalse(Files.exists(second.resolve("world/items.json")));
        assertFalse(Files.exists(second.resolve("world/npcs.json.remote-20261027-183000")));
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(second.toFile())) {
            assertEquals(org.eclipse.jgit.lib.RepositoryState.SAFE, git.getRepository().getRepositoryState());
        }
        // And GitHub still has the first computer's version.
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());
        assertEquals("[\"from first\"]", read(first, "world/npcs.json"));
    }

    @Test
    public void choosingGithubsVersionTakesItAndKeepsOtherChanges() throws Exception {
        Path first = campaign("first");
        repo(first).connect(remote);
        Path second = cloneOf("second");

        write(first, "world/npcs.json", "[\"from first\"]");
        write(first, "world/items.json", "[\"only first touched this\"]");
        repo(first).sync();
        write(second, "world/npcs.json", "[\"from second\"]");
        write(second, "world/places.json", "[\"only second touched this\"]");
        assertEquals(CampaignRepo.Outcome.CONFLICT, repo(second).sync().outcome());

        CampaignRepo.Result resolved = repo(second).resolve(Map.of("world/npcs.json", CampaignRepo.Choice.GITHUB));
        assertEquals(resolved.message(), CampaignRepo.Outcome.SYNCED, resolved.outcome());
        assertEquals("[\"from first\"]", read(second, "world/npcs.json"));
        assertEquals("[\"only first touched this\"]", read(second, "world/items.json"));
        assertEquals("[\"only second touched this\"]", read(second, "world/places.json"));

        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());
        assertEquals("[\"from first\"]", read(first, "world/npcs.json"));
        assertEquals("[\"only second touched this\"]", read(first, "world/places.json"));
    }

    @Test
    public void choosingBothSavesGithubsVersionNextToThisOne() throws Exception {
        Path first = campaign("first");
        repo(first).connect(remote);
        Path second = cloneOf("second");
        write(first, "world/npcs.json", "[\"from first\"]");
        repo(first).sync();
        write(second, "world/npcs.json", "[\"from second\"]");

        CampaignRepo.Result result = repo(second).resolve(Map.of("world/npcs.json", CampaignRepo.Choice.BOTH));
        assertEquals(result.message(), CampaignRepo.Outcome.SYNCED, result.outcome());
        String copy = "world/npcs.json.remote-20261027-183000";
        assertEquals(java.util.List.of(copy), result.conflictCopies());
        assertEquals("[\"from second\"]", read(second, "world/npcs.json"));
        assertEquals("[\"from first\"]", read(second, copy));
    }

    @Test
    public void onlyGithubChangedIsTakenWithoutAsking() throws Exception {
        Path first = campaign("first");
        repo(first).connect(remote);
        Path second = cloneOf("second");
        write(second, "world/npcs.json", "[\"newer, from second\"]");
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(second).sync().outcome());
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());
        assertEquals("[\"newer, from second\"]", read(first, "world/npcs.json"));
    }

    @Test
    public void localCommitIsDatedWhenTheFileWasEdited() throws Exception {
        Path first = campaign("first");
        repo(first).connect(remote);
        Path second = cloneOf("second");
        write(first, "world/npcs.json", "[\"edited long ago\"]");
        Files.setLastModifiedTime(first.resolve("world/npcs.json"),
            java.nio.file.attribute.FileTime.from(Instant.parse("2026-10-01T10:00:00Z")));
        write(second, "world/npcs.json", "[\"edited later\"]");
        Files.setLastModifiedTime(second.resolve("world/npcs.json"),
            java.nio.file.attribute.FileTime.from(Instant.parse("2026-10-20T10:00:00Z")));
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(second).sync().outcome());

        CampaignRepo.Result result = repo(first).sync();
        assertEquals(CampaignRepo.Outcome.CONFLICT, result.outcome());
        CampaignRepo.Conflict conflict = result.conflicts().get(0);
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), conflict.mine().when());
        assertEquals(Instant.parse("2026-10-20T10:00:00Z"), conflict.github().when());
        assertEquals(CampaignRepo.Choice.GITHUB, conflict.newer());
    }

    @Test
    public void connectingAnExistingCopyAsksAboutFilesBothHave() throws Exception {
        Path first = campaign("first");
        write(first, "world/npcs.json", "[\"first\"]");
        repo(first).connect(remote);

        Path second = campaign("second");
        write(second, "world/places.json", "[\"second only\"]");
        write(second, "world/npcs.json", "[\"second\"]");
        CampaignRepo.Result result = repo(second).connect(remote);
        assertEquals(result.message(), CampaignRepo.Outcome.CONFLICT, result.outcome());
        assertEquals("[\"second\"]", read(second, "world/npcs.json"));

        java.util.Map<String, CampaignRepo.Choice> choices = new java.util.HashMap<>();
        for (CampaignRepo.Conflict c : result.conflicts()) choices.put(c.path(), CampaignRepo.Choice.MINE);
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(second).resolve(choices).outcome());
        assertEquals("[\"second\"]", read(second, "world/npcs.json"));
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());
        assertEquals("[\"second only\"]", read(first, "world/places.json"));
        assertEquals("[\"second\"]", read(first, "world/npcs.json"));
    }

    @Test
    public void computerNameIsReadFromTheCommitMessage() {
        assertEquals("DESKTOP-1", CampaignRepo.computerOf("Campaign changes from DESKTOP-1"));
        assertEquals(null, CampaignRepo.computerOf("Merge campaign changes from GitHub"));
    }

    @Test
    public void unreachableRemoteIsReportedAsOffline() throws Exception {
        Path first = campaign("first");
        CampaignRepo.Result result = repo(first).connect("https://127.0.0.1:1/nobody/nothing.git");
        assertEquals(result.message(), CampaignRepo.Outcome.OFFLINE, result.outcome());
        assertTrue("local work is committed even when offline", CampaignRepo.isRepository(first));
    }

    @Test
    public void cloneRefusesToOverwriteAnExistingFolder() throws Exception {
        Path existing = campaign("taken");
        CampaignRepo.Result result = CampaignRepo.cloneInto(remote, existing, CREDS, GM);
        assertEquals(CampaignRepo.Outcome.FAILED, result.outcome());
        assertEquals("[]", read(existing, "world/npcs.json"));
    }

    @Test
    public void displayNameShowsOwnerAndRepository() {
        assertEquals("gm/almerion-campaign-x", CampaignRepo.displayName("https://github.com/gm/almerion-campaign-x.git"));
        assertEquals("gm/r", CampaignRepo.displayName("https://github.com/gm/r"));
    }

    @Test
    public void putAsideCopiesCanBeRestoredOrDiscarded() throws Exception {
        Path folder = campaign("aside");
        write(folder, "items/sword.json", "local");
        write(folder, "items/sword.json.remote-20261027-183000", "github");
        write(folder, "spells/fire.json", "mine");
        write(folder, "spells/fire.json.remote-20261028-090000", "theirs");
        write(folder, "notes.remote-old", "not a copy");

        java.util.List<CampaignRepo.SetAside> copies = CampaignRepo.setAsideCopies(folder);
        assertEquals(2, copies.size());
        assertEquals("items/sword.json", copies.get(0).original());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 27, 18, 30), copies.get(0).putAside());

        CampaignRepo.restoreSetAside(folder, copies.get(0));
        assertEquals("github", read(folder, "items/sword.json"));
        CampaignRepo.discardSetAside(folder, copies.get(1));
        assertEquals("mine", read(folder, "spells/fire.json"));
        assertTrue(CampaignRepo.setAsideCopies(folder).isEmpty());
    }

    @Test
    public void gitLockIsExplainedAndNeverDeletedByAgeAlone() throws Exception {
        Path first = campaign("crashed");
        repo(first).connect(remote);
        Path lock = first.resolve(".git/index.lock");
        Files.writeString(lock, "");
        Files.setLastModifiedTime(lock, java.nio.file.attribute.FileTime.from(Instant.parse("2026-10-27T18:00:00Z")));

        write(first, "world/npcs.json", "[{\"name\":\"Bob\"}]");
        CampaignRepo.Result result = repo(first).sync();
        assertEquals(result.message(), CampaignRepo.Outcome.FAILED, result.outcome());
        assertTrue(result.message(), result.message().contains("index.lock"));
        assertTrue(Files.exists(lock));
        assertTrue(Files.readString(first.resolve("world/npcs.json")).contains("Bob"));
    }

    @Test
    public void confirmedLockRecoveryBacksUpLockAndSyncsWithoutLosingChanges() throws Exception {
        Path first = campaign("recover");
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).connect(remote).outcome());
        Path lock = first.resolve(".git/index.lock");
        byte[] interruptedIndex = {0, 1, 2, 3};
        Files.write(lock, interruptedIndex);
        write(first, "world/npcs.json", "[\"not yet synced\"]");
        assertTrue(CampaignRepo.hasIndexLock(first));

        CampaignRepo.Result result = repo(first).recoverIndexLock();

        assertEquals(result.message(), CampaignRepo.Outcome.SYNCED, result.outcome());
        assertFalse(CampaignRepo.hasIndexLock(first));
        try (var backups = Files.list(first.resolve(".git/almerion-lock-backups"))) {
            var files = backups.toList();
            assertEquals(1, files.size());
            org.junit.Assert.assertArrayEquals(interruptedIndex, Files.readAllBytes(files.getFirst()));
        }
        Path second = cloneOf("recovered-on-other-computer");
        assertEquals("[\"not yet synced\"]", read(second, "world/npcs.json"));
    }

    @Test
    public void lockRecoveryDoesNotRemoveUnexpectedDirectories() throws Exception {
        Path first = campaign("unexpected-lock");
        repo(first).connect(remote);
        Path lock = first.resolve(".git/index.lock");
        Files.createDirectory(lock);
        write(first, ".git/index.lock/keep", "preserve");
        CampaignRepo.Result result = repo(first).recoverIndexLock();
        assertEquals(CampaignRepo.Outcome.FAILED, result.outcome());
        assertTrue(result.message(), result.message().contains("not a regular file"));
        assertEquals("preserve", read(first, ".git/index.lock/keep"));
    }

    @Test
    public void failedLockBackupLeavesLockAndLocalChangesUntouched() throws Exception {
        Path first = campaign("failed-backup");
        repo(first).connect(remote);
        write(first, ".git/index.lock", "interrupted index");
        write(first, ".git/almerion-lock-backups", "not a directory");
        write(first, "world/npcs.json", "[\"local\"]");
        CampaignRepo.Result result = repo(first).recoverIndexLock();
        assertEquals(CampaignRepo.Outcome.FAILED, result.outcome());
        assertTrue(result.message(), result.message().contains("Could not back up"));
        assertEquals("interrupted index", read(first, ".git/index.lock"));
        assertEquals("[\"local\"]", read(first, "world/npcs.json"));
    }

    @Test
    public void retryAfterLockDisappearsStillSyncs() throws Exception {
        Path first = campaign("already-unlocked");
        repo(first).connect(remote);
        write(first, "world/npcs.json", "[\"local\"]");
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).recoverIndexLock().outcome());
        assertEquals("[\"local\"]", read(cloneOf("retried"), "world/npcs.json"));
    }

    @Test
    public void failureShowsTheRealCause() {
        Exception e = new RuntimeException("Exception caught during execution of add command",
            new java.io.IOException("Access is denied: images/map.png"));
        assertEquals("Access is denied: images/map.png", CampaignRepo.failure(e).message());
        Exception missing = new RuntimeException("Exception caught during execution of add command",
            new java.io.IOException("Local file not found: images/map.png"));
        assertEquals("Local file not found: images/map.png", CampaignRepo.failure(missing).message());
    }
}
