package com.dnd.ui.components;

import com.dnd.data.MapLink;
import com.dnd.music.MusicCue;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colours the markers in a storyline file so they stand out from the story text: each kind of
 * music / sound cue (start and end), read-aloud and DM-note brackets, and battle-map links get
 * their own CSS class (see {@code dnd-theme.css}), as do chapter, scene, encounter and NPC headings,
 * [CHECK] / [LOOT] tags and divider lines.
 */
public final class StorylineHighlighter {

    public static final String MUSIC_START = "cue-music-start";
    public static final String MUSIC_END = "cue-music-end";
    public static final String SFX_START = "cue-sfx-start";
    public static final String SFX_END = "cue-sfx-end";
    public static final String READ_ALOUD = "mk-read-aloud";
    public static final String DM_NOTE = "mk-dm-note";
    public static final String MAP = "mk-map";
    public static final String CHAPTER = "hd-chapter";
    public static final String SCENE = "hd-scene";
    public static final String ENCOUNTER = "hd-encounter";
    public static final String NPC = "hd-npc";
    public static final String SUBHEADING = "hd-sub";
    public static final String TAG = "mk-tag";
    public static final String DIVIDER = "mk-divider";

    private static final Pattern READ_ALOUD_TAG = Pattern.compile("\\[/?READ ALOUD\\]");
    private static final Pattern DM_NOTE_TAG = Pattern.compile("\\[/?[DG]M NOTE\\]");
    private static final Pattern CHAPTER_LINE = Pattern.compile("(?m)^[ \\t]*(== |#{1,2} )\\S.*$");
    private static final Pattern SCENE_LINE = Pattern.compile("(?m)^[ \\t]*=== SCENE\\b.*$");
    private static final Pattern ENCOUNTER_LINE = Pattern.compile("(?m)^[ \\t]*=== ENCOUNTER\\b.*$");
    private static final Pattern OTHER_BLOCK_LINE = Pattern.compile("(?m)^[ \\t]*=== (?!SCENE\\b|ENCOUNTER\\b)\\S.*$");
    private static final Pattern NPC_LINE = Pattern.compile("(?m)^[ \\t]*--- \\S.* ---[ \\t]*$");
    private static final Pattern SUBHEADING_LINE = Pattern.compile("(?m)^[ \\t]*#{3,} \\S.*$");
    private static final Pattern BLOCK_TAG = Pattern.compile("\\[(CHECK|LOOT)\\]");
    private static final Pattern DIVIDER_LINE = Pattern.compile("(?m)^[ \\t]*(={10,}|-{10,})[ \\t]*$");

    private record Range(int start, int end, String style) { }

    private StorylineHighlighter() { }

    public static StyleSpans<Collection<String>> spans(String text) {
        String t = text == null ? "" : text;
        List<Range> ranges = new ArrayList<>();
        for (MusicCue cue : MusicCue.findAll(t)) ranges.add(new Range(cue.start(), cue.end(), styleOf(cue)));
        for (MapLink link : MapLink.findAll(t)) ranges.add(new Range(link.start(), link.end(), MAP));
        addAll(ranges, READ_ALOUD_TAG.matcher(t), READ_ALOUD);
        addAll(ranges, DM_NOTE_TAG.matcher(t), DM_NOTE);
        addAll(ranges, CHAPTER_LINE.matcher(t), CHAPTER);
        addAll(ranges, SCENE_LINE.matcher(t), SCENE);
        addAll(ranges, ENCOUNTER_LINE.matcher(t), ENCOUNTER);
        addAll(ranges, OTHER_BLOCK_LINE.matcher(t), SUBHEADING);
        addAll(ranges, NPC_LINE.matcher(t), NPC);
        addAll(ranges, SUBHEADING_LINE.matcher(t), SUBHEADING);
        addAll(ranges, BLOCK_TAG.matcher(t), TAG);
        addAll(ranges, DIVIDER_LINE.matcher(t), DIVIDER);
        // Earliest first; at the same start the longer range (a whole heading line) wins.
        ranges.sort(Comparator.comparingInt(Range::start).thenComparing(r -> -(r.end() - r.start())));

        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int pos = 0;
        for (Range r : ranges) {
            if (r.start() < pos) continue;
            builder.add(Set.of(), r.start() - pos);
            builder.add(Set.of(r.style()), r.end() - r.start());
            pos = r.end();
        }
        builder.add(Set.of(), t.length() - pos);
        return builder.create();
    }

    public static String styleOf(MusicCue cue) {
        if (cue.kind() == MusicCue.Kind.SFX) return cue.isStop() ? SFX_END : SFX_START;
        return cue.isStop() ? MUSIC_END : MUSIC_START;
    }

    private static void addAll(List<Range> ranges, Matcher m, String style) {
        while (m.find()) ranges.add(new Range(m.start(), m.end(), style));
    }
}
