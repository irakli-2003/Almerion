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
    public void parsesCommonVideoLinks() {
        assertEquals("dQw4w9WgXcQ", YouTubeLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ").videoId());
        assertEquals("dQw4w9WgXcQ", YouTubeLink.parse("youtube.com/watch?feature=share&v=dQw4w9WgXcQ").videoId());
        assertEquals("dQw4w9WgXcQ", YouTubeLink.parse("https://youtu.be/dQw4w9WgXcQ?si=abc").videoId());
        assertEquals("dQw4w9WgXcQ", YouTubeLink.parse("https://www.youtube.com/shorts/dQw4w9WgXcQ").videoId());
        assertEquals("dQw4w9WgXcQ", YouTubeLink.parse("https://www.youtube.com/embed/dQw4w9WgXcQ").videoId());
        assertEquals("dQw4w9WgXcQ", YouTubeLink.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ").videoId());
        assertEquals("dQw4w9WgXcQ", YouTubeLink.parse("dQw4w9WgXcQ").videoId());
        assertFalse(YouTubeLink.parse("https://youtu.be/dQw4w9WgXcQ").isPlaylist());
    }

    @Test
    public void parsesPlaylistsAndStartTimes() {
        YouTubeLink list = YouTubeLink.parse("https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG");
        assertTrue(list.isPlaylist());
        assertNull(list.videoId());
        YouTubeLink inList = YouTubeLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG");
        assertTrue(inList.isPlaylist());
        assertEquals(90, YouTubeLink.parse("https://youtu.be/dQw4w9WgXcQ?t=90").startSeconds());
        assertEquals(90, YouTubeLink.parse("https://youtu.be/dQw4w9WgXcQ?t=1m30s").startSeconds());
        assertEquals(3725, YouTubeLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1h2m5s").startSeconds());
    }

    @Test
    public void rejectsNonYouTubeLinks() {
        assertNull(YouTubeLink.parse(""));
        assertNull(YouTubeLink.parse("https://vimeo.com/123456"));
        assertNull(YouTubeLink.parse("https://www.youtube.com/watch?v=short"));
        assertNull(YouTubeLink.parse("https://www.youtube.com/"));
        assertNull(YouTubeLink.parse("not a link at all"));
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
        tavern.getTracks().add(new MusicLibrary.Track("Inn", "https://youtu.be/dQw4w9WgXcQ"));
        MusicLibrary.Playlist battle = new MusicLibrary.Playlist("Battle");
        library.getPlaylists().add(tavern);
        library.getPlaylists().add(battle);
        library.getSounds().add(new MusicLibrary.Sound("Thunder", "https://youtu.be/dQw4w9WgXcQ", 3, 8));
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

    @Test
    public void starterPackLinksAreAllSingleYouTubeVideos() {
        MusicLibrary starter = MusicLibrary.starter();
        assertTrue(starter.getPlaylists().size() >= 10);
        assertTrue(starter.getSounds().size() >= 20);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (MusicLibrary.Playlist p : starter.getPlaylists()) {
            assertTrue(p.getName(), ids.add(p.getId()));
            assertFalse(p.getName(), p.getTracks().isEmpty());
            for (MusicLibrary.Track t : p.getTracks()) {
                assertNotNull(t.getUrl(), t.link());
                assertNotNull(t.getUrl(), t.link().videoId());
                assertFalse(t.getTitle().isBlank());
            }
        }
        for (MusicLibrary.Sound s : starter.getSounds()) {
            assertTrue(s.getName(), ids.add(s.getId()));
            assertNotNull(s.getUrl(), s.link().videoId());
        }
    }

    @Test
    public void starterPackMergesWithoutDuplicates() {
        MusicLibrary library = new MusicLibrary();
        MusicLibrary.Playlist mine = new MusicLibrary.Playlist("tavern");
        library.getPlaylists().add(mine);
        library.getSounds().add(new MusicLibrary.Sound("River", "https://youtu.be/dQw4w9WgXcQ", 0, 0));

        int added = library.addStarterPack();
        int total = MusicLibrary.starter().getPlaylists().size() + MusicLibrary.starter().getSounds().size();
        assertEquals(total - 2, added);
        assertSame(mine, library.findPlaylist("Tavern"));
        assertEquals("https://youtu.be/dQw4w9WgXcQ", library.findSound("river").getUrl());
        assertEquals("Combat", library.findPlaylist(library.getCombatPlaylistId()).getName());
        assertEquals(0, library.addStarterPack());
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
