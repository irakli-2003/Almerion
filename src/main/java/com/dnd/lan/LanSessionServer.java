package com.dnd.lan;

import com.dnd.data.CampaignPaths;
import com.dnd.data.CampaignRepositories;
import com.dnd.data.JsonMappers;
import com.dnd.model.character.PlayerCharacter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Hosts a campaign on the local network so players can follow their own character sheet in
 * a browser (desktop, tablet or phone) while the GM runs the game.
 *
 * <p>The sheet is view only: everything still happens in the GM's app, and the page updates
 * by itself (Server-Sent Events) whenever the campaign files change, e.g. when a character
 * takes damage on the battle map, spends a slot or gains XP. A player signs in by picking
 * their character and typing the password the GM set for it; a character without a
 * password cannot be opened, so nobody can peek at someone else's sheet.</p>
 *
 * <p>Built on the JDK's own HTTP server, so it adds no library to the app.</p>
 */
public final class LanSessionServer {

    public static final int DEFAULT_PORT = 8765;
    private static final int PORT_ATTEMPTS = 20;
    private static final int MAX_FAILED_LOGINS = 5;
    private static final long LOCKOUT_MILLIS = 60_000;
    private static final long HEARTBEAT_MILLIS = 15_000;
    private static final int MAX_BODY = 4096;

    /**
     * Every label the browser page shows. They live here, not in the page, so the app's
     * translation tools find them and the page is served in the GM's language.
     */
    static final String[] LABELS = {
        "Choose your character", "Password", "Open sheet", "Back",
        "No password yet. Ask your GM to set one for this character.",
        "Wrong password.", "Too many attempts. Wait a minute and try again.",
        "Log out", "Live", "Reconnecting…", "The session has ended.",
        "View only: your GM keeps this sheet up to date.",
        "Hit points", "Temporary", "Armor class", "Initiative", "Speed", "Proficiency",
        "Passive perception", "Hit dice", "Inspiration", "Death saves", "Successes", "Failures",
        "Abilities", "Saving throws", "Skills", "Spellcasting", "Spell save DC", "Spell attack",
        "Mana", "Spell slots", "Active effects", "Rounds left", "Attacks", "To hit", "Damage",
        "Actions", "Traits", "Class features", "Spells", "Cantrips", "Level", "Inventory",
        "Equipped", "Coins", "Proficiencies & languages", "Features & traits", "Notes", "XP",
        "Sheet", "Combat", "Magic", "Gear", "Nothing here yet.", "ft."
    };

    private static LanSessionServer running;

    private final Path campaignRoot;
    private final String campaignName;
    private final Function<String, String> translate;
    private final ObjectMapper mapper = JsonMappers.create();
    /** One line per message: Server-Sent Events end a message at the first blank line. */
    private final ObjectWriter compact = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final Map<String, int[]> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private final Object changeLock = new Object();
    private volatile long version;
    private volatile boolean open;
    private long stamp;
    private HttpServer server;
    private ExecutorService workers;
    private ScheduledExecutorService watcher;

    public LanSessionServer(Path campaignRoot, String campaignName, Function<String, String> translate) {
        this.campaignRoot = campaignRoot;
        this.campaignName = campaignName == null ? "" : campaignName;
        this.translate = translate == null ? s -> s : translate;
    }

    // ── The app-wide session ────────────────────────────────────────────────

    /** The session currently hosted by this app, or null. */
    public static synchronized LanSessionServer current() {
        return running;
    }

    /** Hosts {@code campaignRoot}, replacing any session already running. */
    public static synchronized LanSessionServer startShared(Path campaignRoot, String campaignName,
                                                            Function<String, String> translate) throws IOException {
        stopShared();
        LanSessionServer s = new LanSessionServer(campaignRoot, campaignName, translate);
        s.start(DEFAULT_PORT);
        running = s;
        return s;
    }

    public static synchronized void stopShared() {
        if (running != null) running.stop();
        running = null;
    }

    /** Ends the hosted session unless it is for {@code campaignRoot} (called when the GM switches campaign). */
    public static synchronized void stopUnlessFor(Path campaignRoot) {
        if (running == null) return;
        if (campaignRoot == null || !running.campaignRoot.toAbsolutePath().normalize()
                .equals(campaignRoot.toAbsolutePath().normalize())) {
            stopShared();
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Listens on every network interface, on {@code preferredPort} or the next free one (0 = any). */
    public void start(int preferredPort) throws IOException {
        IOException last = null;
        int attempts = preferredPort == 0 ? 1 : PORT_ATTEMPTS;
        for (int i = 0; i < attempts && server == null; i++) {
            try {
                server = HttpServer.create(new InetSocketAddress(preferredPort == 0 ? 0 : preferredPort + i), 32);
            } catch (BindException e) {
                last = e;
            }
        }
        if (server == null) throw last != null ? last : new IOException("No free port");
        workers = Executors.newCachedThreadPool(daemon("almerion-lan"));
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        open = true;
        stamp = fingerprint();
        watcher = Executors.newSingleThreadScheduledExecutor(daemon("almerion-lan-watch"));
        watcher.scheduleWithFixedDelay(this::checkForChanges, 1, 1, TimeUnit.SECONDS);
        server.start();
    }

    public void stop() {
        open = false;
        synchronized (changeLock) {
            changeLock.notifyAll();
        }
        if (watcher != null) watcher.shutdownNow();
        if (server != null) server.stop(0);
        if (workers != null) workers.shutdownNow();
        tokens.clear();
    }

    public boolean isOpen() {
        return open;
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public Path campaignRoot() {
        return campaignRoot;
    }

    /** Addresses players can type into their browser, most likely first. */
    public List<String> addresses() {
        List<String> lan = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLinkLocalAddress()) continue;
                    String url = "http://" + a.getHostAddress() + ":" + port() + "/";
                    (a.isSiteLocalAddress() ? lan : other).add(url);
                }
            }
        } catch (IOException ignored) {
            // Fall through to localhost.
        }
        lan.addAll(other);
        if (lan.isEmpty()) lan.add("http://localhost:" + port() + "/");
        return lan;
    }

    /** Wakes every open sheet so it re-reads the campaign (also called by the file watcher). */
    public void notifyChanged() {
        synchronized (changeLock) {
            version++;
            changeLock.notifyAll();
        }
    }

    private void checkForChanges() {
        long now = fingerprint();
        if (now != stamp) {
            stamp = now;
            notifyChanged();
        }
    }

    /** Changes whenever a character or world file is saved. */
    private long fingerprint() {
        CampaignPaths paths = new CampaignPaths(campaignRoot);
        long h = 17;
        for (Path dir : List.of(paths.playersDir(), paths.worldDir())) {
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
                for (Path f : files) {
                    h = h * 31 + f.getFileName().toString().hashCode();
                    h = h * 31 + Files.getLastModifiedTime(f).toMillis();
                    h = h * 31 + Files.size(f);
                }
            } catch (IOException ignored) {
                // A file being written right now is picked up on the next tick.
            }
        }
        return h;
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private void handle(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/", "/index.html" -> page(ex);
                case "/api/info" -> json(ex, 200, info());
                case "/api/characters" -> json(ex, 200, characters());
                case "/api/login" -> login(ex);
                case "/api/sheet" -> sheet(ex);
                case "/api/events" -> events(ex);
                case "/portrait" -> portrait(ex);
                default -> text(ex, 404, "Not found");
            }
        } catch (RuntimeException e) {
            try {
                text(ex, 500, "Something went wrong");
            } catch (IOException | RuntimeException ignored) {
                // The response had already started.
            }
        } finally {
            ex.close();
        }
    }

    private void page(HttpExchange ex) throws IOException {
        try (InputStream in = LanSessionServer.class.getResourceAsStream("/lan/sheet.html")) {
            if (in == null) {
                text(ex, 500, "Page missing");
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
        }
    }

    ObjectNode info() {
        ObjectNode n = mapper.createObjectNode();
        n.put("campaign", campaignName);
        ObjectNode labels = n.putObject("labels");
        for (String l : LABELS) labels.put(l, translate.apply(l));
        return n;
    }

    ArrayNode characters() {
        ArrayNode out = mapper.createArrayNode();
        for (PlayerCharacter pc : players()) {
            ObjectNode n = out.addObject();
            n.put("id", pc.getId());
            n.put("name", pc.getName() == null ? pc.getId() : pc.getName());
            n.put("playerName", pc.getPlayerName() == null ? "" : pc.getPlayerName());
            n.put("level", Math.max(1, pc.getLevel()));
            n.put("hasPassword", pc.hasPassword());
        }
        return out;
    }

    private void login(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            text(ex, 405, "POST only");
            return;
        }
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            text(ex, 413, "Too large");
            return;
        }
        JsonNode body;
        try {
            body = mapper.readTree(raw);
        } catch (IOException e) {
            text(ex, 400, "Bad request");
            return;
        }
        String id = body == null || body.get("id") == null ? null : body.get("id").asText();
        String password = body == null || body.get("password") == null ? "" : body.get("password").asText();
        LoginResult result = login(ip, id, password);
        ObjectNode n = mapper.createObjectNode();
        n.put("result", result.code);
        if (result.token != null) n.put("token", result.token);
        json(ex, result.token != null ? 200 : 403, n);
    }

    record LoginResult(String code, String token) {
    }

    /** Checks a sign-in; {@code code} is "ok", "locked", "no-password" or "wrong". */
    LoginResult login(String ip, String id, String password) {
        long now = System.currentTimeMillis();
        Long until = lockedUntil.get(ip);
        if (until != null && until > now) return new LoginResult("locked", null);
        PlayerCharacter pc = find(id);
        if (pc != null && !pc.hasPassword()) return new LoginResult("no-password", null);
        if (pc == null || password == null || !pc.checkPassword(password)) {
            int[] count = failures.computeIfAbsent(ip, k -> new int[1]);
            synchronized (count) {
                if (++count[0] >= MAX_FAILED_LOGINS) {
                    count[0] = 0;
                    lockedUntil.put(ip, now + LOCKOUT_MILLIS);
                    return new LoginResult("locked", null);
                }
            }
            return new LoginResult("wrong", null);
        }
        failures.remove(ip);
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, pc.getId());
        return new LoginResult("ok", token);
    }

    private void sheet(HttpExchange ex) throws IOException {
        PlayerCharacter pc = authorised(ex);
        if (pc == null) {
            text(ex, 401, "Sign in again");
            return;
        }
        json(ex, 200, sheetOf(pc));
    }

    ObjectNode sheetOf(PlayerCharacter pc) {
        return LanSheet.build(pc, new CampaignRepositories(campaignRoot), campaignName, translate, mapper);
    }

    private void events(HttpExchange ex) throws IOException {
        if (authorised(ex) == null) {
            text(ex, 401, "Sign in again");
            return;
        }
        String token = query(ex).get("token");
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        String last = null;
        long seen = -1;
        long lastWrite = System.currentTimeMillis();
        try {
            while (open && tokens.containsKey(token)) {
                if (seen != version) {
                    seen = version;
                    PlayerCharacter pc = find(tokens.get(token));
                    if (pc == null) {
                        send(out, "event: gone\ndata: {}\n\n");
                        return;
                    }
                    String json = compact.writeValueAsString(sheetOf(pc));
                    if (!json.equals(last)) {
                        last = json;
                        send(out, "data: " + json + "\n\n");
                        lastWrite = System.currentTimeMillis();
                    }
                }
                if (System.currentTimeMillis() - lastWrite >= HEARTBEAT_MILLIS) {
                    send(out, ": ping\n\n");
                    lastWrite = System.currentTimeMillis();
                }
                synchronized (changeLock) {
                    if (seen == version && open) changeLock.wait(1000);
                }
            }
            if (!open) send(out, "event: ended\ndata: {}\n\n");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The browser went away; nothing to clean up.
        }
    }

    private void portrait(HttpExchange ex) throws IOException {
        PlayerCharacter pc = authorised(ex);
        Path file = pc == null ? null : portraitFile(pc.getImagePath());
        if (file == null) {
            text(ex, 404, "No picture");
            return;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String type = name.endsWith(".png") ? "image/png" : name.endsWith(".gif") ? "image/gif"
            : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
        byte[] body = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
    }

    /** The character's picture, only if it is an image file inside the campaign folder. */
    private Path portraitFile(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) return null;
        try {
            Path base = campaignRoot.toAbsolutePath().normalize();
            Path p = base.resolve(imagePath).normalize();
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean image = name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp");
            return image && p.startsWith(base) && Files.isRegularFile(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private PlayerCharacter authorised(HttpExchange ex) {
        String token = query(ex).get("token");
        String id = token == null ? null : tokens.get(token);
        return id == null ? null : find(id);
    }

    PlayerCharacter characterForToken(String token) {
        String id = token == null ? null : tokens.get(token);
        return id == null ? null : find(id);
    }

    private List<PlayerCharacter> players() {
        try {
            return new CampaignRepositories(campaignRoot).players().list();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private PlayerCharacter find(String id) {
        if (id == null) return null;
        for (PlayerCharacter pc : players()) if (id.equals(pc.getId())) return pc;
        return null;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return out;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void send(OutputStream out, String chunk) throws IOException {
        out.write(chunk.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void json(HttpExchange ex, int status, JsonNode node) throws IOException {
        byte[] body = compact.writeValueAsBytes(node);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
    }

    private static void text(HttpExchange ex, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
