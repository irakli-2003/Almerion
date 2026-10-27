package com.dnd.ui.i18n;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class TranslationServiceTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final Map<String, String> GERMAN = Map.of(
        "Create Character", "Charakter erstellen",
        "Level {0}", "Stufe {0}",
        "HP {0}/{1}", "TP {0}/{1}",
        "Needs {A} XP (has {B})", "Benötigt {A} EP (hat {B})",
        "Casting ability {A}", "Zauberattribut {A}",
        "Intelligence", "Intelligenz",
        "Broken {0}", "Kaputt",
        "Lost {A} wildcard", "Verloren",
        "{A} is dead.", "{A} ist tot.",
        "First line", "Erste Zeile");

    private final AtomicInteger loads = new AtomicInteger();

    private TranslationService german() {
        TranslationService service = new TranslationService(code -> {
            loads.incrementAndGet();
            return "de".equals(code) ? GERMAN : Map.of();
        });
        service.setLanguage("de");
        return service;
    }

    @Test
    public void englishIsReturnedUnchangedWithoutLoadingAnything() {
        TranslationService service = new TranslationService(code -> {
            loads.incrementAndGet();
            return GERMAN;
        });
        assertEquals("Create Character", service.lookup("Create Character"));
        service.setLanguage("en");
        assertEquals("Create Character", service.lookup("Create Character"));
        assertEquals(0, loads.get());
    }

    @Test
    public void exactKeysKeepNumbersAndSurroundingSpace() {
        TranslationService service = german();
        assertEquals("Charakter erstellen", service.lookup("Create Character"));
        assertEquals("Stufe 12", service.lookup("Level 12"));
        assertEquals("  TP 5/10 ", service.lookup("  HP 5/10 "));
    }

    @Test
    public void wildcardPatternsTranslateRuntimeText() {
        TranslationService service = german();
        assertEquals("Benötigt 300 EP (hat 120)", service.lookup("Needs 300 XP (has 120)"));
        assertEquals("Zauberattribut Intelligenz", service.lookup("Casting ability Intelligence"));
        assertEquals("Goblin 3 ist tot.", service.lookup("Goblin 3 is dead."));
    }

    @Test
    public void missingTextStaysEnglish() {
        TranslationService service = german();
        assertEquals("Something new", service.lookup("Something new"));
        assertEquals("42", service.lookup("42"));
    }

    @Test
    public void translationsThatLosePlaceholdersAreIgnored() {
        TranslationService service = german();
        assertEquals("Broken 7", service.lookup("Broken 7"));
        assertEquals("Lost x wildcard", service.lookup("Lost x wildcard"));
    }

    @Test
    public void multiLineTextIsTranslatedLineByLine() {
        TranslationService service = german();
        assertEquals("Erste Zeile\nStufe 2", service.lookup("First line\nLevel 2"));
    }

    @Test
    public void switchingLanguageLoadsItsDictionary() {
        Map<String, Map<String, String>> dictionaries = new HashMap<>();
        dictionaries.put("de", GERMAN);
        dictionaries.put("fr", Map.of("Create Character", "Créer un personnage"));
        TranslationService service = new TranslationService(code -> dictionaries.getOrDefault(code, Map.of()));
        service.setLanguage("fr");
        assertEquals("Créer un personnage", service.lookup("Create Character"));
        service.setLanguage("de");
        assertEquals("Charakter erstellen", service.lookup("Create Character"));
        service.setLanguage("en");
        assertEquals("Create Character", service.lookup("Create Character"));
    }

    @Test
    public void placeholderCheckCoversBothKinds() {
        assertEquals(true, TranslationService.placeholdersIntact("Level {0} of {A}", "{A}: Stufe {0}"));
        assertEquals(false, TranslationService.placeholdersIntact("Level {0} of {A}", "Stufe {0}"));
        assertEquals(false, TranslationService.placeholdersIntact("Level {0} of {A}", "Stufe von {A}"));
    }

    @Test
    public void everyShippedDictionaryLoadsAndKeepsItsPlaceholders() {
        List<Language> languages = Language.all();
        assertSame(Language.english(), languages.get(0));
        for (Language language : languages.subList(1, languages.size())) {
            Map<String, String> dictionary = TranslationService.loadBundled(language.code());
            assertNotNull(language.code(), dictionary);
            assertEquals(language.code() + " dictionary is empty", false, dictionary.isEmpty());
            for (Map.Entry<String, String> entry : dictionary.entrySet()) {
                assertEquals(language.code() + ": \"" + entry.getKey() + "\" -> \"" + entry.getValue() + "\"",
                    true, TranslationService.placeholdersIntact(entry.getKey(), entry.getValue()));
            }
        }
    }

    @Test
    public void allPlannedLanguagesShip() {
        for (String code : List.of("ar", "de", "es", "fr", "hi", "it", "ja", "ka", "ko", "pl", "pt-BR", "ru", "tr", "uk", "zh-CN")) {
            assertEquals(code, Language.byCode(code).code());
        }
    }

    @Test
    public void settingsRoundTrip() {
        Path file = temp.getRoot().toPath().resolve("data").resolve("settings.json");
        AppSettings settings = AppSettings.load(file);
        assertEquals("en", settings.getLanguage());
        settings.setLanguage("ka");
        assertEquals("ka", AppSettings.load(file).getLanguage());
        settings.setLanguage("not-a-language");
        assertEquals("en", AppSettings.load(file).getLanguage());
    }
}
