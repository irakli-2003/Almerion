package com.dnd.ui.i18n;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TextTemplateTest {

    @Test
    public void digitsBecomePlaceholdersAndAreRestored() {
        TextTemplate t = TextTemplate.of("HP 12/20");
        assertEquals("HP {0}/{1}", t.key());
        assertEquals(List.of("12", "20"), t.numbers());
        assertEquals("PV 12/20", t.fill("PV {0}/{1}"));
        assertEquals("20 von 12", t.fill("{1} von {0}"));
    }

    @Test
    public void differentNumbersShareOneTemplate() {
        assertEquals(TextTemplate.of("Level 3").key(), TextTemplate.of("Level 17").key());
    }

    @Test
    public void surroundingWhitespaceIsKeptOutOfTheKey() {
        TextTemplate t = TextTemplate.of("  Log Out \n");
        assertEquals("Log Out", t.key());
        assertEquals("  Abmelden \n", t.fill("Abmelden"));
    }

    @Test
    public void textWithoutLettersIsNotTranslated() {
        assertNull(TextTemplate.of(null));
        assertNull(TextTemplate.of("12/20"));
        assertNull(TextTemplate.of("x"));
        assertNull(TextTemplate.of("  "));
        assertNull(TextTemplate.of("⚔"));
        assertNull(TextTemplate.of("🌐 ●"));
        assertNull(TextTemplate.of("+ - ×"));
        assertTrue(TextTemplate.isTranslatable("Go"));
        assertFalse(TextTemplate.isTranslatable("A"));
    }

    @Test
    public void textWithBracesKeepsItsDigits() {
        TextTemplate t = TextTemplate.of("Use {name} 5 times");
        assertEquals("Use {name} 5 times", t.key());
        assertTrue(t.numbers().isEmpty());
    }

    @Test
    public void placeholderChecks() {
        assertEquals("Stufe {0}", TextTemplate.normalizePlaceholders("Stufe { 0 }"));
        assertTrue(TextTemplate.placeholdersIntact("HP {0}/{1}", "PV {1}/{0}"));
        assertFalse(TextTemplate.placeholdersIntact("HP {0}/{1}", "PV {0}"));
        assertTrue(TextTemplate.placeholdersIntact("Log Out", "Abmelden"));
    }

    @Test
    public void fillHandlesRegexSpecialCharacters() {
        TextTemplate t = TextTemplate.of("Cost 5 gold");
        assertEquals("Kosten $5 \\ Gold", t.fill("Kosten ${0} \\ Gold"));
    }
}
