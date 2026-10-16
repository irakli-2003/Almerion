package com.dnd.music;

import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The campaign's soundtrack, stored in {@code music.json} at the campaign root: named
 * playlists of YouTube links, short sound effects, and which playlist plays in combat.
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

    /** The campaign's library, or the bundled starter pack when the campaign has none yet. */
    public static MusicLibrary load(Path campaignRoot) {
        Path file = campaignRoot.resolve(FILE_NAME);
        if (!Files.exists(file)) return starter();
        try {
            MusicLibrary library = mapper().readValue(file.toFile(), MusicLibrary.class);
            return library == null ? new MusicLibrary() : library;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    /**
     * The bundled starter pack: popular YouTube tavern/market/dungeon/combat music, looping
     * ambience (rain, river, wind...) and one-shot effects (thunder, door creak, dragon roar...).
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
     * Adds the starter pack's playlists and sounds that this library doesn't have yet
     * (matched by name), and its combat playlist if none is chosen. Returns how many were added.
     */
    public int addStarterPack() {
        MusicLibrary starter = starter();
        int added = 0;
        for (Playlist p : starter.playlists) {
            if (findPlaylist(p.getName()) != null) continue;
            if (findPlaylist(p.getId()) != null) p.setId(newId());
            playlists.add(p);
            added++;
        }
        for (Sound s : starter.sounds) {
            if (findSound(s.getName()) != null) continue;
            if (findSound(s.getId()) != null) s.setId(newId());
            sounds.add(s);
            added++;
        }
        if (findPlaylist(combatPlaylistId) == null) {
            Playlist combat = starter.findPlaylist(starter.combatPlaylistId);
            if (combat != null) combatPlaylistId = findPlaylist(combat.getName()).getId();
        }
        return added;
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

    /** A named list of YouTube links (each may itself be a whole YouTube playlist). */
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

    public static class Track {
        private String title;
        private String url;

        public Track() {}

        public Track(String title, String url) {
            this.title = title;
            this.url = url;
        }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public YouTubeLink link() { return YouTubeLink.parse(url); }

        /** The title, or the link itself when no title was given. */
        public String label() { return title == null || title.isBlank() ? url : title; }

        @Override public String toString() { return label(); }
    }

    /** A sound effect (a YouTube clip, optionally cut to a start/end second) or a looping ambience. */
    public static class Sound {
        private String id = newId();
        private String name;
        private String url;
        /** Optional group, e.g. "Alice X Horror"; blank means the general library. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private String folder;
        private int startSeconds;
        /** 0 means "play to the end of the video". */
        private int endSeconds;
        /** Background sound (rain, river, wind...) that loops under the music until stopped. */
        private boolean ambience;

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
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getFolder() { return folder; }
        public void setFolder(String folder) { this.folder = cleanFolder(folder); }
        public int getStartSeconds() { return startSeconds; }
        public void setStartSeconds(int startSeconds) { this.startSeconds = Math.max(0, startSeconds); }
        public int getEndSeconds() { return endSeconds; }
        public void setEndSeconds(int endSeconds) { this.endSeconds = Math.max(0, endSeconds); }

        public boolean isAmbience() { return ambience; }
        public void setAmbience(boolean ambience) { this.ambience = ambience; }

        public YouTubeLink link() { return YouTubeLink.parse(url); }

        @Override public String toString() { return name; }
    }
}
