package com.dnd.music;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.junit.Assert.*;

public class MusicTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void acceptsDirectAudioLinksAndRejectsStreamingPages() {
        assertNull(AudioSource.validateLink("https://example.com/music/tavern.mp3"));
        assertNull(AudioSource.validateLink("http://cdn.example.org/a/b/rain.WAV?x=1"));
        assertNull(AudioSource.validateLink("https://example.com/download?id=42"));
        for (String page : List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ",
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ", "https://open.spotify.com/track/abc",
                "https://soundcloud.com/artist/song", "https://artist.bandcamp.com/track/song",
                "https://vimeo.com/123456", "https://example.com/song.html")) {
            assertEquals(page, AudioSource.PAGE_LINK_MESSAGE, AudioSource.validateLink(page));
        }
        assertEquals(AudioSource.NOT_HTTP_MESSAGE, AudioSource.validateLink("not a link at all"));
        assertEquals(AudioSource.NOT_HTTP_MESSAGE, AudioSource.validateLink("file:///C:/music/a.mp3"));
        assertEquals(AudioSource.NOT_HTTP_MESSAGE, AudioSource.validateLink(""));
        assertTrue(AudioSource.isYouTube("dQw4w9WgXcQ"));
        assertTrue(AudioSource.isYouTube("youtube.com/watch?v=dQw4w9WgXcQ"));
        assertFalse(AudioSource.isYouTube("https://example.com/youtube.mp3"));
    }

    @Test
    public void recognisesAudioFilesAndTimes() {
        assertTrue(AudioSource.isSupportedFile(java.nio.file.Path.of("a", "Song.MP3")));
        assertTrue(AudioSource.isSupportedFile(java.nio.file.Path.of("x.m4a")));
        assertFalse(AudioSource.isSupportedFile(java.nio.file.Path.of("x.ogg")));
        assertFalse(AudioSource.isSupportedFile(java.nio.file.Path.of("mp3")));
        assertEquals("mp3", AudioSource.sniff("ID3\u0004\0\0\0\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertEquals("mp3", AudioSource.sniff(new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64}));
        assertEquals("aac", AudioSource.sniff(new byte[]{(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80}));
        assertEquals("wav", AudioSource.sniff("RIFF\0\0\0\0WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertEquals("aiff", AudioSource.sniff("FORM\0\0\0\0AIFFCOMM".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertEquals("m4a", AudioSource.sniff("\0\0\0\u0020ftypM4A ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertNull(AudioSource.sniff("<!DOCTYPE html><html>".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertNull(AudioSource.sniff("OggS\0\2\0\0\0\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertEquals(90, AudioSource.parseTime("1:30"));
        assertEquals(90, AudioSource.parseTime("90"));
        assertEquals(90, AudioSource.parseTime("1m30s"));
        assertEquals(3725, AudioSource.parseTime("1:02:05"));
        assertEquals(0, AudioSource.parseTime(""));
        assertEquals(-1, AudioSource.parseTime("soon"));
        assertEquals("1:30", AudioSource.formatTime(90));
        assertEquals("", AudioSource.formatTime(0));
    }

    @Test
    public void filePathsStayInsideTheCampaignFolder() {
        java.nio.file.Path root = tmp.getRoot().toPath();
        assertEquals(root.resolve("music").resolve("a.mp3").toAbsolutePath().normalize(),
            AudioSource.resolve(root, "music/a.mp3"));
        assertNull(AudioSource.resolve(root, "../outside.mp3"));
        assertNull(AudioSource.resolve(root, "music/../../outside.mp3"));
        assertNull(AudioSource.resolve(root, root.resolve("x.mp3").toAbsolutePath().toString()));
        assertEquals(AudioSource.cacheKey("https://a/b.mp3"), AudioSource.cacheKey(" https://a/b.mp3 "));
        assertNotEquals(AudioSource.cacheKey("https://a/b.mp3"), AudioSource.cacheKey("https://a/c.mp3"));
    }

    @Test
    public void importedFilesAreCopiedIntoTheCampaign() throws Exception {
        java.nio.file.Path root = tmp.newFolder("campaign").toPath();
        java.nio.file.Path outside = tmp.newFolder("downloads").toPath().resolve("Rain Loop.mp3");
        java.nio.file.Files.write(outside, new byte[]{'I', 'D', '3', 4, 0, 0, 1, 2});
        String rel = AudioFiles.importFile(root, outside);
        assertEquals("music/Rain Loop.mp3", rel);
        assertTrue(java.nio.file.Files.exists(root.resolve("music").resolve("Rain Loop.mp3")));
        assertEquals(rel, AudioFiles.importFile(root, outside));

        java.nio.file.Path other = outside.resolveSibling("other").resolve("Rain Loop.mp3");
        java.nio.file.Files.createDirectories(other.getParent());
        java.nio.file.Files.write(other, new byte[]{'I', 'D', '3', 9});
        assertEquals("music/Rain Loop (2).mp3", AudioFiles.importFile(root, other));

        java.nio.file.Path ogg = outside.resolveSibling("song.ogg");
        java.nio.file.Files.write(ogg, new byte[]{'O', 'g', 'g', 'S'});
        try {
            AudioFiles.importFile(root, ogg);
            fail("OGG can't be played by JavaFX");
        } catch (AudioFiles.AudioException expected) {
            assertEquals(AudioSource.UNSUPPORTED_FILE_MESSAGE, expected.getMessage());
        }

        MusicLibrary library = MusicLibrary.load(root);
        MusicLibrary.Track track = MusicLibrary.Track.ofFile("Rain", rel);
        assertFalse(track.unsupported());
        assertEquals(root.resolve(rel).toAbsolutePath().normalize(), library.localFile(track));
    }

    @Test
    public void downloadsRefusePageLinksWithoutNetwork() {
        try {
            AudioFiles.download(tmp.getRoot().toPath(), "https://www.youtube.com/watch?v=dQw4w9WgXcQ", null);
            fail("YouTube pages must be refused");
        } catch (java.io.IOException e) {
            assertEquals(AudioSource.PAGE_LINK_MESSAGE, e.getMessage());
        }
    }

    @Test
    public void legacyYouTubeTracksLoadFlaggedAndCanBeRemoved() throws Exception {
        java.nio.file.Path root = tmp.getRoot().toPath();
        java.nio.file.Files.writeString(root.resolve(MusicLibrary.FILE_NAME), """
            {"playlists":[{"id":"t","name":"Tavern","futureField":1,"tracks":[
               {"title":"Old inn","url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s"},
               {"title":"Lute","url":"https://example.com/lute.mp3","startSeconds":12},
               {"title":"Mine","file":"music/mine.mp3"}]}],
             "sounds":[{"id":"r","name":"River","url":"https://youtu.be/dQw4w9WgXcQ","ambience":true},
               {"id":"d","name":"Door","file":"music/door.wav"}],
             "combatPlaylistId":"t","volume":40}
            """);
        MusicLibrary library = MusicLibrary.load(root);
        List<MusicLibrary.Track> tracks = library.findPlaylist("Tavern").getTracks();
        assertTrue(tracks.get(0).unsupported());
        assertEquals(AudioSource.YOUTUBE_MESSAGE, tracks.get(0).unsupportedReason());
        assertFalse(tracks.get(1).unsupported());
        assertTrue(tracks.get(1).downloadable());
        assertEquals(12, tracks.get(1).getStartSeconds());
        assertFalse(tracks.get(2).unsupported());
        assertTrue(library.findSound("River").unsupported());
        assertEquals(2, library.unsupportedCount());
        assertEquals(1, library.pendingDownloads().size());

        assertEquals(2, library.removeUnsupported());
        assertEquals(0, library.unsupportedCount());
        assertEquals(List.of("Lute", "Mine"), library.findPlaylist("Tavern").getTracks().stream()
            .map(MusicLibrary.Track::getTitle).toList());
        assertNull(library.findSound("River"));
        library.save(root);
        String saved = java.nio.file.Files.readString(root.resolve(MusicLibrary.FILE_NAME));
        assertFalse(saved.contains("youtube"));
        assertFalse(saved.contains("unsupported"));
        assertFalse(saved.contains("downloadable"));
        assertEquals(40, MusicLibrary.load(root).getVolume());
    }

    @Test
    public void findsCuesInSessionText() {
        String text = "The door opens. [music: Boss Fight]\nThunder! [SFX: thunder] ... [music: stop]";
        List<MusicCue> cues = MusicCue.findAll(text);
        assertEquals(3, cues.size());
        assertEquals("Boss Fight", cues.get(0).name());
        assertEquals(MusicCue.Kind.SFX, cues.get(1).kind());
        assertTrue(cues.get(2).isStop());
        assertEquals("thunder", MusicCue.at(text, text.indexOf("thunder") + 2).name());
        assertNull(MusicCue.at(text, 2));
        assertEquals("[music: Tavern]", MusicCue.marker(MusicCue.Kind.MUSIC, "Tavern"));
        assertEquals("[sfx: Door creak]", MusicCue.marker(MusicCue.Kind.SFX, "Door] creak"));
    }

    @Test
    public void libraryRoundTripsAndFindsByName() {
        MusicLibrary library = new MusicLibrary();
        MusicLibrary.Playlist tavern = new MusicLibrary.Playlist("Tavern");
        tavern.getTracks().add(new MusicLibrary.Track("Inn", "https://example.com/inn.mp3"));
        MusicLibrary.Playlist battle = new MusicLibrary.Playlist("Battle");
        library.getPlaylists().add(tavern);
        library.getPlaylists().add(battle);
        library.getSounds().add(new MusicLibrary.Sound("Thunder", "https://example.com/thunder.mp3", 3, 8));
        library.setCombatPlaylistId(battle.getId());
        library.setVolume(150);
        library.save(tmp.getRoot().toPath());

        MusicLibrary loaded = MusicLibrary.load(tmp.getRoot().toPath());
        assertEquals(100, loaded.getVolume());
        assertEquals("Battle", loaded.findPlaylist(loaded.getCombatPlaylistId()).getName());
        assertEquals("Tavern", loaded.findPlaylist("tavern").getName());
        assertEquals(1, loaded.findPlaylist("Tavern").getTracks().size());
        assertEquals(8, loaded.findSound("THUNDER").getEndSeconds());
        assertEquals(List.of("Battle", "Tavern"), loaded.sortedPlaylists().stream().map(MusicLibrary.Playlist::getName).toList());
    }

    @Test
    public void missingFileGivesStarterLibrary() {
        MusicLibrary library = MusicLibrary.load(tmp.getRoot().toPath());
        assertTrue(library.isLoop());
        assertNotNull(library.findPlaylist("Tavern"));
        assertEquals("Combat", library.findPlaylist(library.getCombatPlaylistId()).getName());
        assertTrue(library.findSound("River").isAmbience());
        assertFalse(library.findSound("Thunder clap").isAmbience());
    }

    private static final java.util.Map<String, String> ALLOWED_LICENSES = java.util.Map.of(
        "CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/",
        "CC BY 3.0", "https://creativecommons.org/licenses/by/3.0/",
        "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/",
        "Public domain", "https://creativecommons.org/publicdomain/mark/1.0/");

    /** Every starter entry must be a direct audio download with author, license and source page. */
    private static void assertLicensed(String what, MusicLibrary.Audio audio) {
        assertTrue(what, audio.downloadable());
        assertTrue(what, audio.getUrl().startsWith("https://"));
        assertTrue(what, audio.getUrl().toLowerCase().endsWith(".mp3"));
        Attribution a = audio.getAttribution();
        assertNotNull(what, a);
        assertFalse(what, a.getTitle() == null || a.getTitle().isBlank());
        assertFalse(what, a.getAuthor() == null || a.getAuthor().isBlank());
        assertTrue(what + ": " + a.getLicense(), ALLOWED_LICENSES.containsKey(a.getLicense()));
        assertEquals(what, ALLOWED_LICENSES.get(a.getLicense()), a.getLicenseUrl());
        assertTrue(what, a.getSourceUrl() != null && a.getSourceUrl().startsWith("https://"));
        String lower = (a.getLicense() + " " + a.getLicenseUrl()).toLowerCase();
        assertFalse(what, lower.contains("nc") || lower.contains("nd"));
    }

    @Test
    public void starterPackIsOpenlyLicensedAudio() {
        MusicLibrary starter = MusicLibrary.starter();
        assertTrue(starter.getPlaylists().size() >= 10);
        assertTrue(starter.getSounds().size() >= 20);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (MusicLibrary.Playlist p : starter.getPlaylists()) {
            assertTrue(p.getName(), ids.add(p.getId()));
            assertFalse(p.getName(), p.getTracks().isEmpty());
            for (MusicLibrary.Track t : p.getTracks()) {
                assertFalse(t.getTitle().isBlank());
                assertLicensed(p.getName() + " / " + t.getTitle(), t);
            }
        }
        for (MusicLibrary.Sound s : starter.getSounds()) {
            assertTrue(s.getName(), ids.add(s.getId()));
            assertLicensed(s.getName(), s);
        }
        assertFalse(starter.credits().isEmpty());
        assertTrue(starter.credits().stream().anyMatch(c -> c.creditLine().contains("Kevin MacLeod")));
    }

    @Test
    public void bundledCampaignMusicIsOpenlyLicensed() {
        MusicLibrary lib = MusicLibrary.load(java.nio.file.Paths.get("src/main/resources/data/custom-campaigns/zurabaach"));
        assertEquals(0, lib.unsupportedCount());
        for (MusicLibrary.Playlist p : lib.getPlaylists()) {
            for (MusicLibrary.Track t : p.getTracks()) assertLicensed(p.getName() + " / " + t.getTitle(), t);
        }
        for (MusicLibrary.Sound s : lib.getSounds()) assertLicensed(s.getName(), s);
    }

    @Test
    public void starterPackMergesWithoutDuplicates() {
        MusicLibrary library = new MusicLibrary();
        MusicLibrary.Playlist mine = new MusicLibrary.Playlist("tavern");
        mine.getTracks().add(new MusicLibrary.Track("Old inn", "https://youtu.be/dQw4w9WgXcQ"));
        library.getPlaylists().add(mine);
        library.getSounds().add(new MusicLibrary.Sound("River", "https://youtu.be/dQw4w9WgXcQ", 0, 0));
        library.getSounds().add(new MusicLibrary.Sound("Rain", "https://example.com/my-rain.mp3", 0, 0));

        MusicLibrary starter = MusicLibrary.starter();
        int starterTavernTracks = starter.findPlaylist("Tavern").getTracks().size();
        int added = library.addStarterPack();
        int expected = starter.getPlaylists().size() - 1 + starterTavernTracks + starter.getSounds().size() - 1;
        assertEquals(expected, added);
        assertSame(mine, library.findPlaylist("Tavern"));
        assertEquals(1 + starterTavernTracks, mine.getTracks().size());
        assertFalse(library.findSound("river").unsupported());
        assertNotNull(library.findSound("river").getAttribution());
        assertEquals("https://example.com/my-rain.mp3", library.findSound("rain").getUrl());
        assertEquals("Combat", library.findPlaylist(library.getCombatPlaylistId()).getName());
        assertEquals(0, library.addStarterPack());
        assertEquals(1, library.removeUnsupported());
    }

    @Test
    public void sfxStopCueIsAStop() {
        MusicCue cue = MusicCue.findAll("[sfx: stop]").get(0);
        assertEquals(MusicCue.Kind.SFX, cue.kind());
        assertTrue(cue.isStop());
    }

    @Test
    public void closingCuesPairWithTheirOpeners() {
        String text = "[music: Tavern] talk [sfx: Rain] wet [/sfx: Rain] dry [/music] [music] [/sfx]";
        List<MusicCue> cues = MusicCue.findAll(text);
        assertEquals(5, cues.size());
        assertFalse(cues.get(0).closing());
        assertFalse(cues.get(0).isStop());
        assertTrue(cues.get(2).closing());
        assertTrue(cues.get(2).isStop());
        assertEquals("Rain", cues.get(2).name());
        assertEquals(MusicCue.Kind.MUSIC, cues.get(3).kind());
        assertEquals("", cues.get(3).name());
        assertEquals(MusicCue.Kind.SFX, cues.get(4).kind());
        assertEquals("[/music: Tavern]", MusicCue.closingMarker(MusicCue.Kind.MUSIC, "Tavern"));
        assertEquals("[/sfx]", MusicCue.closingMarker(MusicCue.Kind.SFX, null));
        assertEquals("a  b", MusicCue.stripMarkers("a [music: X] b[/music: X]"));
    }

    @Test
    public void highlighterGivesEachCueKindItsOwnStyle() {
        String text = "x [music: T] y [/music: T] [sfx: R] [/sfx] [READ ALOUD] [map:m|M]";
        var spans = com.dnd.ui.components.StorylineHighlighter.spans(text);
        assertEquals(text.length(), spans.length());
        java.util.List<String> styles = new java.util.ArrayList<>();
        spans.forEach(s -> { if (!s.getStyle().isEmpty()) styles.add(s.getStyle().iterator().next()); });
        assertEquals(List.of("cue-music-start", "cue-music-end", "cue-sfx-start", "cue-sfx-end",
            "mk-read-aloud", "mk-map"), styles);
    }

    @Test
    public void zurabaachStorylineCuesAreKnownAndPaired() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get("src/main/resources/data/custom-campaigns/zurabaach");
        MusicLibrary lib = MusicLibrary.load(root);
        String story = java.nio.file.Files.readString(
            root.resolve("storyline/multiverse arc/Dungeonland arc/dungeonland 2"));
        java.util.Map<String, Integer> open = new java.util.HashMap<>();
        List<MusicCue> cues = MusicCue.findAll(story);
        assertTrue(cues.size() > 20);
        for (MusicCue cue : cues) {
            boolean known = cue.kind() == MusicCue.Kind.MUSIC
                ? lib.findPlaylist(cue.name()) != null
                : lib.findSound(cue.name()) != null;
            assertTrue("Unknown cue: " + cue.name(), known);
            String key = cue.kind() + ":" + cue.name().toLowerCase();
            int depth = open.getOrDefault(key, 0) + (cue.closing() ? -1 : 1);
            assertTrue("Close without open: " + cue.name(), depth >= 0);
            open.put(key, depth);
        }
        open.forEach((k, v) -> assertEquals("Unclosed cue " + k, Integer.valueOf(0), v));
    }
}
