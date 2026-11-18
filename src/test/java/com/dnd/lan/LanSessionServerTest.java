package com.dnd.lan;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.JsonMappers;
import com.dnd.model.character.PlayerCharacter;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LanSessionServerTest {

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path root;
    private CampaignRepositories repos;
    private LanSessionServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Before
    public void setUp() throws Exception {
        root = temp.newFolder("camp").toPath();
        repos = new CampaignRepositories(root);
        PlayerCharacter hero = new PlayerCharacter();
        hero.setId("hero");
        hero.setName("Ardan");
        hero.setLevel(3);
        hero.setMaxHitPoints(24);
        hero.setCurrentHitPoints(24);
        hero.setPassword("secret");
        repos.players().save(hero);
        PlayerCharacter open = new PlayerCharacter();
        open.setId("open");
        open.setName("Nopass");
        repos.players().save(open);
        server = new LanSessionServer(root, "Test Camp", s -> "Strength".equals(s) ? "Force" : s);
        server.start(0);
    }

    @After
    public void tearDown() {
        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String id, String password) throws Exception {
        String body = "{\"id\":\"" + id + "\",\"password\":\"" + password + "\"}";
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/login"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void servesPageAndCharacterList() throws Exception {
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("EventSource"));

        JsonNode list = JsonMappers.create().readTree(get("/api/characters").body());
        assertEquals(2, list.size());
        boolean sawHero = false;
        for (JsonNode c : list) {
            assertFalse("no secrets in the list", c.has("passwordHash"));
            if ("hero".equals(c.get("id").asText())) {
                sawHero = true;
                assertTrue(c.get("hasPassword").asBoolean());
            }
        }
        assertTrue(sawHero);
        assertEquals("Test Camp", JsonMappers.create().readTree(get("/api/info").body()).get("campaign").asText());
    }

    @Test
    public void signInRules() throws Exception {
        assertEquals(403, login("hero", "nope").statusCode());
        assertEquals("no-password", JsonMappers.create().readTree(login("open", "").body()).get("result").asText());
        HttpResponse<String> ok = login("hero", "secret");
        assertEquals(200, ok.statusCode());
        String token = JsonMappers.create().readTree(ok.body()).get("token").asText();
        assertNotNull(server.characterForToken(token));
    }

    @Test
    public void locksOutAfterRepeatedFailures() {
        for (int i = 0; i < 4; i++) assertEquals("wrong", server.login("1.2.3.4", "hero", "x").code());
        assertEquals("locked", server.login("1.2.3.4", "hero", "x").code());
        assertEquals("locked", server.login("1.2.3.4", "hero", "secret").code());
        assertEquals("ok", server.login("5.6.7.8", "hero", "secret").code());
    }

    @Test
    public void sheetNeedsValidToken() throws Exception {
        assertEquals(401, get("/api/sheet?token=bogus").statusCode());
        assertEquals(401, get("/api/events?token=bogus").statusCode());
        assertEquals(404, get("/portrait?token=bogus").statusCode());
        String token = server.login("ip", "hero", "secret").token();
        HttpResponse<String> sheet = get("/api/sheet?token=" + token);
        assertEquals(200, sheet.statusCode());
        JsonNode d = JsonMappers.create().readTree(sheet.body());
        assertEquals("Ardan", d.get("name").asText());
        assertEquals(24, d.get("hp").get("max").asInt());
        assertEquals("Force", d.get("abilities").get(0).get("name").asText());
        assertEquals(18, d.get("skills").size());
    }

    @Test
    public void streamsChangesAsTheGmMakesThem() throws Exception {
        String token = server.login("ip", "hero", "secret").token();
        HttpResponse<java.io.InputStream> stream = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + server.port() + "/api/events?token=" + token)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
        try (BufferedReader in = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            assertEquals(24, nextSheet(in).get("hp").get("current").asInt());

            PlayerCharacter hero = repos.players().getById("hero");
            hero.setCurrentHitPoints(9);
            repos.players().save(hero);
            server.notifyChanged();
            assertEquals(9, nextSheet(in).get("hp").get("current").asInt());
        }
    }

    @Test
    public void playerLinksNeverUseLoopbackOrUnroutableAddresses() throws Exception {
        assertTrue(LanSessionServer.isJoinAddress(java.net.InetAddress.getByName("192.168.1.20")));
        for (String invalid : java.util.List.of("127.0.0.1", "169.254.5.1", "0.0.0.0", "224.0.0.1", "::1")) {
            assertFalse(invalid, LanSessionServer.isJoinAddress(java.net.InetAddress.getByName(invalid)));
        }
        for (var link : server.joinAddresses()) {
            var uri = URI.create(link.url());
            assertTrue(LanSessionServer.isJoinAddress(java.net.InetAddress.getByName(uri.getHost())));
            assertEquals(server.port(), uri.getPort());
            assertFalse(link.adapter().isBlank());
        }
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(server.hostPreviewUrl())).build(),
            HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test(timeout = 10000)
    public void signedInPlayersReceiveCountdownUpdatesAndClears() throws Exception {
        String token = server.login("ip", "hero", "secret").token();
        server.publishCountdown(root, "first", 11, true, true, true);
        var stream = http.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + server.port() + "/api/events?token=" + token)).build(),
            HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
        try (BufferedReader in = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            assertEquals(24, nextSheet(in).path("hp").path("current").asInt());
            assertEquals(11, nextTimer(in).path("seconds").asInt());
            server.publishCountdown(root, "first", 10, true, true, false);
            var tick = nextTimer(in);
            assertEquals(10, tick.path("seconds").asInt());
            assertTrue(tick.path("sound").asBoolean());
            server.publishCountdown(root, "replacement", 5, true, false, true);
            server.publishCountdown(root, "first", -1, false, false, false);
            var replacement = nextTimer(in);
            assertEquals("replacement", replacement.path("id").asText());
            assertFalse(replacement.path("sound").asBoolean());
            server.publishCountdown(root, "replacement", -1, false, false, false);
            assertTrue(nextTimer(in).isNull());
        }
    }

    @Test(timeout = 10000)
    public void liveSheetOnlyReceivesItsOwnTurnAndClearsItWhenBattleCloses() throws Exception {
        var source = new Object();
        String token = server.login("ip", "hero", "secret").token();
        var stream = http.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + server.port() + "/api/events?token=" + token)).build(),
            HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader in = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            assertFalse(nextSheet(in).has("battleTurn"));
            com.dnd.data.BattleTurns.publish(root, source, new com.dnd.data.BattleTurns.View(
                "turn-1", 2, java.util.Map.of("hero", 2, "other-character", 0)));
            server.notifyChanged();
            var sheet = nextSheet(in);
            assertEquals(2, sheet.path("battleTurn").path("remaining").asInt());
            assertFalse(sheet.toString().contains("other-character"));
            com.dnd.data.BattleTurns.clear(root, source);
            server.notifyChanged();
            assertFalse(nextSheet(in).has("battleTurn"));
        } finally {
            com.dnd.data.BattleTurns.clear(root, source);
        }
    }

    private static JsonNode nextTimer(BufferedReader in) throws Exception {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals("event: timer")) {
                String data = in.readLine();
                if (data != null && data.startsWith("data: ")) return JsonMappers.create().readTree(data.substring(6));
                throw new AssertionError("Missing timer event data");
            }
        }
        throw new AssertionError("Timer stream ended");
    }

    @Test
    public void stopEndsTheSession() throws Exception {
        String token = server.login("ip", "hero", "secret").token();
        server.stop();
        assertFalse(server.isOpen());
        assertNull(server.characterForToken(token));
    }

    @Test public void browserChatSharesHostHubButCannotReadOtherConversationsOrSpoofSender() throws Exception {
        String token = server.login("ip", "hero", "secret").token();
        var chat = com.dnd.chat.SessionChat.hosted(root);
        assertNotNull(chat);
        var create = new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(), "create", null,
            java.util.List.of("pc:hero"), "GM secret", "");
        String thread = chat.execute(com.dnd.chat.SessionChat.HOST, create).selected();
        chat.execute(com.dnd.chat.SessionChat.HOST, new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(),
            "send", thread, java.util.List.of(), "", "For Ardan only"));
        assertEquals(401, get("/api/chat?token=bogus").statusCode());
        var response = JsonMappers.create().readTree(get("/api/chat?token=" + token + "&conversation=" + thread).body());
        assertEquals("pc:hero", response.path("peer").asText());
        assertEquals("For Ardan only", response.path("messages").get(0).path("text").asText());
        String hidden = chat.execute("pc:open", new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(),
            "create", null, java.util.List.of(com.dnd.chat.SessionChat.HOST), "Not Ardan", "")).selected();
        assertEquals(400, get("/api/chat?token=" + token + "&conversation=" + hidden).statusCode());
        var command = new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(), "send", thread,
            java.util.List.of(), "", "<script>not executable</script>");
        var reply = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/chat?token=" + token))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JsonMappers.create().writeValueAsString(command))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reply.statusCode());
        assertEquals("pc:hero", chat.execute(com.dnd.chat.SessionChat.HOST, com.dnd.chat.SessionChat.Command.poll(thread))
            .messages().getLast().sender());
        var history = chat.execute("pc:hero", com.dnd.chat.SessionChat.Command.poll(thread)).messages();
        assertEquals(400, upgradePost(token, new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(),
            "delete-message", thread, java.util.List.of(), "", Long.toString(history.getFirst().sequence()))).statusCode());
        assertEquals(200, upgradePost(token, new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(),
            "delete-message", thread, java.util.List.of(), "", Long.toString(history.getLast().sequence()))).statusCode());
        assertEquals(1, chat.execute("pc:hero", com.dnd.chat.SessionChat.Command.poll(thread)).messages().size());
        var group = chat.execute("gm", new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(),
            "create", null, java.util.List.of("pc:hero", "pc:open"), "Table", "")).selected();
        assertEquals(200, upgradePost(token, new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(),
            "rename", group, java.util.List.of(), "New table", "")).statusCode());
        assertTrue(chat.execute("gm", com.dnd.chat.SessionChat.Command.poll(group)).conversations().stream()
            .anyMatch(c -> c.id().equals(group) && c.title().equals("New table")));
        server.stop();
        assertNull(com.dnd.chat.SessionChat.hosted(root));
    }

    @Test public void mobileUpgradeRequestsAreAuthenticatedAndDoNotSpendXpUntilGmAcceptance() throws Exception {
        var pc = repos.players().getById("hero");
        pc.setClassId("fighter"); pc.setLevel(5); pc.setXp(100);
        pc.setStats(new com.dnd.model.character.stats.CoreStats(10, 10, 10, 10, 10, 10));
        repos.players().save(pc);
        var cls = new com.dnd.model.character.CharacterClass(); cls.setId("fighter");
        repos.classes().save(cls);
        String token = JsonMappers.create().readTree(login("hero", "secret").body()).path("token").asText();
        var mapper = JsonMappers.create();
        var optionsCommand = new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(),
            "upgrade-options", "open", java.util.List.of(), "", "");
        var optionsReply = upgradePost(token, optionsCommand);
        assertEquals(optionsReply.body(), 200, optionsReply.statusCode());
        var options = mapper.readTree(optionsReply.body()).path("upgrades").path("options");
        var node = java.util.stream.StreamSupport.stream(options.path("choices").spliterator(), false)
            .filter(n -> n.path("blocked").asText().isBlank()).findFirst().orElseThrow();
        var proposal = new com.dnd.model.character.UpgradeRequests.Proposal(options.path("revision").asText(),
            java.util.Map.of("strength", 2), node.path("id").asText(), java.util.Map.of());
        var submit = new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(),
            "upgrade-submit", "open", java.util.List.of(), "", mapper.writeValueAsString(proposal));
        assertEquals(401, upgradePost("wrong-token", submit).statusCode());
        assertEquals(200, upgradePost(token, submit).statusCode());
        assertEquals(200, upgradePost(token, submit).statusCode());
        assertEquals(100, repos.players().getById("hero").getXp());
        var accept = new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(),
            "upgrade-accept", submit.id(), java.util.List.of(), "", mapper.writeValueAsString(proposal));
        assertEquals(400, upgradePost(token, accept).statusCode());
        com.dnd.chat.SessionChat.hosted(root).execute("gm", accept);
        assertEquals(6, repos.players().getById("hero").getLevel());
        assertEquals(0, repos.players().getById("hero").getXp());
        assertEquals(1, repos.players().getById("open").getLevel());
    }

    private HttpResponse<String> upgradePost(String token, com.dnd.chat.SessionChat.Command command) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/chat?token=" + token))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonMappers.create().writeValueAsString(command))).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode nextSheet(BufferedReader in) throws Exception {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("data: ")) return JsonMappers.create().readTree(line.substring(6));
        }
        throw new AssertionError("stream ended");
    }
}
