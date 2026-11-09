package com.dnd.data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reference to a battle map embedded in a storyline file, written as
 * {@code [map:<id>|<label>]}.
 *
 * <p>Session notes are plain text on purpose - they stay readable and diffable outside the
 * app - so a link has to be a textual marker rather than rich-text markup. The bracketed
 * form is unobtrusive when read aloud from the file and unambiguous to parse, and the label
 * keeps the note readable even if the map is later renamed or deleted.</p>
 */
public record MapLink(String mapId, String label, int start, int end) {

    private static final Pattern PATTERN = Pattern.compile("\\[map:([^\\]|]+)(?:\\|([^\\]]*))?\\]");

    /** Renders a link marker for insertion into a file. */
    public static String marker(String mapId, String label) {
        if (mapId == null || mapId.isBlank()) return "";
        String safeId = mapId.trim();
        if (label == null || label.isBlank()) return "[map:" + safeId + "]";
        // A label containing ] or | would make the marker unparseable, so those are dropped
        // rather than silently producing a broken link.
        return "[map:" + safeId + "|" + label.replaceAll("[\\]|]", "").trim() + "]";
    }

    /** Every map link in the given text, in order of appearance. */
    public static List<MapLink> findAll(String text) {
        List<MapLink> links = new ArrayList<>();
        if (text == null || text.isEmpty()) return links;
        Matcher matcher = PATTERN.matcher(text);
        while (matcher.find()) {
            String id = matcher.group(1).trim();
            String label = matcher.group(2) == null ? id : matcher.group(2).trim();
            links.add(new MapLink(id, label.isEmpty() ? id : label, matcher.start(), matcher.end()));
        }
        return links;
    }

    /**
     * The link containing the given caret offset, or null.
     *
     * <p>The whole marker counts as the clickable region, including its brackets, because a
     * user clicking at either edge of a link plainly means that link.</p>
     */
    public static MapLink at(String text, int caret) {
        for (MapLink link : findAll(text)) {
            if (caret >= link.start() && caret <= link.end()) return link;
        }
        return null;
    }

    /** Replaces every marker with just its label, for read-aloud output. */
    public static String stripMarkers(String text) {
        if (text == null) return "";
        return PATTERN.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
            m.group(2) == null || m.group(2).isBlank() ? m.group(1).trim() : m.group(2).trim()));
    }

    /** Keeps IDs stable while displaying the location's current name, not a stale template label. */
    public static String refreshLabels(String text, java.util.Map<String, String> names) {
        if (text == null) return "";
        return PATTERN.matcher(text).replaceAll(m -> {
            String name = names.get(m.group(1).trim());
            return Matcher.quoteReplacement(name == null || name.isBlank()
                ? m.group() : marker(m.group(1).trim(), name));
        });
    }
}
