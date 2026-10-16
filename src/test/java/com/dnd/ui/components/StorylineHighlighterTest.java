package com.dnd.ui.components;

import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.Test;

import java.util.Collection;

import static org.junit.Assert.*;

public class StorylineHighlighterTest {

    /** Style at character {@code index}, or null when unstyled. */
    private static String styleAt(String text, int index) {
        StyleSpans<Collection<String>> spans = StorylineHighlighter.spans(text);
        int pos = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            if (index < pos + span.getLength()) return span.getStyle().stream().findFirst().orElse(null);
            pos += span.getLength();
        }
        return null;
    }

    private static String styleOfLine(String text, String line) {
        return styleAt(text, text.indexOf(line) + 1);
    }

    @Test
    public void headingsGetTheirOwnStyles() {
        String text = String.join("\n",
            "============================================================",
            "== მოქმედება 3 - უგულო დედოფალი",
            "=== SCENE: შეხვედრა ეზოში ===",
            "story text",
            "=== ENCOUNTER: მზრუნველი ===",
            "--- NPC: ყალბი კუ ---",
            "=== RECAP ===",
            "## Markdown chapter",
            "### Sub heading",
            "[CHECK] Performance DC 15",
            " -----+---------+-----");

        assertEquals(StorylineHighlighter.DIVIDER, styleOfLine(text, "======"));
        assertEquals(StorylineHighlighter.CHAPTER, styleOfLine(text, "== მოქმედება"));
        assertEquals(StorylineHighlighter.SCENE, styleOfLine(text, "=== SCENE"));
        assertNull(styleOfLine(text, "story text"));
        assertEquals(StorylineHighlighter.ENCOUNTER, styleOfLine(text, "=== ENCOUNTER"));
        assertEquals(StorylineHighlighter.NPC, styleOfLine(text, "--- NPC"));
        assertEquals(StorylineHighlighter.SUBHEADING, styleOfLine(text, "=== RECAP"));
        assertEquals(StorylineHighlighter.CHAPTER, styleOfLine(text, "## Markdown"));
        assertEquals(StorylineHighlighter.SUBHEADING, styleOfLine(text, "### Sub"));
        assertEquals(StorylineHighlighter.TAG, styleOfLine(text, "[CHECK]"));
        assertNull(styleOfLine(text, " -----+"));
    }

    @Test
    public void wholeHeadingLineIsStyled() {
        String text = "=== SCENE: x ===\nnext";
        assertEquals(StorylineHighlighter.SCENE, styleAt(text, text.indexOf("x ===") + 3));
        assertNull(styleAt(text, text.indexOf("next")));
    }
}
