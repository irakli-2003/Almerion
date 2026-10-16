package com.dnd.ui.music;

import com.dnd.data.JsonMappers;
import com.dnd.music.MusicLibrary;
import com.dnd.music.YouTubeLink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.EnumProgress;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Background music from YouTube links.
 *
 * <p>JavaFX's WebView can't decode YouTube's streams, so this embeds Chromium (JCEF) and runs
 * YouTube's official IFrame player in it ({@code /music/player.html}). The page is served
 * from a tiny localhost server because YouTube refuses to play embeds that have no origin.
 * The Chromium window must be on screen while the player initialises; after that it is
 * hidden and keeps playing in the background. The engine (~150 MB) downloads on first use
 * into {@code ~/.dnd-campaign-manager/jcef}.</p>
 *
 * <p>One shared instance per app. All listener callbacks arrive on the JavaFX thread.</p>
 */
public final class MusicPlayer {

    public enum Phase { OFF, DOWNLOADING, STARTING, READY, FAILED }

    /** Snapshot of what the player is doing, for the music panel's "now playing" line. */
    public record Status(Phase phase, String message, String playlistName, String title,
                         int index, int count, int state, int time, int duration, boolean paused,
                         String error, boolean combat) {
        public boolean isPlaying() {
            return phase == Phase.READY && (state == 1 || state == 3) && !paused;
        }
    }

    private static final MusicPlayer INSTANCE = new MusicPlayer();
    private static final int CROSSFADE_MS = 3000;

    public static MusicPlayer get() {
        return INSTANCE;
    }

    private final ObjectMapper mapper = JsonMappers.create();
    private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private final List<String> pending = new ArrayList<>();

    private volatile Status status = new Status(Phase.OFF, "Music is off.", null, null, -1, 0, -1, 0, 0, false, null, false);
    private CefApp app;
    private CefClient client;
    private CefBrowser browser;
    private JFrame frame;
    private HttpServer server;
    private boolean ready;

    private String playlistId;
    private String playlistName;
    /** The playlist to go back to when combat ends (null = silence). */
    private String beforeCombatPlaylistId;
    private boolean combat;
    /** The looping ambience sound that is on, or null. */
    private volatile String ambienceSoundId;

    private MusicPlayer() {}

    public Status status() {
        return status;
    }

    public void addListener(Consumer<Status> listener) {
        listeners.add(listener);
        listener.accept(status);
    }

    public void removeListener(Consumer<Status> listener) {
        listeners.remove(listener);
    }

    public String currentPlaylistId() {
        return playlistId;
    }

    public boolean inCombat() {
        return combat;
    }

    // ── Engine lifecycle ────────────────────────────────────────────────────

    /** Starts the engine if it isn't running yet. Safe to call repeatedly. */
    public synchronized void start() {
        if (status.phase() != Phase.OFF && status.phase() != Phase.FAILED) return;
        if (app != null) {
            if (browser != null && !ready) {
                update(Phase.STARTING, "Reconnecting to YouTube...");
                SwingUtilities.invokeLater(() -> {
                    if (frame != null) frame.setVisible(true);
                    browser.reloadIgnoreCache();
                });
                startWatchdog();
            }
            return;
        }
        update(Phase.STARTING, "Starting the music engine...");
        Thread thread = new Thread(this::boot, "music-engine-start");
        thread.setDaemon(true);
        thread.start();
    }

    private void boot() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] page;
                try (InputStream in = MusicPlayer.class.getResourceAsStream("/music/player.html")) {
                    page = in == null ? new byte[0] : in.readAllBytes();
                }
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, page.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(page);
                }
            });
            server.start();

            CefAppBuilder builder = new CefAppBuilder();
            File bundled = bundledEngineDir();
            if (bundled != null) {
                // Shipped with the installer: nothing to download in the middle of a session.
                builder.setInstallDir(bundled);
                builder.setSkipInstallation(true);
            } else {
                builder.setInstallDir(userEngineDir());
            }
            builder.addJcefArgs("--autoplay-policy=no-user-gesture-required");
            builder.getCefSettings().windowless_rendering_enabled = false;
            // A profile folder per process: Chromium refuses to start a second instance on a shared
            // profile ("Opening in existing browser session"), e.g. two app windows at once.
            File cache = new File(System.getProperty("java.io.tmpdir"), "dnd-music-" + ProcessHandle.current().pid());
            deleteStaleCaches(cache.getParentFile());
            builder.getCefSettings().root_cache_path = cache.getAbsolutePath();
            builder.getCefSettings().cache_path = cache.getAbsolutePath();
            builder.setProgressHandler((state, percent) -> {
                if (state == EnumProgress.DOWNLOADING) {
                    update(Phase.DOWNLOADING, "Downloading the music engine (one time only, ~150 MB)"
                        + (percent >= 0 ? ": " + Math.round(percent) + "%" : "..."));
                } else if (state == EnumProgress.EXTRACTING || state == EnumProgress.INSTALL) {
                    update(Phase.DOWNLOADING, "Installing the music engine...");
                } else if (state == EnumProgress.INITIALIZING) {
                    update(Phase.STARTING, "Starting the music engine...");
                }
            });
            app = builder.build();
            client = app.createClient();
            client.addDisplayHandler(new CefDisplayHandlerAdapter() {
                @Override
                public void onTitleChange(CefBrowser b, String title) {
                    onPageStatus(title);
                }
            });
            String url = "http://localhost:" + server.getAddress().getPort() + "/";
            SwingUtilities.invokeLater(() -> {
                browser = client.createBrowser(url, false, false);
                frame = new JFrame("D&D music engine");
                frame.setAutoRequestFocus(false);
                frame.setFocusableWindowState(false);
                frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                frame.getContentPane().add(browser.getUIComponent());
                frame.setSize(360, 240);
                Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                frame.setLocation(screen.x + screen.width - 370, screen.y + screen.height - 250);
                frame.setVisible(true);
            });
            startWatchdog();
        } catch (Throwable e) {
            synchronized (this) {
                app = null;
            }
            update(Phase.FAILED, "The music engine couldn't start: " + e.getMessage());
        }
    }

    /** Downloaded on first use when the app wasn't installed with the engine bundled. */
    static File userEngineDir() {
        return new File(System.getProperty("user.home"), ".dnd-campaign-manager/jcef");
    }

    /**
     * The engine the installer ships in {@code <install>\app\jcef}, or {@code -Ddnd.jcef.dir};
     * null if there is no complete copy there.
     */
    static File bundledEngineDir() {
        String override = System.getProperty("dnd.jcef.dir");
        File dir = null;
        if (override != null && !override.isBlank()) {
            dir = new File(override);
        } else {
            String exe = System.getProperty("jpackage.app-path");
            if (exe != null) dir = new File(new File(exe).getParentFile(), "app" + File.separator + "jcef");
        }
        return dir != null && new File(dir, "install.lock").isFile() ? dir : null;
    }

    /** Build step of the Windows installer: downloads the engine into the given folder. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: MusicPlayer <install folder>");
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(new File(args[0]));
        builder.setProgressHandler((state, percent) -> System.out.println("music engine: " + state
            + (percent >= 0 ? " " + Math.round(percent) + "%" : "")));
        builder.install();
        System.out.println("music engine installed in " + args[0]);
    }

    private void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(45_000);
            } catch (InterruptedException ignored) {
                return;
            }
            if (!ready) update(Phase.FAILED, "Couldn't reach YouTube. Check the internet connection and press Play again.");
        }, "music-engine-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Removes profile folders left behind by earlier runs whose process has ended. */
    private static void deleteStaleCaches(File dir) {
        File[] old = dir.listFiles((d, name) -> name.startsWith("dnd-music-"));
        if (old == null) return;
        for (File f : old) {
            try {
                long pid = Long.parseLong(f.getName().substring("dnd-music-".length()));
                if (pid == ProcessHandle.current().pid() || ProcessHandle.of(pid).isPresent()) continue;
                try (var walk = java.nio.file.Files.walk(f.toPath())) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            } catch (Exception ignored) {
                // Not ours, or still locked - try again next time.
            }
        }
    }

    private void onPageStatus(String title) {
        if (title == null || !title.startsWith("DND|")) return;
        JsonNode s;
        try {
            s = mapper.readTree(title.substring(4));
        } catch (IOException e) {
            return;
        }
        boolean nowReady = "ready".equals(s.path("phase").asText());
        if (nowReady && !ready) {
            List<String> queued;
            synchronized (this) {
                ready = true;
                queued = new ArrayList<>(pending);
                pending.clear();
            }
            SwingUtilities.invokeLater(() -> {
                if (frame != null) frame.setVisible(false);
            });
            for (String js : queued) run(js);
        }
        String error = s.path("error").isNull() ? null : s.path("error").asText(null);
        status = new Status(ready ? Phase.READY : status.phase(), ready ? null : status.message(), playlistName,
            s.path("title").asText(""), s.path("index").asInt(-1), s.path("count").asInt(0),
            s.path("state").asInt(-1), s.path("t").asInt(0), s.path("dur").asInt(0),
            s.path("paused").asBoolean(false), error, combat);
        fire();
    }

    private void update(Phase phase, String message) {
        Status s = status;
        status = new Status(phase, message, playlistName, s.title(), s.index(), s.count(), s.state(),
            s.time(), s.duration(), s.paused(), s.error(), combat);
        fire();
    }

    private void fire() {
        Status s = status;
        Runnable notify = () -> listeners.forEach(l -> l.accept(s));
        try {
            Platform.runLater(notify);
        } catch (IllegalStateException toolkitNotRunning) {
            notify.run();
        }
    }

    /** Runs a script in the player page, queueing it until the player is ready. */
    private void run(String js) {
        synchronized (this) {
            if (!ready) {
                pending.add(js);
                start();
                return;
            }
        }
        CefBrowser b = browser;
        if (b != null) b.executeJavaScript(js, "", 0);
    }

    /** Closes the engine; call on app exit. Returns true if it had been started. */
    public boolean shutdown() {
        boolean started;
        synchronized (this) {
            started = app != null;
        }
        if (!started) return false;
        Thread closer = new Thread(() -> {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    if (browser != null) browser.close(true);
                    if (frame != null) frame.dispose();
                    if (client != null) client.dispose();
                    if (app != null) app.dispose();
                });
            } catch (Exception ignored) {
            }
        }, "music-engine-stop");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(3000);
        } catch (InterruptedException ignored) {
        }
        if (server != null) server.stop(0);
        return true;
    }

    // ── Playback commands ───────────────────────────────────────────────────

    /** Applies the campaign's volume / shuffle / loop settings. */
    public void applySettings(MusicLibrary library) {
        run("dnd.setVolume(" + library.getVolume() + ");dnd.setFxVolume(" + library.getEffectsVolume()
            + ");dnd.setShuffle(" + library.isShuffle() + ");dnd.setLoop(" + library.isLoop() + ");");
    }

    /** Crossfades to a playlist, starting at {@code startIndex} (-1 = first, or random when shuffling). */
    public boolean playPlaylist(MusicLibrary library, MusicLibrary.Playlist playlist, int startIndex) {
        if (playlist == null) return false;
        ArrayNode items = mapper.createArrayNode();
        int jsStart = -1;
        int i = 0;
        for (MusicLibrary.Track track : playlist.getTracks()) {
            YouTubeLink link = track.link();
            if (link == null) {
                i++;
                continue;
            }
            if (i == startIndex) jsStart = items.size();
            var item = items.addObject();
            if (link.videoId() != null && !link.isPlaylist()) item.put("v", link.videoId());
            if (link.isPlaylist()) item.put("l", link.playlistId());
            if (link.startSeconds() > 0) item.put("s", link.startSeconds());
            item.put("t", track.label());
            i++;
        }
        if (items.isEmpty()) return false;
        playlistId = playlist.getId();
        playlistName = playlist.getName();
        applySettings(library);
        run("dnd.play(" + items + "," + jsStart + "," + CROSSFADE_MS + ");");
        update(status.phase(), status.message());
        return true;
    }

    public void pause() {
        run("dnd.pause();");
    }

    public void resume() {
        run("dnd.resume();");
    }

    public void next() {
        run("dnd.next();");
    }

    public void previous() {
        run("dnd.prev();");
    }

    public void stop() {
        playlistId = null;
        playlistName = null;
        run("dnd.stop(" + CROSSFADE_MS + ");");
        update(status.phase(), status.message());
    }

    public void setVolume(int volume) {
        run("dnd.setVolume(" + Math.max(0, Math.min(100, volume)) + ");");
    }

    public void setEffectsVolume(int volume) {
        run("dnd.setFxVolume(" + Math.max(0, Math.min(100, volume)) + ");");
    }

    public void setShuffle(boolean shuffle) {
        run("dnd.setShuffle(" + shuffle + ");");
    }

    public void setLoop(boolean loop) {
        run("dnd.setLoop(" + loop + ");");
    }

    /**
     * Plays a sound over the music. One-shot effects play once and the music dips while they
     * play; ambience sounds (rain, river, wind...) fade in and loop under the music until
     * {@link #stopAmbience()} or another ambience replaces them.
     */
    public boolean playSound(MusicLibrary.Sound sound) {
        if (sound == null) return false;
        YouTubeLink link = sound.link();
        if (link == null || link.videoId() == null) return false;
        int start = sound.getStartSeconds() > 0 ? sound.getStartSeconds() : link.startSeconds();
        if (sound.isAmbience()) {
            ambienceSoundId = sound.getId();
            run("dnd.ambience(" + mapper.valueToTree(link.videoId()) + "," + start + ");");
            fire();
            return true;
        }
        run("dnd.sfx(" + mapper.valueToTree(link.videoId()) + "," + start + "," + sound.getEndSeconds() + ");");
        return true;
    }

    public void stopSound() {
        run("dnd.stopSfx();");
    }

    /** Fades out the looping ambience. */
    public void stopAmbience() {
        if (ambienceSoundId == null) return;
        ambienceSoundId = null;
        run("dnd.stopAmbience();");
        fire();
    }

    /** Id of the ambience sound that is currently looping, or null. */
    public String ambienceSoundId() {
        return ambienceSoundId;
    }

    // ── Combat and map music ────────────────────────────────────────────────

    /**
     * Switches to the campaign's combat playlist, remembering what was playing so
     * {@link #endCombat} can go back to it. Returns false if no combat playlist is set.
     */
    public boolean startCombat(MusicLibrary library) {
        if (combat) return true;
        MusicLibrary.Playlist battle = library.findPlaylist(library.getCombatPlaylistId());
        if (battle == null || battle.getTracks().isEmpty()) return false;
        beforeCombatPlaylistId = playlistId;
        combat = true;
        return playPlaylist(library, battle, -1);
    }

    /** Leaves combat music: back to {@code fallbackPlaylistId} if given, else to whatever played before. */
    public void endCombat(MusicLibrary library, String fallbackPlaylistId) {
        if (!combat) return;
        combat = false;
        String backTo = fallbackPlaylistId != null ? fallbackPlaylistId : beforeCombatPlaylistId;
        beforeCombatPlaylistId = null;
        MusicLibrary.Playlist previous = library.findPlaylist(backTo);
        if (previous != null && !previous.getTracks().isEmpty()) {
            playPlaylist(library, previous, -1);
        } else {
            stop();
        }
    }

    /** Plays a map's own playlist unless it is already playing (or combat music is on). */
    public void playMapMusic(MusicLibrary library, String mapPlaylistId) {
        if (combat || mapPlaylistId == null || mapPlaylistId.equals(playlistId)) return;
        MusicLibrary.Playlist playlist = library.findPlaylist(mapPlaylistId);
        if (playlist != null) playPlaylist(library, playlist, -1);
    }
}
