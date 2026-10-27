package com.dnd.ui.i18n;

import com.dnd.data.AppHome;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Translates UI strings from English, remembering every answer in a per-language JSON cache
 * ({@code data/translations/<code>.json}). Lookups never block: unknown strings are queued,
 * sent to the provider in batches on a background thread, and listeners are told when the
 * answers arrive.
 */
public final class TranslationService {
    private static final Logger LOGGER = Logger.getLogger(TranslationService.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final int MAX_BATCH_CHARS = 1800;
    private static final long RETRY_AFTER_MS = 60_000;

    /** Called on the callback executor (the FX thread in the app). */
    public interface Listener {
        void translationsArrived(String language);

        default void busyChanged(boolean busy) {
        }
    }

    private final TranslationProvider provider;
    private final Path cacheDir;
    private final Executor callbacks;
    private final long debounceMs;
    private final ScheduledExecutorService worker;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile String language = Language.ENGLISH_CODE;
    private long generation;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Set<String> outputs = ConcurrentHashMap.newKeySet();
    private final Set<String> pending = new LinkedHashSet<>();
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private boolean flushScheduled;
    private boolean busy;

    public TranslationService(TranslationProvider provider, Path cacheDir, Executor callbacks, long debounceMs) {
        this.provider = provider;
        this.cacheDir = cacheDir;
        this.callbacks = callbacks;
        this.debounceMs = debounceMs;
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ui-translation");
            t.setDaemon(true);
            return t;
        });
    }

    /** Google Translate with MyMemory as a fallback, cached under the app's data folder. */
    public static TranslationService createDefault(Executor callbacks) {
        TranslationProvider provider = new FallbackProvider(List.of(new GoogleTranslateProvider(), new MyMemoryProvider()));
        return new TranslationService(provider, AppHome.resolve("data", "translations"), callbacks, 150);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public String getLanguage() {
        return language;
    }

    public boolean isEnglish() {
        return Language.isEnglish(language);
    }

    /** Switches language and loads that language's cache. Queued work for the old language is dropped. */
    public synchronized void setLanguage(String code) {
        String next = Language.isEnglish(code) ? Language.ENGLISH_CODE : code;
        generation++;
        language = next;
        cache.clear();
        outputs.clear();
        pending.clear();
        failedAt.clear();
        if (!Language.isEnglish(next)) {
            cache.putAll(load(cacheFile(next)));
            outputs.addAll(cache.values());
        }
        setBusy(false);
    }

    /**
     * The translation of {@code text}, the text itself when it needs no translation (or is
     * already a translation), or {@code null} when the answer isn't known yet - in which case
     * it has been queued and listeners will be notified.
     */
    public String lookup(String text) {
        if (text == null || isEnglish()) return text;
        TextTemplate template = TextTemplate.of(text);
        if (template == null) return text;
        String translated = cache.get(template.key());
        if (translated != null) return template.fill(translated);
        if (outputs.contains(template.key())) return text;
        request(template.key());
        return null;
    }

    /** Waits until queued work has been processed (tests only). */
    void awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (this) {
                if (pending.isEmpty() && !flushScheduled) break;
            }
            Thread.sleep(10);
        }
        try {
            worker.submit(() -> { }).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private synchronized void request(String key) {
        Long failed = failedAt.get(key);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) return;
        if (!pending.add(key)) return;
        if (!flushScheduled) {
            flushScheduled = true;
            worker.schedule(this::flush, debounceMs, TimeUnit.MILLISECONDS);
        }
        setBusy(true);
    }

    private void flush() {
        List<String> keys;
        String lang;
        long gen;
        synchronized (this) {
            flushScheduled = false;
            keys = new ArrayList<>(pending);
            lang = language;
            gen = generation;
        }
        boolean changed = false;
        for (List<String> batch : batches(keys)) {
            Map<String, String> results = translateBatch(batch, lang);
            synchronized (this) {
                if (gen != generation) return;
                long now = System.currentTimeMillis();
                for (String key : batch) {
                    String value = results.get(key);
                    if (value == null) {
                        failedAt.put(key, now);
                    } else {
                        cache.put(key, value);
                        outputs.add(value);
                        changed = true;
                    }
                    pending.remove(key);
                }
            }
            if (!results.isEmpty()) notifyArrived(lang);
        }
        if (changed) save(lang);
        synchronized (this) {
            if (gen == generation && pending.isEmpty()) setBusy(false);
        }
    }

    /** Groups keys so each request stays under {@link #MAX_BATCH_CHARS}; multi-line keys go alone. */
    static List<List<String>> batches(List<String> keys) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int size = 0;
        for (String key : keys) {
            if (key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
                batches.add(List.of(key));
                continue;
            }
            if (!current.isEmpty() && size + key.length() + 1 > MAX_BATCH_CHARS) {
                batches.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(key);
            size += key.length() + 1;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    /**
     * Translates a batch in one request by joining it with line breaks. If the answer doesn't
     * split back into the same number of lines, each key is translated on its own. Keys that
     * fail are missing from the result.
     */
    Map<String, String> translateBatch(List<String> keys, String lang) {
        Map<String, String> results = new LinkedHashMap<>();
        if (keys.size() > 1) {
            String joined;
            try {
                joined = provider.translate(String.join("\n", keys), lang);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Translation request failed", e);
                return results;
            }
            String[] parts = joined.replace("\r\n", "\n").split("\n", -1);
            if (parts.length == keys.size()) {
                for (int i = 0; i < parts.length; i++) results.put(keys.get(i), accept(keys.get(i), parts[i]));
                return results;
            }
        }
        for (String key : keys) {
            try {
                results.put(key, accept(key, provider.translate(key, lang)));
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Translation request failed", e);
                break;
            }
        }
        return results;
    }

    /** The translation to cache, or the English key when the translation lost a placeholder. */
    private static String accept(String key, String translated) {
        if (translated == null || translated.isBlank()) return key;
        String normalized = TextTemplate.normalizePlaceholders(translated.strip());
        return TextTemplate.placeholdersIntact(key, normalized) ? normalized : key;
    }

    private void notifyArrived(String lang) {
        for (Listener listener : listeners) callbacks.execute(() -> listener.translationsArrived(lang));
    }

    private void setBusy(boolean value) {
        if (busy == value) return;
        busy = value;
        for (Listener listener : listeners) callbacks.execute(() -> listener.busyChanged(value));
    }

    Path cacheFile(String lang) {
        return cacheDir.resolve(lang.replaceAll("[^A-Za-z-]", "_") + ".json");
    }

    static Map<String, String> load(Path file) {
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            Map<String, String> map = JSON.readValue(file.toFile(), new TypeReference<Map<String, String>>() { });
            return map == null ? Map.of() : map;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Ignoring unreadable translation cache " + file, e);
            return Map.of();
        }
    }

    private void save(String lang) {
        Map<String, String> snapshot;
        synchronized (this) {
            if (!lang.equals(language)) return;
            snapshot = new TreeMap<>(cache);
        }
        try {
            writeAtomically(cacheFile(lang), snapshot);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save translation cache for " + lang, e);
        }
    }

    static void writeAtomically(Path file, Object value) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        JSON.writeValue(temp.toFile(), value);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
