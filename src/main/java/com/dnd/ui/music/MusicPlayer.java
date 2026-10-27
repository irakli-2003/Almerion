package com.dnd.ui.music;

import com.dnd.data.AppHome;
import com.dnd.music.AudioFiles;
import com.dnd.music.MusicLibrary;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Plays the campaign's music, ambience and sound effects from audio files with JavaFX Media
 * (MP3, WAV, AIFF, M4A/AAC). Tracks that come from a download link are fetched once into the
 * campaign's {@code music/cache} folder and played from disk afterwards.
 *
 * <p>Music plays on two decks so switching playlists crossfades; sound effects duck the music
 * while they play; one ambience sound loops under everything. One shared instance per app.
 * All work happens on the JavaFX thread and listener callbacks arrive there.</p>
 */
public final class MusicPlayer {

    public enum Phase { OFF, DOWNLOADING, STARTING, READY, FAILED }

    /**
     * Snapshot of what the player is doing, for the music panel's "now playing" line.
     * {@code state} is 1 while playing, 2 when paused, 3 while loading and 0 when stopped.
     */
    public record Status(Phase phase, String message, String playlistName, String title,
                         int index, int count, int state, int time, int duration, boolean paused,
                         String error, boolean combat) {
        public boolean isPlaying() {
            return phase == Phase.READY && (state == 1 || state == 3) && !paused;
        }
    }

    public static final String DOWNLOADING_MESSAGE = "Downloading the track...";
    public static final String MISSING_FILE_MESSAGE = "An audio file is missing from the campaign's music folder,"
        + " so the track was skipped.";
    public static final String TRACK_FAILED_MESSAGE = "A track couldn't be played, so it was skipped.";
    public static final String ALL_FAILED_MESSAGE = "None of the tracks in this playlist could be played.";
    public static final String SOUND_FAILED_MESSAGE = "The sound couldn't be played.";

    private static final MusicPlayer INSTANCE = new MusicPlayer();
    private static final int CROSSFADE_MS = 3000;
    private static final int NEXT_FADE_MS = 1500;
    private static final int PREVIOUS_FADE_MS = 800;
    private static final int STOP_FADE_MS = 1500;
    private static final int AMBIENCE_IN_MS = 2000;
    private static final int AMBIENCE_OUT_MS = 1500;
    private static final int DUCK_MS = 300;
    private static final double DUCK_LEVEL = 0.4;
    private static final int TICK_MS = 40;

    public static MusicPlayer get() {
        return INSTANCE;
    }

    private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private final Random random = new Random();

    private volatile Status status = new Status(Phase.READY, null, null, null, -1, 0, 0, 0, 0, false, null, false);

    private volatile String playlistId;
    private String playlistName;
    /** The playlist to go back to when combat ends (null = silence). */
    private String beforeCombatPlaylistId;
    private volatile boolean combat;
    /** The looping ambience sound that is on, or null. */
    private volatile String ambienceSoundId;

    // Everything below is only touched on the JavaFX thread.
    private int volume = 60;
    private int effectsVolume = 80;
    private boolean shuffle;
    private boolean loop = true;
    private Path lastRoot;

    private List<MusicLibrary.Track> queue = List.of();
    private Path queueRoot;
    private int index = -1;
    private String title;
    private int state;
    private boolean paused;
    private String error;
    private Phase phase = Phase.READY;
    private String phaseMessage;
    private int errorStreak;
    /** Bumped whenever a new track is requested, so late downloads/loads of older requests are ignored. */
    private int musicToken;
    private int ambienceToken;

    private Deck music;
    private Deck ambience;
    private final List<Deck> fading = new ArrayList<>();
    private MediaPlayer sfx;
    private double duck = 1;
    private double duckTarget = 1;
    private Timeline ticker;
    private int ticks;

    private MusicPlayer() {}

    /** A media player with a fade gain on top of its layer's volume. */
    private static final class Deck {
        final MediaPlayer player;
        final boolean ambience;
        double gain;
        double target = 1;
        double step = 1;
        boolean disposeAtZero;

        Deck(MediaPlayer player, boolean ambience) {
            this.player = player;
            this.ambience = ambience;
        }

        void fadeTo(double to, int ms) {
            target = to;
            step = ms <= 0 ? 1 : (double) TICK_MS / ms;
            if (ms <= 0) gain = to;
        }
    }

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

    /** Stops every sound and releases the media players; call on app exit. */
    public void shutdown() {
        onFx(() -> {
            musicToken++;
            ambienceToken++;
            if (ticker != null) ticker.stop();
            for (Deck d : new ArrayList<>(fading)) dispose(d.player);
            fading.clear();
            if (music != null) dispose(music.player);
            if (ambience != null) dispose(ambience.player);
            if (sfx != null) dispose(sfx);
            music = null;
            ambience = null;
            sfx = null;
        });
    }

    // ── Playback commands ───────────────────────────────────────────────────

    /** Applies the campaign's volume / shuffle / loop settings. */
    public void applySettings(MusicLibrary library) {
        if (library == null) return;
        int v = library.getVolume();
        int fx = library.getEffectsVolume();
        boolean sh = library.isShuffle();
        boolean lp = library.isLoop();
        Path root = library.campaignRoot();
        onFx(() -> {
            volume = v;
            effectsVolume = fx;
            shuffle = sh;
            loop = lp;
            if (root != null) lastRoot = root;
            applyVolumes();
        });
    }

    /**
     * Crossfades to a playlist, starting at track {@code startIndex} (-1 = first, or random when
     * shuffling). Tracks that can't be played (old YouTube links) are skipped. Returns false if the
     * playlist has no playable track.
     */
    public boolean playPlaylist(MusicLibrary library, MusicLibrary.Playlist playlist, int startIndex) {
        if (library == null || playlist == null) return false;
        List<MusicLibrary.Track> tracks = new ArrayList<>();
        int start = -1;
        List<MusicLibrary.Track> all = playlist.getTracks();
        for (int i = 0; i < all.size(); i++) {
            MusicLibrary.Track t = all.get(i);
            if (t == null || t.unsupported()) continue;
            if (startIndex >= 0 && i >= startIndex && start < 0) start = tracks.size();
            tracks.add(t);
        }
        if (tracks.isEmpty()) return false;
        playlistId = playlist.getId();
        String name = playlist.getName();
        applySettings(library);
        Path root = rootOf(library);
        int first = start;
        onFx(() -> {
            playlistName = name;
            queue = List.copyOf(tracks);
            queueRoot = root;
            errorStreak = 0;
            error = null;
            paused = false;
            int at = first >= 0 ? first : shuffle ? random.nextInt(queue.size()) : 0;
            playIndex(at, music != null ? CROSSFADE_MS : 0);
        });
        return true;
    }

    public void pause() {
        onFx(() -> {
            if (music == null || paused) return;
            music.player.pause();
            paused = true;
            state = 2;
            fire();
        });
    }

    public void resume() {
        onFx(() -> {
            if (!paused) return;
            paused = false;
            if (music != null) {
                music.player.play();
                state = 1;
            } else if (!queue.isEmpty()) {
                playIndex(Math.max(0, index), 0);
            }
            fire();
        });
    }

    public void next() {
        onFx(() -> advance(1, NEXT_FADE_MS, false));
    }

    public void previous() {
        onFx(() -> advance(-1, PREVIOUS_FADE_MS, false));
    }

    /** Fades the music out. */
    public void stop() {
        playlistId = null;
        onFx(() -> {
            playlistName = null;
            stopMusic(STOP_FADE_MS);
            fire();
        });
    }

    public void setVolume(int volume) {
        int v = clamp(volume);
        onFx(() -> {
            this.volume = v;
            applyVolumes();
        });
    }

    public void setEffectsVolume(int volume) {
        int v = clamp(volume);
        onFx(() -> {
            effectsVolume = v;
            applyVolumes();
        });
    }

    public void setShuffle(boolean shuffle) {
        onFx(() -> this.shuffle = shuffle);
    }

    public void setLoop(boolean loop) {
        onFx(() -> this.loop = loop);
    }

    /** Same as {@link #playSound(MusicLibrary, MusicLibrary.Sound)} for the campaign last played. */
    public boolean playSound(MusicLibrary.Sound sound) {
        return playSound(null, sound);
    }

    /**
     * Plays a sound over the music. One-shot effects play once and the music dips while they
     * play; ambience sounds (rain, river, wind...) fade in and loop under the music until
     * {@link #stopAmbience()} or another ambience replaces them. Returns false when the sound has
     * no playable audio.
     */
    public boolean playSound(MusicLibrary library, MusicLibrary.Sound sound) {
        if (sound == null || sound.unsupported()) return false;
        if (library != null) applySettings(library);
        Path explicitRoot = library == null ? null : library.campaignRoot();
        if (sound.isAmbience()) {
            ambienceSoundId = sound.getId();
            onFx(() -> {
                int token = ++ambienceToken;
                Path root = explicitRoot != null ? explicitRoot : rootOf(null);
                withFile(sound, root, file -> {
                    if (token == ambienceToken) startAmbience(sound, file);
                }, message -> {
                    if (token != ambienceToken) return;
                    ambienceSoundId = null;
                    error = message;
                    fire();
                });
                fire();
            });
            return true;
        }
        onFx(() -> {
            Path root = explicitRoot != null ? explicitRoot : rootOf(null);
            withFile(sound, root, file -> startEffect(sound, file), message -> {
                error = message;
                fire();
            });
        });
        return true;
    }

    public void stopSound() {
        onFx(() -> {
            if (sfx != null) dispose(sfx);
            sfx = null;
            duckTarget = 1;
        });
    }

    /** Fades out the looping ambience. */
    public void stopAmbience() {
        if (ambienceSoundId == null) return;
        ambienceSoundId = null;
        onFx(() -> {
            ambienceToken++;
            fadeOut(ambience, AMBIENCE_OUT_MS);
            ambience = null;
            fire();
        });
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
        String before = playlistId;
        combat = true;
        if (!playPlaylist(library, battle, -1)) {
            combat = false;
            return false;
        }
        beforeCombatPlaylistId = before;
        fireLater();
        return true;
    }

    /** Leaves combat music: back to {@code fallbackPlaylistId} if given, else to whatever played before. */
    public void endCombat(MusicLibrary library, String fallbackPlaylistId) {
        if (!combat) return;
        combat = false;
        String backTo = fallbackPlaylistId != null ? fallbackPlaylistId : beforeCombatPlaylistId;
        beforeCombatPlaylistId = null;
        MusicLibrary.Playlist previous = library.findPlaylist(backTo);
        if (previous == null || !playPlaylist(library, previous, -1)) stop();
        fireLater();
    }

    /** Plays a map's own playlist unless it is already playing (or combat music is on). */
    public void playMapMusic(MusicLibrary library, String mapPlaylistId) {
        if (combat || mapPlaylistId == null || mapPlaylistId.equals(playlistId)) return;
        MusicLibrary.Playlist playlist = library.findPlaylist(mapPlaylistId);
        if (playlist != null) playPlaylist(library, playlist, -1);
    }

    // ── Music decks ─────────────────────────────────────────────────────────

    private void playIndex(int at, int fadeMs) {
        if (queue.isEmpty()) return;
        index = Math.floorMod(at, queue.size());
        int token = ++musicToken;
        MusicLibrary.Track track = queue.get(index);
        fadeOut(music, fadeMs);
        music = null;
        title = track.label();
        state = 3;
        fire();
        withFile(track, queueRoot, file -> {
            if (token == musicToken) startTrack(track, file, fadeMs);
        }, message -> {
            if (token == musicToken) trackFailed(message);
        });
    }

    private void startTrack(MusicLibrary.Track track, Path file, int fadeMs) {
        MediaPlayer mp;
        try {
            mp = new MediaPlayer(new Media(file.toUri().toString()));
        } catch (RuntimeException e) {
            trackFailed(TRACK_FAILED_MESSAGE);
            return;
        }
        Deck deck = new Deck(mp, false);
        deck.gain = fadeMs > 0 ? 0 : 1;
        music = deck;
        if (track.getStartSeconds() > 0) mp.setStartTime(Duration.seconds(track.getStartSeconds()));
        applyVolume(deck);
        mp.setOnReady(() -> {
            if (music != deck) return;
            errorStreak = 0;
            if (!paused) {
                mp.play();
                state = 1;
            } else {
                state = 2;
            }
            deck.fadeTo(1, fadeMs);
            ensureTicker();
            fire();
        });
        mp.setOnEndOfMedia(() -> {
            if (music == deck) advance(1, 0, true);
        });
        mp.setOnError(() -> {
            if (music == deck) trackFailed(TRACK_FAILED_MESSAGE);
        });
        ensureTicker();
    }

    private void trackFailed(String message) {
        error = message;
        errorStreak++;
        if (music != null) {
            dispose(music.player);
            music = null;
        }
        if (errorStreak >= queue.size()) {
            error = queue.size() == 1 ? message : ALL_FAILED_MESSAGE;
            stopMusic(0);
            fire();
            return;
        }
        playIndex(index + 1, 0);
    }

    /** Moves to another track; at the end of the list it wraps when looping (or when asked by hand). */
    private void advance(int delta, int fadeMs, boolean atEnd) {
        if (queue.isEmpty()) return;
        int n;
        if (shuffle && delta > 0 && queue.size() > 1) {
            do {
                n = random.nextInt(queue.size());
            } while (n == index);
        } else {
            n = index + delta;
        }
        if (n >= queue.size() && atEnd && !loop) {
            stopMusic(0);
            fire();
            return;
        }
        if (n < 0) n = queue.size() - 1;
        paused = false;
        playIndex(n % queue.size(), fadeMs);
    }

    private void stopMusic(int fadeMs) {
        musicToken++;
        fadeOut(music, fadeMs);
        music = null;
        queue = List.of();
        index = -1;
        title = null;
        state = 0;
        paused = false;
        if (phase == Phase.DOWNLOADING) setPhase(Phase.READY, null);
    }

    // ── Sounds ──────────────────────────────────────────────────────────────

    private void startAmbience(MusicLibrary.Sound sound, Path file) {
        MediaPlayer mp;
        try {
            mp = new MediaPlayer(new Media(file.toUri().toString()));
        } catch (RuntimeException e) {
            ambienceSoundId = null;
            error = SOUND_FAILED_MESSAGE;
            fire();
            return;
        }
        fadeOut(ambience, AMBIENCE_OUT_MS);
        Deck deck = new Deck(mp, true);
        ambience = deck;
        cut(mp, sound);
        mp.setCycleCount(MediaPlayer.INDEFINITE);
        applyVolume(deck);
        mp.setOnReady(() -> {
            if (ambience != deck) return;
            mp.play();
            deck.fadeTo(1, AMBIENCE_IN_MS);
            ensureTicker();
        });
        mp.setOnError(() -> {
            if (ambience != deck) return;
            dispose(mp);
            ambience = null;
            ambienceSoundId = null;
            error = SOUND_FAILED_MESSAGE;
            fire();
        });
        ensureTicker();
    }

    private void startEffect(MusicLibrary.Sound sound, Path file) {
        MediaPlayer mp;
        try {
            mp = new MediaPlayer(new Media(file.toUri().toString()));
        } catch (RuntimeException e) {
            error = SOUND_FAILED_MESSAGE;
            fire();
            return;
        }
        if (sfx != null) dispose(sfx);
        sfx = mp;
        cut(mp, sound);
        mp.setVolume(effectsVolume / 100.0);
        Runnable done = () -> {
            dispose(mp);
            if (sfx == mp) {
                sfx = null;
                duckTarget = 1;
            }
        };
        mp.setOnReady(() -> {
            if (sfx != mp) return;
            mp.play();
            duckTarget = DUCK_LEVEL;
            ensureTicker();
        });
        mp.setOnEndOfMedia(done);
        mp.setOnStopped(done);
        mp.setOnError(() -> {
            done.run();
            error = SOUND_FAILED_MESSAGE;
            fire();
        });
    }

    private static void cut(MediaPlayer mp, MusicLibrary.Sound sound) {
        if (sound.getStartSeconds() > 0) mp.setStartTime(Duration.seconds(sound.getStartSeconds()));
        if (sound.getEndSeconds() > sound.getStartSeconds()) mp.setStopTime(Duration.seconds(sound.getEndSeconds()));
    }

    // ── Files and downloads ─────────────────────────────────────────────────

    /**
     * Finds the local file for a track or sound, downloading it first if it only has a link.
     * Both callbacks run on the JavaFX thread.
     */
    private void withFile(MusicLibrary.Audio audio, Path root, Consumer<Path> then, Consumer<String> fail) {
        Path file = MusicLibrary.localFile(root, audio);
        if (file != null) {
            then.accept(file);
            return;
        }
        if (!audio.downloadable() || root == null) {
            fail.accept(MISSING_FILE_MESSAGE);
            return;
        }
        setPhase(Phase.DOWNLOADING, DOWNLOADING_MESSAGE);
        Thread t = new Thread(() -> {
            try {
                Path downloaded = AudioFiles.download(root, audio.getUrl(), null);
                onFx(() -> {
                    if (phase == Phase.DOWNLOADING) setPhase(Phase.READY, null);
                    then.accept(downloaded);
                });
            } catch (IOException e) {
                String message = e.getMessage();
                onFx(() -> {
                    if (phase == Phase.DOWNLOADING) setPhase(Phase.READY, null);
                    fail.accept(message);
                });
            }
        }, "music-download");
        t.setDaemon(true);
        t.start();
    }

    private Path rootOf(MusicLibrary library) {
        Path root = library == null ? null : library.campaignRoot();
        if (root == null) root = lastRoot;
        if (root == null) root = AppHome.root();
        return root;
    }

    // ── Fades and volume ────────────────────────────────────────────────────

    private void fadeOut(Deck deck, int ms) {
        if (deck == null) return;
        if (ms <= 0 || deck.gain <= 0) {
            dispose(deck.player);
            return;
        }
        deck.fadeTo(0, ms);
        deck.disposeAtZero = true;
        fading.add(deck);
        ensureTicker();
    }

    private void ensureTicker() {
        if (ticker == null) {
            ticker = new Timeline(new KeyFrame(Duration.millis(TICK_MS), e -> tick()));
            ticker.setCycleCount(Animation.INDEFINITE);
        }
        if (ticker.getStatus() != Animation.Status.RUNNING) ticker.play();
    }

    private void tick() {
        if (duck != duckTarget) duck = approach(duck, duckTarget, (double) TICK_MS / DUCK_MS * (1 - DUCK_LEVEL));
        for (Deck d : new ArrayList<>(fading)) {
            step(d);
            if (d.gain <= 0) {
                fading.remove(d);
                dispose(d.player);
            }
        }
        if (music != null) step(music);
        if (ambience != null) step(ambience);
        if (sfx != null) sfx.setVolume(effectsVolume / 100.0);
        if (++ticks % 12 == 0 && music != null && state == 1) fire();
        if (music == null && ambience == null && sfx == null && fading.isEmpty() && duck == duckTarget) ticker.stop();
    }

    private void step(Deck d) {
        d.gain = approach(d.gain, d.target, d.step);
        applyVolume(d);
    }

    private void applyVolumes() {
        for (Deck d : fading) applyVolume(d);
        if (music != null) applyVolume(music);
        if (ambience != null) applyVolume(ambience);
        if (sfx != null) sfx.setVolume(effectsVolume / 100.0);
    }

    private void applyVolume(Deck d) {
        double level = d.ambience ? effectsVolume / 100.0 : volume / 100.0 * duck;
        d.player.setVolume(Math.max(0, Math.min(1, d.gain * level)));
    }

    private static double approach(double value, double target, double step) {
        if (value < target) return Math.min(target, value + step);
        return Math.max(target, value - step);
    }

    private static void dispose(MediaPlayer mp) {
        try {
            mp.stop();
            mp.dispose();
        } catch (RuntimeException ignored) {
            // Already disposed.
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    // ── Status ──────────────────────────────────────────────────────────────

    private void setPhase(Phase phase, String message) {
        this.phase = phase;
        this.phaseMessage = message;
        fire();
    }

    /** Publishes the current state to the listeners; call on the JavaFX thread. */
    private void fire() {
        int time = 0;
        int duration = 0;
        if (music != null) {
            Duration now = music.player.getCurrentTime();
            Duration total = music.player.getTotalDuration();
            if (now != null && !now.isUnknown()) time = (int) now.toSeconds();
            if (total != null && !total.isUnknown() && !total.isIndefinite()) duration = (int) total.toSeconds();
        }
        Status s = new Status(phase, phaseMessage, playlistName, title, index, queue.size(), state, time, duration,
            paused, error, combat);
        status = s;
        listeners.forEach(l -> l.accept(s));
    }

    private void fireLater() {
        onFx(this::fire);
    }

    /** Runs on the JavaFX thread: right away when already on it, else queued (starting JavaFX if needed). */
    private static void onFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
            return;
        }
        try {
            Platform.runLater(r);
        } catch (IllegalStateException notStarted) {
            try {
                Platform.startup(r);
            } catch (IllegalStateException alreadyStarted) {
                Platform.runLater(r);
            }
        }
    }

}
