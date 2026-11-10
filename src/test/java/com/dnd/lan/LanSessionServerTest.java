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
    public void stopEndsTheSession() throws Exception {
        String token = server.login("ip", "hero", "secret").token();
        server.stop();
        assertFalse(server.isOpen());
        assertNull(server.characterForToken(token));
    }

    private static JsonNode nextSheet(BufferedReader in) throws Exception {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("data: ")) return JsonMappers.create().readTree(line.substring(6));
        }
        throw new AssertionError("stream ended");
    }
}
