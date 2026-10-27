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
    public void conflictKeepsBothVersions() throws Exception {
        Path first = campaign("first");
        repo(first).connect(remote);
        Path second = cloneOf("second");

        write(first, "world/npcs.json", "[\"from first\"]");
        write(first, "world/items.json", "[\"only first touched this\"]");
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());

        write(second, "world/npcs.json", "[\"from second\"]");
        CampaignRepo.Result result = repo(second).sync();
        assertEquals(result.message(), CampaignRepo.Outcome.CONFLICT, result.outcome());
        String copy = "world/npcs.json.remote-20261027-183000";
        assertEquals(java.util.List.of(copy), result.conflictCopies());
        assertEquals("[\"from second\"]", read(second, "world/npcs.json"));
        assertEquals("[\"from first\"]", read(second, copy));
        assertEquals("[\"only first touched this\"]", read(second, "world/items.json"));

        // The resolved merge was pushed, so the first computer gets both versions too.
        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());
        assertEquals("[\"from second\"]", read(first, "world/npcs.json"));
        assertEquals("[\"from first\"]", read(first, copy));
    }

    @Test
    public void connectingAnExistingCopyMergesWithWhatIsAlreadyOnGitHub() throws Exception {
        Path first = campaign("first");
        write(first, "world/npcs.json", "[\"first\"]");
        repo(first).connect(remote);

        Path second = campaign("second");
        write(second, "world/places.json", "[\"second only\"]");
        write(second, "world/npcs.json", "[\"second\"]");
        CampaignRepo.Result result = repo(second).connect(remote);
        assertEquals(result.message(), CampaignRepo.Outcome.CONFLICT, result.outcome());
        assertEquals("[\"second\"]", read(second, "world/npcs.json"));
        assertEquals("[\"first\"]", read(second, "world/npcs.json.remote-20261027-183000"));
        assertEquals("[\"second only\"]", read(second, "world/places.json"));

        assertEquals(CampaignRepo.Outcome.SYNCED, repo(first).sync().outcome());
        assertEquals("[\"second only\"]", read(first, "world/places.json"));
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
}
