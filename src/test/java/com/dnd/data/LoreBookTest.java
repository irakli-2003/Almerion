package com.dnd.data;

import org.junit.Test;

import static org.junit.Assert.*;

public class LoreBookTest {
    @Test public void structuredLoreRoundTripsWithoutLosingText() {
        LoreBook original = new LoreBook();
        original.title = "Zurabaach";
        var section = new LoreBook.Section("The war");
        section.articles.add(new LoreBook.Article("A disputed account", "First line.\n\nSecond line.\n"));
        section.articles.add(new LoreBook.Article("საიდუმლო", "უცნობი\n"));
        original.sections.add(section);
        LoreBook restored = LoreBook.parse(original.serialize());
        assertEquals("Zurabaach", restored.title);
        assertEquals("The war", restored.sections.getFirst().title);
        assertEquals("First line.\n\nSecond line.\n", restored.sections.getFirst().articles.getFirst().text);
        assertEquals("უცნობი\n", restored.sections.getFirst().articles.getLast().text);
    }

    @Test public void malformedLoreFailsRatherThanDisappearing() {
        assertThrows(IllegalArgumentException.class,
            () -> LoreBook.parse("{\"title\":\"Book\",\"sections\":[{\"title\":\"A\",\"articles\":[{\"title\":\"B\"}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> LoreBook.parse("{broken"));
    }
}
