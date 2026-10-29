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
}
