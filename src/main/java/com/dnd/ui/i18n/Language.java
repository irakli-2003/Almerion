package com.dnd.ui.i18n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A language the UI can be shown in. Only languages whose dictionary ships with the app
 * ({@code /i18n/<code>.json}) are offered.
 */
public record Language(String code, String englishName, String nativeName) {

    public static final String ENGLISH_CODE = "en";

    private static final Set<String> RIGHT_TO_LEFT = Set.of("ar", "he", "fa", "ur");

    private static final List<Language> ALL = build();

    /** English first, then every shipped language alphabetically by English name. */
    public static List<Language> all() {
        return ALL;
    }

    public static Language english() {
        return ALL.get(0);
    }

    /** The language with this code, or English when the code is unknown. */
    public static Language byCode(String code) {
        if (code != null) {
            for (Language language : ALL) {
                if (language.code.equalsIgnoreCase(code)) return language;
            }
        }
        return english();
    }

    public static boolean isEnglish(String code) {
        return code == null || code.isBlank() || ENGLISH_CODE.equalsIgnoreCase(code);
    }

    public boolean isEnglish() {
        return isEnglish(code);
    }

    public boolean isRightToLeft() {
        return RIGHT_TO_LEFT.contains(code);
    }

    /**
     * A system font to use instead of the theme font, for scripts the theme font does not cover
     * well; {@code null} to keep the theme font.
     */
    public String fontOverride() {
        return "hi".equals(code) ? "Nirmala UI" : null;
    }

    /** "ქართული (Georgian)"; English is shown just as "English". */
    public String displayName() {
        return nativeName.equals(englishName) ? nativeName : nativeName + " (" + englishName + ")";
    }

    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        return englishName.toLowerCase(Locale.ROOT).contains(q)
            || nativeName.toLowerCase(Locale.ROOT).contains(q)
            || code.toLowerCase(Locale.ROOT).startsWith(q);
    }

    @Override
    public String toString() {
        return displayName();
    }

    private static List<Language> build() {
        String[][] table = {
            {"ar", "Arabic", "العربية"},
            {"zh-CN", "Chinese (Simplified)", "简体中文"},
            {"fr", "French", "Français"},
            {"ka", "Georgian", "ქართული"},
            {"de", "German", "Deutsch"},
            {"hi", "Hindi", "हिन्दी"},
            {"it", "Italian", "Italiano"},
            {"ja", "Japanese", "日本語"},
            {"ko", "Korean", "한국어"},
            {"pl", "Polish", "Polski"},
            {"pt-BR", "Portuguese (Brazil)", "Português (Brasil)"},
            {"ru", "Russian", "Русский"},
            {"es", "Spanish", "Español"},
            {"tr", "Turkish", "Türkçe"},
            {"uk", "Ukrainian", "Українська"},
        };
        List<Language> others = new ArrayList<>();
        for (String[] row : table) {
            if (Language.class.getResource(TranslationService.resourcePath(row[0])) != null) {
                others.add(new Language(row[0], row[1], row[2]));
            }
        }
        others.sort(Comparator.comparing(Language::englishName, String.CASE_INSENSITIVE_ORDER));
        List<Language> list = new ArrayList<>();
        list.add(new Language(ENGLISH_CODE, "English", "English"));
        list.addAll(others);
        return Collections.unmodifiableList(list);
    }
}
