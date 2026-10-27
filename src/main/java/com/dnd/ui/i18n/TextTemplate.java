package com.dnd.ui.i18n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A UI string reduced to the part that needs translating: surrounding whitespace is set aside
 * and every run of digits becomes a numbered placeholder, so "HP 12/20" and "HP 3/20" share
 * the cached translation of "HP {0}/{1}".
 */
public record TextTemplate(String key, List<String> numbers, String leading, String trailing) {

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\s*(\\d+)\\s*\\}");

    /** The template for {@code text}, or {@code null} when the text has nothing to translate. */
    public static TextTemplate of(String text) {
        if (text == null) return null;
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        String core = text.substring(start, end);
        if (!isTranslatable(core)) return null;
        String leading = text.substring(0, start);
        String trailing = text.substring(end);
        // Text that already contains braces can't be told apart from our placeholders.
        if (core.indexOf('{') >= 0 || core.indexOf('}') >= 0) {
            return new TextTemplate(core, List.of(), leading, trailing);
        }
        List<String> numbers = new ArrayList<>();
        Matcher m = DIGITS.matcher(core);
        StringBuilder key = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(key, Matcher.quoteReplacement("{" + numbers.size() + "}"));
            numbers.add(m.group());
        }
        m.appendTail(key);
        return new TextTemplate(key.toString(), Collections.unmodifiableList(numbers), leading, trailing);
    }

    /** At least two characters and at least one letter - numbers, symbols and emoji are left alone. */
    public static boolean isTranslatable(String text) {
        if (text == null) return false;
        String core = text.strip();
        if (core.codePointCount(0, core.length()) < 2) return false;
        return core.codePoints().anyMatch(Character::isLetter);
    }

    /** Rebuilds the full text from a translated template. */
    public String fill(String translatedKey) {
        Matcher m = PLACEHOLDER.matcher(translatedKey);
        StringBuilder out = new StringBuilder(leading);
        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            String value = index < numbers.size() ? numbers.get(index) : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.append(trailing).toString();
    }

    /** Normalises placeholders mangled by the translator ("{ 0 }" back to "{0}"). */
    public static String normalizePlaceholders(String translated) {
        return PLACEHOLDER.matcher(translated).replaceAll("{$1}");
    }

    /** True when every placeholder of {@code key} survived the translation exactly once or more. */
    public static boolean placeholdersIntact(String key, String translated) {
        Matcher m = PLACEHOLDER.matcher(key);
        while (m.find()) {
            if (!translated.contains("{" + m.group(1) + "}")) return false;
        }
        return true;
    }
}
