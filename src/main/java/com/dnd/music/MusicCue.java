package com.dnd.music;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A music or sound cue written into a storyline file. Cues come in pairs so the DM clicks the
 * opening one when a scene begins and the closing one when it is over:
 * <pre>
 *   [music: Tavern]   ...scene...   [/music: Tavern]
 *   [sfx: Rain]       ...scene...   [/sfx: Rain]
 * </pre>
 * The name on a closing cue is optional ({@code [/music]}), and the older one-shot stop cues
 * {@code [music: stop]} / {@code [sfx: stop]} still work. Names (not ids) are used so the
 * files stay readable when printed or read aloud.
 */
public record MusicCue(Kind kind, String name, boolean closing, int start, int end) {

    public enum Kind { MUSIC, SFX }

    private static final Pattern PATTERN = Pattern.compile(
        "\\[(/)?(music|sfx)[ \\t]*(?::[ \\t]*([^\\]\\n]*))?\\]", Pattern.CASE_INSENSITIVE);

    /** An opening cue. */
    public MusicCue(Kind kind, String name, int start, int end) {
        this(kind, name, false, start, end);
    }

    /** True for a closing cue or the legacy {@code [music: stop]} / {@code [sfx: stop]}. */
    public boolean isStop() {
        return closing || "stop".equalsIgnoreCase(name);
    }

    /** Opening cue, e.g. {@code [music: Tavern]}. */
    public static String marker(Kind kind, String name) {
        return "[" + tag(kind) + ": " + clean(name) + "]";
    }

    /** Closing cue, e.g. {@code [/music: Tavern]}. */
    public static String closingMarker(Kind kind, String name) {
        String safe = clean(name);
        return "[/" + tag(kind) + (safe.isEmpty() ? "" : ": " + safe) + "]";
    }

    private static String clean(String name) {
        return name == null ? "" : name.replace("]", "").replace("\n", " ").trim();
    }

    private static String tag(Kind kind) {
        return kind == Kind.SFX ? "sfx" : "music";
    }

    public static List<MusicCue> findAll(String text) {
        List<MusicCue> cues = new ArrayList<>();
        if (text == null || text.isEmpty()) return cues;
        Matcher m = PATTERN.matcher(text);
        while (m.find()) {
            boolean closing = m.group(1) != null;
            String name = m.group(3) == null ? "" : m.group(3).trim();
            // An opening cue needs something to play; "[music]" alone is just text.
            if (!closing && name.isEmpty()) continue;
            Kind kind = m.group(2).equalsIgnoreCase("sfx") ? Kind.SFX : Kind.MUSIC;
            cues.add(new MusicCue(kind, name, closing, m.start(), m.end()));
        }
        return cues;
    }

    public static MusicCue at(String text, int caret) {
        for (MusicCue cue : findAll(text)) {
            if (caret >= cue.start() && caret <= cue.end()) return cue;
        }
        return null;
    }

    /** Removes every cue, for text shown to the players. */
    public static String stripMarkers(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text);
        List<MusicCue> cues = findAll(text);
        for (int i = cues.size() - 1; i >= 0; i--) sb.delete(cues.get(i).start(), cues.get(i).end());
        return sb.toString();
    }
}
