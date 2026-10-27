package com.dnd.ui.i18n;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TranslationServiceTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final FakeProvider provider = new FakeProvider();

    private TranslationService service(Path dir) {
        return new TranslationService(provider, dir, Runnable::run, 10);
    }

    @Test
    public void englishNeverCallsTheProvider() throws Exception {
        TranslationService service = service(temp.getRoot().toPath());
        assertEquals("Create Character", service.lookup("Create Character"));
        service.setLanguage("en");
        assertEquals("Log Out", service.lookup("Log Out"));
        service.awaitIdle(1000);
        assertTrue(provider.requests.isEmpty());
        assertTrue(Files.notExists(temp.getRoot().toPath().resolve("en.json")));
    }

    @Test
    public void unknownTextIsQueuedThenTranslatedInOneBatch() throws Exception {
        TranslationService service = service(temp.getRoot().toPath());
        AtomicInteger arrivals = new AtomicInteger();
        service.addListener(lang -> arrivals.incrementAndGet());
        service.setLanguage("de");

        assertNull(service.lookup("Create Character"));
        assertNull(service.lookup("Level 3"));
        assertNull(service.lookup("Log Out"));
        service.awaitIdle(5000);

        assertEquals(1, provider.requests.size());
        assertEquals("Create Character\nLevel {0}\nLog Out", provider.requests.get(0));
        assertEquals("DE:Create Character", service.lookup("Create Character"));
        assertEquals("DE:Level 3", service.lookup("Level 3"));
        assertEquals("DE:Level 12", service.lookup("Level 12"));
        assertEquals("DE:Log Out", service.lookup("Log Out"));
        assertTrue(arrivals.get() >= 1);
    }

    @Test
    public void alreadyTranslatedTextIsLeftAlone() throws Exception {
        TranslationService service = service(temp.getRoot().toPath());
        service.setLanguage("de");
        service.lookup("Log Out");
        service.awaitIdle(5000);
        int before = provider.requests.size();
        assertEquals("DE:Log Out", service.lookup("DE:Log Out"));
        service.awaitIdle(1000);
        assertEquals(before, provider.requests.size());
    }

    @Test
    public void mismatchedBatchFallsBackToOneRequestPerString() throws Exception {
        provider.mergeLines = true;
        TranslationService service = service(temp.getRoot().toPath());
        service.setLanguage("fr");
        service.lookup("Attack");
        service.lookup("Defend");
        service.lookup("Flee");
        service.awaitIdle(5000);

        assertEquals(4, provider.requests.size());
        assertEquals("FR:Attack", service.lookup("Attack"));
        assertEquals("FR:Defend", service.lookup("Defend"));
        assertEquals("FR:Flee", service.lookup("Flee"));
    }

    @Test
    public void multiLineTextIsTranslatedOnItsOwn() throws Exception {
        TranslationService service = service(temp.getRoot().toPath());
        service.setLanguage("fr");
        service.lookup("First line\nSecond line");
        service.lookup("Attack");
        service.awaitIdle(5000);
        assertEquals("FR:First line\nFR:Second line", service.lookup("First line\nSecond line"));
        assertTrue(provider.requests.contains("Attack"));
    }

    @Test
    public void translationThatLosesAPlaceholderFallsBackToEnglish() throws Exception {
        provider.dropPlaceholders = true;
        TranslationService service = service(temp.getRoot().toPath());
        service.setLanguage("de");
        service.lookup("HP 5/10");
        service.awaitIdle(5000);
        assertEquals("HP 5/10", service.lookup("HP 5/10"));
    }

    @Test
    public void failuresKeepEnglishWithoutHammeringTheService() throws Exception {
        provider.failing = true;
        TranslationService service = service(temp.getRoot().toPath());
        service.setLanguage("de");
        assertNull(service.lookup("Attack"));
        service.awaitIdle(5000);
        int calls = provider.requests.size();
        assertNull(service.lookup("Attack"));
        service.awaitIdle(1000);
        assertEquals(calls, provider.requests.size());
    }

    @Test
    public void cacheSurvivesARestart() throws Exception {
        Path dir = temp.getRoot().toPath().resolve("translations");
        TranslationService first = service(dir);
        first.setLanguage("ka");
        first.lookup("Create Character");
        first.lookup("Level 2");
        first.awaitIdle(5000);

        Path file = dir.resolve("ka.json");
        assertTrue(Files.exists(file));
        Map<String, String> saved = TranslationService.load(file);
        assertEquals("KA:Level {0}", saved.get("Level {0}"));

        int calls = provider.requests.size();
        TranslationService second = service(dir);
        second.setLanguage("ka");
        assertEquals("KA:Create Character", second.lookup("Create Character"));
        assertEquals("KA:Level 9", second.lookup("Level 9"));
        assertEquals(calls, provider.requests.size());
    }

    @Test
    public void batchesStayUnderTheSizeLimit() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 200; i++) keys.add("Some reasonably long button caption number " + (char) ('a' + i % 26));
        keys.add("two\nlines");
        List<List<String>> batches = TranslationService.batches(keys);
        int total = 0;
        for (List<String> batch : batches) {
            total += batch.size();
            if (batch.size() > 1) assertTrue(String.join("\n", batch).length() <= TranslationService.MAX_BATCH_CHARS);
        }
        assertEquals(keys.size(), total);
        assertTrue(batches.contains(List.of("two\nlines")));
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
