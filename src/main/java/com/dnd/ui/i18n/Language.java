package com.dnd.ui.i18n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A language the UI can be shown in. Codes are the ones Google Translate uses, so they can be
 * passed straight to the translation provider.
 */
public record Language(String code, String englishName, String nativeName) {

    public static final String ENGLISH_CODE = "en";

    private static final Set<String> RIGHT_TO_LEFT = Set.of("ar", "he", "iw", "fa", "ur", "ps", "sd", "ug", "yi", "dv", "ckb");

    private static final List<Language> ALL = build();

    /** English first, then every other language alphabetically by English name. */
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
     * A system font to use instead of the theme font, for scripts that JavaFX's font fallback
     * does not cover on Windows (they would otherwise render as empty boxes).
     */
    public String fontOverride() {
        return switch (code) {
            case "am", "ti" -> "Ebrima";
            case "my" -> "Myanmar Text";
            case "mni-Mtei" -> "Nirmala UI";
            default -> null;
        };
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
            {"af", "Afrikaans", "Afrikaans"},
            {"sq", "Albanian", "Shqip"},
            {"am", "Amharic", "አማርኛ"},
            {"ar", "Arabic", "العربية"},
            {"hy", "Armenian", "Հայերեն"},
            {"as", "Assamese", "অসমীয়া"},
            {"ay", "Aymara", "Aymar aru"},
            {"az", "Azerbaijani", "Azərbaycanca"},
            {"bm", "Bambara", "Bamanankan"},
            {"eu", "Basque", "Euskara"},
            {"be", "Belarusian", "Беларуская"},
            {"bn", "Bengali", "বাংলা"},
            {"bho", "Bhojpuri", "भोजपुरी"},
            {"bs", "Bosnian", "Bosanski"},
            {"bg", "Bulgarian", "Български"},
            {"ca", "Catalan", "Català"},
            {"ceb", "Cebuano", "Cebuano"},
            {"ny", "Chichewa", "Chichewa"},
            {"zh-CN", "Chinese (Simplified)", "简体中文"},
            {"zh-TW", "Chinese (Traditional)", "繁體中文"},
            {"co", "Corsican", "Corsu"},
            {"hr", "Croatian", "Hrvatski"},
            {"cs", "Czech", "Čeština"},
            {"da", "Danish", "Dansk"},
            {"dv", "Dhivehi", "ދިވެހި"},
            {"doi", "Dogri", "डोगरी"},
            {"nl", "Dutch", "Nederlands"},
            {"eo", "Esperanto", "Esperanto"},
            {"et", "Estonian", "Eesti"},
            {"ee", "Ewe", "Eʋegbe"},
            {"tl", "Filipino", "Filipino"},
            {"fi", "Finnish", "Suomi"},
            {"fr", "French", "Français"},
            {"fy", "Frisian", "Frysk"},
            {"gl", "Galician", "Galego"},
            {"ka", "Georgian", "ქართული"},
            {"de", "German", "Deutsch"},
            {"el", "Greek", "Ελληνικά"},
            {"gn", "Guarani", "Avañe'ẽ"},
            {"gu", "Gujarati", "ગુજરાતી"},
            {"ht", "Haitian Creole", "Kreyòl ayisyen"},
            {"ha", "Hausa", "Hausa"},
            {"haw", "Hawaiian", "ʻŌlelo Hawaiʻi"},
            {"he", "Hebrew", "עברית"},
            {"hi", "Hindi", "हिन्दी"},
            {"hmn", "Hmong", "Hmoob"},
            {"hu", "Hungarian", "Magyar"},
            {"is", "Icelandic", "Íslenska"},
            {"ig", "Igbo", "Igbo"},
            {"ilo", "Ilocano", "Ilokano"},
            {"id", "Indonesian", "Bahasa Indonesia"},
            {"ga", "Irish", "Gaeilge"},
            {"it", "Italian", "Italiano"},
            {"ja", "Japanese", "日本語"},
            {"jw", "Javanese", "Basa Jawa"},
            {"kn", "Kannada", "ಕನ್ನಡ"},
            {"kk", "Kazakh", "Қазақ тілі"},
            {"km", "Khmer", "ខ្មែរ"},
            {"rw", "Kinyarwanda", "Kinyarwanda"},
            {"gom", "Konkani", "कोंकणी"},
            {"ko", "Korean", "한국어"},
            {"kri", "Krio", "Krio"},
            {"ku", "Kurdish (Kurmanji)", "Kurdî"},
            {"ckb", "Kurdish (Sorani)", "کوردی"},
            {"ky", "Kyrgyz", "Кыргызча"},
            {"lo", "Lao", "ລາວ"},
            {"la", "Latin", "Latina"},
            {"lv", "Latvian", "Latviešu"},
            {"ln", "Lingala", "Lingála"},
            {"lt", "Lithuanian", "Lietuvių"},
            {"lg", "Luganda", "Luganda"},
            {"lb", "Luxembourgish", "Lëtzebuergesch"},
            {"mk", "Macedonian", "Македонски"},
            {"mai", "Maithili", "मैथिली"},
            {"mg", "Malagasy", "Malagasy"},
            {"ms", "Malay", "Bahasa Melayu"},
            {"ml", "Malayalam", "മലയാളം"},
            {"mt", "Maltese", "Malti"},
            {"mi", "Maori", "Māori"},
            {"mr", "Marathi", "मराठी"},
            {"mni-Mtei", "Meiteilon (Manipuri)", "ꯃꯤꯇꯩꯂꯣꯟ"},
            {"lus", "Mizo", "Mizo ṭawng"},
            {"mn", "Mongolian", "Монгол"},
            {"my", "Myanmar (Burmese)", "မြန်မာ"},
            {"ne", "Nepali", "नेपाली"},
            {"no", "Norwegian", "Norsk"},
            {"or", "Odia (Oriya)", "ଓଡ଼ିଆ"},
            {"om", "Oromo", "Afaan Oromoo"},
            {"ps", "Pashto", "پښتو"},
            {"fa", "Persian", "فارسی"},
            {"pl", "Polish", "Polski"},
            {"pt", "Portuguese", "Português"},
            {"pa", "Punjabi", "ਪੰਜਾਬੀ"},
            {"qu", "Quechua", "Runa Simi"},
            {"ro", "Romanian", "Română"},
            {"ru", "Russian", "Русский"},
            {"sm", "Samoan", "Gagana Samoa"},
            {"sa", "Sanskrit", "संस्कृतम्"},
            {"gd", "Scots Gaelic", "Gàidhlig"},
            {"nso", "Sepedi", "Sepedi"},
            {"sr", "Serbian", "Српски"},
            {"st", "Sesotho", "Sesotho"},
            {"sn", "Shona", "chiShona"},
            {"sd", "Sindhi", "سنڌي"},
            {"si", "Sinhala", "සිංහල"},
            {"sk", "Slovak", "Slovenčina"},
            {"sl", "Slovenian", "Slovenščina"},
            {"so", "Somali", "Soomaali"},
            {"es", "Spanish", "Español"},
            {"su", "Sundanese", "Basa Sunda"},
            {"sw", "Swahili", "Kiswahili"},
            {"sv", "Swedish", "Svenska"},
            {"tg", "Tajik", "Тоҷикӣ"},
            {"ta", "Tamil", "தமிழ்"},
            {"tt", "Tatar", "Татарча"},
            {"te", "Telugu", "తెలుగు"},
            {"th", "Thai", "ไทย"},
            {"ti", "Tigrinya", "ትግርኛ"},
            {"ts", "Tsonga", "Xitsonga"},
            {"tr", "Turkish", "Türkçe"},
            {"tk", "Turkmen", "Türkmençe"},
            {"ak", "Twi", "Twi"},
            {"uk", "Ukrainian", "Українська"},
            {"ur", "Urdu", "اردو"},
            {"ug", "Uyghur", "ئۇيغۇرچە"},
            {"uz", "Uzbek", "Oʻzbekcha"},
            {"vi", "Vietnamese", "Tiếng Việt"},
            {"cy", "Welsh", "Cymraeg"},
            {"xh", "Xhosa", "isiXhosa"},
            {"yi", "Yiddish", "ייִדיש"},
            {"yo", "Yoruba", "Yorùbá"},
            {"zu", "Zulu", "isiZulu"},
        };
        List<Language> others = new ArrayList<>();
        for (String[] row : table) others.add(new Language(row[0], row[1], row[2]));
        others.sort(Comparator.comparing(Language::englishName, String.CASE_INSENSITIVE_ORDER));
        List<Language> list = new ArrayList<>();
        list.add(new Language(ENGLISH_CODE, "English", "English"));
        list.addAll(others);
        return Collections.unmodifiableList(list);
    }
}
