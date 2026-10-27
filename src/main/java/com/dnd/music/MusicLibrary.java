package com.dnd.music;

import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The campaign's soundtrack, stored in {@code music.json} at the campaign root: named
 * playlists of audio tracks, sound effects and looping ambience, and which playlist plays in
 * combat. Audio is either a file copied into {@code <campaign>/music/} or a direct link to an
 * audio file that is downloaded once into {@code <campaign>/music/cache/}.
 *
 * <p>Libraries saved by older versions hold YouTube links; those entries still load but are
 * {@linkplain Audio#unsupported() unsupported}: they are skipped in playback and can be
 * removed with {@link #removeUnsupported()}.</p>
 */
public class MusicLibrary {

    public static final String FILE_NAME = "music.json";
    private static final String STARTER_RESOURCE = "/music/starter-library.json";

    private List<Playlist> playlists = new ArrayList<>();
    private List<Sound> sounds = new ArrayList<>();
    private String combatPlaylistId;
    private int volume = 60;
    private int effectsVolume = 80;
    private boolean shuffle;
    private boolean loop = true;
    /** The campaign folder audio paths are relative to; not saved. */
    private Path root;

    /** The campaign's library, or the bundled starter pack when the campaign has none yet. */
    public static MusicLibrary load(Path campaignRoot) {
        Path file = campaignRoot.resolve(FILE_NAME);
        MusicLibrary library;
        if (!Files.exists(file)) {
            library = starter();
        } else {
            try {
                library = mapper().readValue(file.toFile(), MusicLibrary.class);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + file, e);
            }
            if (library == null) library = new MusicLibrary();
        }
        library.root = campaignRoot;
        return library;
    }

    /**
     * The bundled starter pack: openly licensed (CC0 / CC BY) tavern, market, dungeon and
     * combat music, looping ambience (rain, river, wind...) and one-shot effects (thunder,
     * door creak...). Entries hold download links and attribution; the audio itself is fetched
     * into the campaign folder when the GM adds the pack or first plays a track.
     */
    public static MusicLibrary starter() {
        try (InputStream in = MusicLibrary.class.getResourceAsStream(STARTER_RESOURCE)) {
            if (in == null) return new MusicLibrary();
            return mapper().readValue(in, MusicLibrary.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the starter music library", e);
        }
    }

    /**
     * Adds the starter pack's playlists, tracks and sounds that this library doesn't have yet
     * (playlists and sounds matched by name, tracks by link), and its combat playlist if none is
     * chosen. A same-named sound whose audio is unsupported (an old YouTube link) is switched to
     * the starter pack's audio. Returns how many entries were added or replaced.
     */
    public int addStarterPack() {
        MusicLibrary starter = starter();
        int added = 0;
        for (Playlist p : starter.playlists) {
            Playlist mine = findPlaylist(p.getName());
            if (mine == null) {
                if (findPlaylist(p.getId()) != null) p.setId(newId());
                playlists.add(p);
                added++;
                continue;
            }
            for (Track t : p.getTracks()) {
                boolean present = mine.getTracks().stream().anyMatch(m -> sameAudio(m, t));
                if (!present) {
                    mine.getTracks().add(t);
                    added++;
                }
            }
        }
        for (Sound s : starter.sounds) {
            Sound mine = findSound(s.getName());
            if (mine == null) {
                if (findSound(s.getId()) != null) s.setId(newId());
                sounds.add(s);
                added++;
            } else if (mine.unsupported()) {
                mine.setUrl(s.getUrl());
                mine.setFile(s.getFile());
                mine.setAttribution(s.getAttribution());
                mine.setStartSeconds(s.getStartSeconds());
                mine.setEndSeconds(s.getEndSeconds());
                added++;
            }
        }
        if (findPlaylist(combatPlaylistId) == null) {
            Playlist combat = starter.findPlaylist(starter.combatPlaylistId);
            if (combat != null) combatPlaylistId = findPlaylist(combat.getName()).getId();
        }
        return added;
    }

    private static boolean sameAudio(Audio a, Audio b) {
        if (a.getUrl() != null && a.getUrl().equalsIgnoreCase(b.getUrl())) return true;
        return a.getFile() != null && a.getFile().equalsIgnoreCase(b.getFile());
    }

    /** Removes every track and sound whose audio can't be played (old YouTube links). Returns how many. */
    public int removeUnsupported() {
        int removed = 0;
        for (Playlist p : playlists) {
            int before = p.getTracks().size();
            p.getTracks().removeIf(Track::unsupported);
            removed += before - p.getTracks().size();
        }
        int before = sounds.size();
        sounds.removeIf(Sound::unsupported);
        return removed + before - sounds.size();
    }

    /** How many tracks and sounds can't be played (old YouTube links). */
    public int unsupportedCount() {
        int count = 0;
        for (Playlist p : playlists) for (Track t : p.getTracks()) if (t.unsupported()) count++;
        for (Sound s : sounds) if (s.unsupported()) count++;
        return count;
    }

    /** Every track and sound, in library order. */
    public List<Audio> allAudio() {
        List<Audio> all = new ArrayList<>();
        for (Playlist p : playlists) all.addAll(p.getTracks());
        all.addAll(sounds);
        return all;
    }

    /** The distinct credits of every track and sound that has them, sorted by title. */
    public List<Attribution> credits() {
        Set<Attribution> seen = new LinkedHashSet<>();
        for (Audio a : allAudio()) if (a.getAttribution() != null) seen.add(a.getAttribution());
        List<Attribution> list = new ArrayList<>(seen);
        list.sort(Comparator.comparing(a -> a.getTitle() == null ? "" : a.getTitle().toLowerCase()));
        return list;
    }

    /** The campaign folder this library was loaded from, or null for the bundled starter pack. */
    public Path campaignRoot() {
        return root;
    }

    public void useCampaignRoot(Path campaignRoot) {
        this.root = campaignRoot;
    }

    /** The audio file on disk for a track or sound, or null if it isn't there (yet). */
    public Path localFile(Audio audio) {
        return localFile(root, audio);
    }

    public static Path localFile(Path campaignRoot, Audio audio) {
        if (audio == null || campaignRoot == null) return null;
        if (audio.getFile() != null && !audio.getFile().isBlank()) {
            Path f = AudioSource.resolve(campaignRoot, audio.getFile());
            return f != null && Files.isRegularFile(f) ? f : null;
        }
        return audio.downloadable() ? AudioFiles.cached(campaignRoot, audio.getUrl()) : null;
    }

    /** Tracks and sounds with a download link that haven't been downloaded yet (one entry per link). */
    public List<Audio> pendingDownloads() {
        List<Audio> pending = new ArrayList<>();
        Set<String> urls = new java.util.HashSet<>();
        for (Audio a : allAudio()) {
            if (a.downloadable() && localFile(a) == null && urls.add(a.getUrl().trim())) pending.add(a);
        }
        return pending;
    }

    public void save(Path campaignRoot) {
        try {
            mapper().writerWithDefaultPrettyPrinter().writeValue(campaignRoot.resolve(FILE_NAME).toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write music library", e);
        }
    }

    private static ObjectMapper mapper() {
        return JsonMappers.create();
    }

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Case-insensitive lookup by id first, then by name - script cues use names. */
    public Playlist findPlaylist(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        String key = idOrName.trim();
        for (Playlist p : playlists) if (key.equalsIgnoreCase(p.getId())) return p;
        for (Playlist p : playlists) if (key.equalsIgnoreCase(p.getName())) return p;
        return null;
    }

    public Sound findSound(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        String key = idOrName.trim();
        for (Sound s : sounds) if (key.equalsIgnoreCase(s.getId())) return s;
        for (Sound s : sounds) if (key.equalsIgnoreCase(s.getName())) return s;
        return null;
    }

    public List<Playlist> sortedPlaylists() {
        List<Playlist> copy = new ArrayList<>(playlists);
        copy.sort(Comparator.comparing(p -> p.getName() == null ? "" : p.getName().toLowerCase()));
        return copy;
    }

    public List<Sound> sortedSounds() {
        List<Sound> copy = new ArrayList<>(sounds);
        copy.sort(Comparator.comparing(s -> s.getName() == null ? "" : s.getName().toLowerCase()));
        return copy;
    }

    public List<Playlist> getPlaylists() { return playlists; }
    public void setPlaylists(List<Playlist> playlists) { this.playlists = playlists == null ? new ArrayList<>() : playlists; }
    public List<Sound> getSounds() { return sounds; }
    public void setSounds(List<Sound> sounds) { this.sounds = sounds == null ? new ArrayList<>() : sounds; }
    public String getCombatPlaylistId() { return combatPlaylistId; }
    public void setCombatPlaylistId(String combatPlaylistId) { this.combatPlaylistId = combatPlaylistId; }
    public int getVolume() { return volume; }
    public void setVolume(int volume) { this.volume = clamp(volume); }
    public int getEffectsVolume() { return effectsVolume; }
    public void setEffectsVolume(int effectsVolume) { this.effectsVolume = clamp(effectsVolume); }
    public boolean isShuffle() { return shuffle; }
    public void setShuffle(boolean shuffle) { this.shuffle = shuffle; }
    public boolean isLoop() { return loop; }
    public void setLoop(boolean loop) { this.loop = loop; }

    /** Every folder used by a playlist or sound, sorted by name. */
    public List<String> folders() {
        java.util.TreeSet<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Playlist p : playlists) if (p.getFolder() != null) names.add(p.getFolder());
        for (Sound s : sounds) if (s.getFolder() != null) names.add(s.getFolder());
        return new ArrayList<>(names);
    }

    static String cleanFolder(String folder) {
        return folder == null || folder.isBlank() ? null : folder.trim();
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    /** A track or sound: an audio file in the campaign folder and/or a direct download link. */
    public interface Audio {
        /** Campaign-relative path of an imported file, e.g. {@code music/rain.mp3}. */
        String getFile();
        /** Direct link to an audio file (or, in old libraries, a YouTube link). */
        String getUrl();
        Attribution getAttribution();
        String label();

        /** True when the audio comes from a link that is downloaded into the campaign's cache. */
        default boolean downloadable() {
            return (getFile() == null || getFile().isBlank()) && getUrl() != null
                && AudioSource.validateLink(getUrl()) == null;
        }

        /** True when there is nothing playable: an old YouTube link, a web page link or no audio at all. */
        default boolean unsupported() {
            return (getFile() == null || getFile().isBlank()) && !downloadable();
        }

        /** Why {@link #unsupported()} is true, as a complete sentence; null when it is playable. */
        default String unsupportedReason() {
            if (!unsupported()) return null;
            if (AudioSource.isYouTube(getUrl())) return AudioSource.YOUTUBE_MESSAGE;
            if (getUrl() == null || getUrl().isBlank()) return "No audio file has been chosen.";
            return AudioSource.validateLink(getUrl());
        }
    }

    /** A named list of tracks that play one after another. */
    public static class Playlist {
        private String id = newId();
        private String name;
        /** Optional group, e.g. "Alice X Horror"; blank means the general library. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private String folder;
        private List<Track> tracks = new ArrayList<>();

        public Playlist() {}

        public Playlist(String name) {
            this.name = name;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getFolder() { return folder; }
        public void setFolder(String folder) { this.folder = cleanFolder(folder); }
        public List<Track> getTracks() { return tracks; }
        public void setTracks(List<Track> tracks) { this.tracks = tracks == null ? new ArrayList<>() : tracks; }

        @Override public String toString() { return name; }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Track implements Audio {
        private String title;
        private String file;
        private String url;
        /** Where playback starts, in seconds (0 = the beginning). */
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private int startSeconds;
        private Attribution attribution;

        public Track() {}

        public Track(String title, String url) {
            this.title = title;
            this.url = url;
        }

        public static Track ofFile(String title, String file) {
            Track t = new Track(title, null);
            t.file = file;
            return t;
        }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        @Override public String getFile() { return file; }
        public void setFile(String file) { this.file = file == null || file.isBlank() ? null : file; }
        @Override public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url == null || url.isBlank() ? null : url; }
        public int getStartSeconds() { return startSeconds; }
        public void setStartSeconds(int startSeconds) { this.startSeconds = Math.max(0, startSeconds); }
        @Override public Attribution getAttribution() { return attribution; }
        public void setAttribution(Attribution attribution) { this.attribution = attribution; }

        /** The title, or the file name / link when no title was given. */
        @Override public String label() {
            if (title != null && !title.isBlank()) return title;
            if (file != null && !file.isBlank()) return file.substring(file.lastIndexOf('/') + 1);
            return url == null ? "" : url;
        }

        @Override public String toString() { return label(); }
    }

    /** A sound effect (optionally cut to a start/end second) or a looping ambience. */
    public static class Sound implements Audio {
        private String id = newId();
        private String name;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String file;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String url;
        /** Optional group, e.g. "Alice X Horror"; blank means the general library. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private String folder;
        private int startSeconds;
        /** 0 means "play to the end of the file". */
        private int endSeconds;
        /** Background sound (rain, river, wind...) that loops under the music until stopped. */
        private boolean ambience;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private Attribution attribution;

        public Sound() {}

        public Sound(String name, String url, int startSeconds, int endSeconds) {
            this.name = name;
            this.url = url;
            this.startSeconds = startSeconds;
            this.endSeconds = endSeconds;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @Override public String getFile() { return file; }
        public void setFile(String file) { this.file = file == null || file.isBlank() ? null : file; }
        @Override public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url == null || url.isBlank() ? null : url; }
        @Override public Attribution getAttribution() { return attribution; }
        public void setAttribution(Attribution attribution) { this.attribution = attribution; }
        public String getFolder() { return folder; }
        public void setFolder(String folder) { this.folder = cleanFolder(folder); }
        public int getStartSeconds() { return startSeconds; }
        public void setStartSeconds(int startSeconds) { this.startSeconds = Math.max(0, startSeconds); }
        public int getEndSeconds() { return endSeconds; }
        public void setEndSeconds(int endSeconds) { this.endSeconds = Math.max(0, endSeconds); }

        public boolean isAmbience() { return ambience; }
        public void setAmbience(boolean ambience) { this.ambience = ambience; }

        @Override public String label() { return name; }

        @Override public String toString() { return name; }
    }
}
