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
 * their own CSS class (see {@code dnd-theme.css}).
 */
public final class StorylineHighlighter {

    public static final String MUSIC_START = "cue-music-start";
    public static final String MUSIC_END = "cue-music-end";
    public static final String SFX_START = "cue-sfx-start";
    public static final String SFX_END = "cue-sfx-end";
    public static final String READ_ALOUD = "mk-read-aloud";
    public static final String DM_NOTE = "mk-dm-note";
    public static final String MAP = "mk-map";

    private static final Pattern READ_ALOUD_TAG = Pattern.compile("\\[/?READ ALOUD\\]");
    private static final Pattern DM_NOTE_TAG = Pattern.compile("\\[/?DM NOTE\\]");

    private record Range(int start, int end, String style) { }

    private StorylineHighlighter() { }

    public static StyleSpans<Collection<String>> spans(String text) {
        String t = text == null ? "" : text;
        List<Range> ranges = new ArrayList<>();
        for (MusicCue cue : MusicCue.findAll(t)) ranges.add(new Range(cue.start(), cue.end(), styleOf(cue)));
        for (MapLink link : MapLink.findAll(t)) ranges.add(new Range(link.start(), link.end(), MAP));
        addAll(ranges, READ_ALOUD_TAG.matcher(t), READ_ALOUD);
        addAll(ranges, DM_NOTE_TAG.matcher(t), DM_NOTE);
        ranges.sort(Comparator.comparingInt(Range::start));

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
