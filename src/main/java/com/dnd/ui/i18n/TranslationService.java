package com.dnd.ui.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates UI strings with the dictionaries shipped inside the app
 * ({@code /i18n/<code>.json} on the classpath). Nothing is sent over the network.
 *
 * <p>A dictionary maps English keys to translations. Two kinds of key are understood:</p>
 * <ul>
 *   <li>exact text, with each run of digits written as {@code {0}}, {@code {1}}, ...
 *       (see {@link TextTemplate}), e.g. {@code "Level {0}"};</li>
 *   <li>patterns for text built at runtime, with {@code {A}}, {@code {B}}, ... standing for any
 *       value, e.g. {@code "Needs {A} XP (has {B})"}. The values are put back into the
 *       translation unchanged, or translated when they are themselves a known UI string.</li>
 * </ul>
 * <p>Text with no translation stays in English, so a dictionary may be incomplete.</p>
 */
public final class TranslationService {
    private static final Logger LOGGER = Logger.getLogger(TranslationService.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WILDCARD = Pattern.compile("\\{([A-Z])\\}");
    private static final int MEMO_SIZE = 4000;

    private final Function<String, Map<String, String>> loader;
    private volatile String language = Language.ENGLISH_CODE;
    private Map<String, String> exact = Map.of();
    private List<WildcardPattern> patterns = List.of();
    private final Map<String, String> memo = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MEMO_SIZE;
        }
    };

    /** @param loader returns the dictionary for a language code, or an empty map when there is none */
    public TranslationService(Function<String, Map<String, String>> loader) {
        this.loader = loader;
    }

    /** The service backed by the dictionaries shipped in the app. */
    public static TranslationService createDefault() {
        return new TranslationService(TranslationService::loadBundled);
    }

    /** Reads {@code /i18n/<code>.json} from the classpath; an empty map when it is missing or unreadable. */
    public static Map<String, String> loadBundled(String code) {
        try (InputStream in = TranslationService.class.getResourceAsStream(resourcePath(code))) {
            if (in == null) return Map.of();
            Map<String, String> map = JSON.readValue(in, new TypeReference<Map<String, String>>() { });
            return map == null ? Map.of() : map;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Ignoring unreadable dictionary for " + code, e);
            return Map.of();
        }
    }

    static String resourcePath(String code) {
        return "/i18n/" + code + ".json";
    }

    public String getLanguage() {
        return language;
    }

    public boolean isEnglish() {
        return Language.isEnglish(language);
    }

    /** Switches language and loads its dictionary. */
    public synchronized void setLanguage(String code) {
        String next = Language.isEnglish(code) ? Language.ENGLISH_CODE : code;
        memo.clear();
        language = next;
        Map<String, String> exactKeys = new HashMap<>();
        List<WildcardPattern> wildcards = new ArrayList<>();
        if (!Language.isEnglish(next)) {
            for (Map.Entry<String, String> entry : loader.apply(next).entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null || value.isBlank()) continue;
                if (WILDCARD.matcher(key).find()) {
                    WildcardPattern pattern = WildcardPattern.of(key, value);
                    if (pattern != null) wildcards.add(pattern);
                } else if (TextTemplate.placeholdersIntact(key, value)) {
                    exactKeys.put(key, value);
                }
            }
        }
        wildcards.sort(Comparator.comparingInt(WildcardPattern::literalLength).reversed());
        exact = exactKeys;
        patterns = Collections.unmodifiableList(wildcards);
    }

    /** The translation of {@code text}, or {@code text} itself when there is none. Never {@code null} for non-null input. */
    public synchronized String lookup(String text) {
        if (text == null || isEnglish()) return text;
        String cached = memo.get(text);
        if (cached != null) return cached;
        String result = translate(text, 0);
        memo.put(text, result);
        return result;
    }

    private String translate(String text, int depth) {
        TextTemplate template = TextTemplate.of(text);
        if (template == null) return text;
        String hit = exact.get(template.key());
        if (hit != null) return template.fill(hit);
        String core = text.strip();
        if (core.indexOf('\n') >= 0) {
            String[] lines = text.split("\n", -1);
            boolean changed = false;
            for (int i = 0; i < lines.length; i++) {
                String translated = translate(lines[i], depth);
                if (!translated.equals(lines[i])) {
                    lines[i] = translated;
                    changed = true;
                }
            }
            if (changed) return String.join("\n", lines);
        }
        if (depth > 1) return text;
        for (WildcardPattern pattern : patterns) {
            Matcher m = pattern.regex.matcher(core);
            if (!m.matches()) continue;
            Map<String, String> values = new HashMap<>();
            for (int g = 0; g < pattern.names.size(); g++) {
                String value = m.group(g + 1);
                values.putIfAbsent(pattern.names.get(g), translate(value, depth + 1));
            }
            String filled = WILDCARD.matcher(pattern.translation).replaceAll(r -> {
                String value = values.get(r.group(1));
                return Matcher.quoteReplacement(value == null ? r.group() : value);
            });
            return template.leading() + filled + template.trailing();
        }
        return text;
    }

    /** True when {@code translated} keeps every {@code {0}} / {@code {A}} placeholder of {@code key}. */
    public static boolean placeholdersIntact(String key, String translated) {
        if (!TextTemplate.placeholdersIntact(key, translated)) return false;
        Matcher m = WILDCARD.matcher(key);
        while (m.find()) {
            if (!translated.contains(m.group())) return false;
        }
        return true;
    }

    /** A dictionary key with {A}, {B}, ... wildcards compiled to an anchored regular expression. */
    private record WildcardPattern(Pattern regex, List<String> names, String translation, int literalLength) {
        static WildcardPattern of(String key, String translation) {
            if (!placeholdersIntact(key, translation)) return null;
            StringBuilder regex = new StringBuilder();
            List<String> names = new ArrayList<>();
            Matcher m = WILDCARD.matcher(key);
            int last = 0;
            int literal = 0;
            while (m.find()) {
                String text = key.substring(last, m.start());
                regex.append(Pattern.quote(text)).append("(.+?)");
                literal += text.length();
                names.add(m.group(1));
                last = m.end();
            }
            String tail = key.substring(last);
            regex.append(Pattern.quote(tail));
            literal += tail.length();
            return new WildcardPattern(Pattern.compile(regex.toString(), Pattern.DOTALL), List.copyOf(names), translation, literal);
        }
    }
}
