package com.dnd.ui.print;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.ClassFeature;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.Progression;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.item.Item;
import com.dnd.model.item.armors.Armor;
import com.dnd.model.magic.CastingResource;
import com.dnd.model.magic.EnergyCharges;
import com.dnd.model.magic.Spell;
import com.dnd.model.magic.SpellSlot;
import com.dnd.model.magic.SpellSlots;
import com.dnd.model.magic.SpellcastingType;
import com.dnd.model.rules.FeatureRules;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Renders a printable, classic 5e-style character sheet for one {@link PlayerCharacter}.
 *
 * <p>Page one mirrors the official front sheet (abilities, saves, skills, combat block,
 * resources, attacks); the following pages flow features, spells, equipment, backstory and
 * lined notes, paginating as needed. Values that change at the table - current and temporary
 * hit points, used spell slots, feature uses, death saves, coins - are left as empty boxes and
 * circles to fill in by pencil.</p>
 *
 * <p>Pure: no JavaFX, so it can run headless (tests, CLI).</p>
 */
public final class CharacterSheetPdf {

    private static final String FONT_DIR = "/com/dnd/ui/fonts/";

    private static final float PW = PDRectangle.A4.getWidth();
    private static final float PH = PDRectangle.A4.getHeight();
    private static final float M = 28f;
    private static final float CW = PW - 2 * M;
    private static final float BOTTOM = PH - 34f;

    private static final float[] INK = {0.10f, 0.10f, 0.12f};
    private static final float[] GREY = {0.42f, 0.42f, 0.45f};
    private static final float[] RULE = {0.70f, 0.70f, 0.72f};
    private static final float[] BAND = {0.30f, 0.09f, 0.08f};
    private static final float[] SHADE = {0.94f, 0.93f, 0.91f};
    private static final float[] WHITE = {1f, 1f, 1f};

    private static final String[] AB_KEYS = {"strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"};
    private static final String[] AB_ABBR = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};

    private static final String[][] SKILLS = {
            {"Acrobatics", "dex"}, {"Animal Handling", "wis"}, {"Arcana", "int"}, {"Athletics", "str"},
            {"Deception", "cha"}, {"History", "int"}, {"Insight", "wis"}, {"Intimidation", "cha"},
            {"Investigation", "int"}, {"Medicine", "wis"}, {"Nature", "int"}, {"Perception", "wis"},
            {"Performance", "cha"}, {"Persuasion", "cha"}, {"Religion", "int"}, {"Sleight of Hand", "dex"},
            {"Stealth", "dex"}, {"Survival", "wis"}
    };

    private CharacterSheetPdf() {
    }

    /** Writes {@code pc}'s sheet to {@code out}, looking classes/races/items/spells up in {@code repos}. */
    public static void write(PlayerCharacter pc, CampaignRepositories repos, Path out) throws IOException {
        write(pc, repos, null, out);
    }

    /** As {@link #write(PlayerCharacter, CampaignRepositories, Path)}, printing {@code campaignName} in the header. */
    public static void write(PlayerCharacter pc, CampaignRepositories repos, String campaignName, Path out) throws IOException {
        if (pc == null) throw new IllegalArgumentException("No character to print");
        CharacterClass cls = repos == null ? null : safe(() -> repos.classes().getById(pc.getClassId()));
        CharacterRace race = repos == null ? null : safe(() -> repos.races().getById(pc.getRaceId()));
        Map<String, Item> items = new HashMap<>();
        Map<String, Spell> spells = new HashMap<>();
        if (repos != null) {
            List<Item> itemList = safe(() -> repos.items().list());
            if (itemList != null) for (Item i : itemList) if (i != null && i.getId() != null) items.put(i.getId(), i);
            List<Spell> spellList = safe(() -> repos.spells().list());
            if (spellList != null) for (Spell s : spellList) if (s != null && s.getId() != null) spells.put(s.getId(), s);
        }
        Path portrait = repos == null ? null : portraitFile(repos.campaignRoot(), pc.getImagePath());
        write(pc, cls, race, items::get, spells::get, campaignName, portrait, out);
    }

    /** The character's picture on disk, or null when none is set or the file is gone. */
    static Path portraitFile(Path campaignRoot, String imagePath) {
        if (campaignRoot == null || blank(imagePath)) return null;
        try {
            Path p = campaignRoot.resolve(imagePath);
            return Files.isRegularFile(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Lookup-based variant for callers that already hold the catalog data; any argument but pc/out may be null. */
    public static void write(PlayerCharacter pc, CharacterClass cls, CharacterRace race,
                             Function<String, Item> items, Function<String, Spell> spells,
                             String campaignName, Path out) throws IOException {
        write(pc, cls, race, items, spells, campaignName, null, out);
    }

    /** As above, with the character's picture ({@code portrait} may be null: the frame is left empty). */
    public static void write(PlayerCharacter pc, CharacterClass cls, CharacterRace race,
                             Function<String, Item> items, Function<String, Spell> spells,
                             String campaignName, Path portrait, Path out) throws IOException {
        if (pc == null) throw new IllegalArgumentException("No character to print");
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (PDDocument doc = new PDDocument()) {
            Sheet sheet = new Sheet(doc, pc, cls, race,
                    items == null ? id -> null : items, spells == null ? id -> null : spells, campaignName, portrait);
            sheet.render();
            doc.getDocumentInformation().setTitle(nz(pc.getName()) + " - Character Sheet");
            doc.getDocumentInformation().setCreator("Almerion");
            doc.save(out.toFile());
        }
    }

    private interface Attempt<T> {
        T get() throws Exception;
    }

    private static <T> T safe(Attempt<T> s) {
        try {
            return s.get();
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String signed(int n) {
        return n >= 0 ? "+" + n : String.valueOf(n);
    }

    private static String cap(String s) {
        if (blank(s)) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String num(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.format(Locale.ROOT, "%.1f", d);
    }

    /** Each slot level's maximum, e.g. "1st ×4 · 2nd ×2" or "Steam-pressure ×4 · Thermal ×2". */
    static String describeSlots(List<SpellSlot> slots, CastingResource resource) {
        List<String> parts = new ArrayList<>();
        for (SpellSlot s : slots) {
            String name = resource == CastingResource.ENERGY_CHARGES ? EnergyCharges.name(s.getLevel()) : SpellSlots.ordinal(s.getLevel());
            parts.add(name + " ×" + s.getMax());
        }
        return String.join(" · ", parts);
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private static final class Sheet {
        private final PDDocument doc;
        private final PlayerCharacter pc;
        private final CharacterClass cls;
        private final CharacterRace race;
        private final Function<String, Item> items;
        private final Function<String, Spell> spells;
        private final String campaign;
        private final Path portrait;

        private PDFont serif;
        private PDFont bold;
        private PDFont mono;
        private final Map<PDFont, Map<Integer, Boolean>> glyphCache = new IdentityHashMap<>();

        private PDPageContentStream cs;
        private float y;

        private final CoreStats stats;
        private final int level;
        private final int prof;
        private final CastingResource resource;
        private final FeatureRules.Kit kit;
        private final String castAbility;
        private final List<SpellSlot> slots;
        private final List<ClassFeature> features;

        Sheet(PDDocument doc, PlayerCharacter pc, CharacterClass cls, CharacterRace race,
              Function<String, Item> items, Function<String, Spell> spells, String campaign, Path portrait) {
            this.portrait = portrait;
            this.doc = doc;
            this.pc = pc;
            this.cls = cls;
            this.race = race;
            this.items = id -> id == null ? null : safe(() -> items.apply(id));
            this.spells = id -> id == null ? null : safe(() -> spells.apply(id));
            this.campaign = campaign;
            this.stats = pc.getStats() != null ? pc.getStats() : new CoreStats();
            this.level = Math.max(1, pc.getLevel());
            this.prof = Progression.proficiencyBonus(level);
            CastingResource r = safe(() -> Progression.resourceOf(pc, cls));
            this.resource = r == null ? CastingResource.NONE : r;
            FeatureRules.Kit k = safe(() -> FeatureRules.kit(pc, cls, race));
            this.kit = k == null ? new FeatureRules.Kit() : k;
            this.castAbility = castingAbility();
            this.slots = slotList();
            List<ClassFeature> f = cls == null ? null : safe(() -> cls.featuresUpTo(level, pc.getSubclass()));
            this.features = f == null ? List.of() : f;
        }

        void render() throws IOException {
            serif = loadFont("DejaVuSerif.ttf");
            bold = loadFont("DejaVuSerif-Bold.ttf");
            frontPage();
            flowPages();
            if (cs != null) cs.close();
            cs = null;
            footers();
        }

        private PDFont loadFont(String file) throws IOException {
            try (InputStream in = CharacterSheetPdf.class.getResourceAsStream(FONT_DIR + file)) {
                if (in == null) throw new IOException("Bundled font missing: " + file);
                return PDType0Font.load(doc, in, true);
            }
        }

        private PDFont monoFont() {
            if (mono == null) {
                try {
                    mono = loadFont("DejaVuSansMono.ttf");
                } catch (IOException e) {
                    mono = serif;
                }
            }
            return mono;
        }

        // ── Derived values ──────────────────────────────────────────────────

        private int score(int i) {
            return switch (i) {
                case 0 -> stats.getStrength();
                case 1 -> stats.getDexterity();
                case 2 -> stats.getConstitution();
                case 3 -> stats.getIntelligence();
                case 4 -> stats.getWisdom();
                default -> stats.getCharisma();
            };
        }

        private int mod(String abbr) {
            return FeatureRules.mod(stats, abbr.toLowerCase(Locale.ROOT));
        }

        private boolean saveProficient(int i) {
            if (cls == null || cls.getSavingThrowBonuses() == null) return false;
            for (Map.Entry<String, Integer> e : cls.getSavingThrowBonuses().entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
                if ((k.equals(AB_KEYS[i]) || k.equals(AB_ABBR[i].toLowerCase(Locale.ROOT)))
                        && e.getValue() != null && e.getValue() > 0) return true;
            }
            return false;
        }

        private String castingAbility() {
            String id = nz(cls != null && cls.getId() != null ? cls.getId() : pc.getClassId()).toLowerCase(Locale.ROOT);
            switch (id) {
                case "bard", "paladin", "sorcerer", "warlock" -> { return "cha"; }
                case "cleric", "druid", "ranger" -> { return "wis"; }
                case "wizard", "artificer" -> { return "int"; }
                default -> { }
            }
            boolean caster = (cls != null && cls.getSpellcasting() != SpellcastingType.NONE)
                    || pc.getMaxMana() > 0 || (pc.getSpells() != null && !pc.getSpells().isEmpty());
            if (!caster) return null;
            if (cls != null && cls.getPrimaryAbilities() != null) {
                for (String a : cls.getPrimaryAbilities()) {
                    String k = nz(a).toLowerCase(Locale.ROOT);
                    if (k.startsWith("int")) return "int";
                    if (k.startsWith("wis")) return "wis";
                    if (k.startsWith("cha")) return "cha";
                }
            }
            return "int";
        }

        private List<SpellSlot> slotList() {
            List<SpellSlot> out = new ArrayList<>();
            if (!resource.usesSlots()) return out;
            if (pc.getSpellSlots() != null) {
                for (SpellSlot s : pc.getSpellSlots()) if (s != null && s.getMax() > 0) out.add(s);
            }
            if (out.isEmpty() && cls != null) {
                int[] max = SpellSlots.maxSlots(cls.getSpellcasting(), level);
                for (int i = 0; i < max.length; i++) if (max[i] > 0) out.add(new SpellSlot(i + 1, max[i], max[i]));
            }
            out.sort(Comparator.comparingInt(SpellSlot::getLevel));
            return out;
        }

        private boolean isCaster() {
            return castAbility != null || !slots.isEmpty() || pc.getMaxMana() > 0;
        }

        private int armorClass() {
            FeatureRules.ArmorClass ac = safe(() -> FeatureRules.armorClass(pc, kit, items, 0));
            return ac == null ? 10 + mod("dex") : ac.total();
        }

        private int speed() {
            int base = race != null && race.getSpeed() > 0 ? race.getSpeed() : 30;
            FeatureRules.Worn worn = safe(() -> FeatureRules.worn(pc, items));
            if (worn == null) return base;
            return base + kit.speedBonus(worn.wearingArmor(), worn.heavy());
        }

        private String className() {
            if (cls != null && !blank(cls.getName())) return cls.getName();
            return cap(nz(pc.getClassId()));
        }

        private String raceName() {
            if (race != null && !blank(race.getName())) return race.getName();
            return cap(nz(pc.getRaceId()));
        }

        private String spellName(String id) {
            Spell s = spells.apply(id);
            return s != null && !blank(s.getName()) ? s.getName() : nz(id);
        }

        // ── Low-level drawing (top-down coordinates) ────────────────────────

        private void color(float[] c, boolean stroke) throws IOException {
            if (stroke) cs.setStrokingColor(c[0], c[1], c[2]);
            else cs.setNonStrokingColor(c[0], c[1], c[2]);
        }

        private record Run(PDFont font, String text) {
        }

        private boolean supports(PDFont font, int cp) {
            Map<Integer, Boolean> cache = glyphCache.computeIfAbsent(font, f -> new HashMap<>());
            Boolean known = cache.get(cp);
            if (known != null) return known;
            boolean ok;
            try {
                font.encode(new String(Character.toChars(cp)));
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            cache.put(cp, ok);
            return ok;
        }

        /** Splits {@code s} into runs drawable by {@code primary} or a fallback; unsupported characters become '?'. */
        private List<Run> runs(String s, PDFont primary) {
            List<Run> out = new ArrayList<>();
            if (s == null || s.isEmpty()) return out;
            PDFont current = null;
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < s.length()) {
                int cp = s.codePointAt(i);
                i += Character.charCount(cp);
                if (cp < 32 || cp == 0x7F) cp = ' ';
                PDFont f;
                if (supports(primary, cp)) f = primary;
                else if (primary != serif && supports(serif, cp)) f = serif;
                else if (supports(monoFont(), cp)) f = monoFont();
                else {
                    f = primary;
                    cp = '?';
                }
                if (f != current && sb.length() > 0) {
                    out.add(new Run(current, sb.toString()));
                    sb.setLength(0);
                }
                current = f;
                sb.appendCodePoint(cp);
            }
            if (sb.length() > 0) out.add(new Run(current, sb.toString()));
            return out;
        }

        private float width(String s, PDFont f, float size) {
            float w = 0;
            for (Run r : runs(s, f)) {
                try {
                    w += r.font().getStringWidth(r.text()) / 1000f * size;
                } catch (Exception e) {
                    w += r.text().length() * size * 0.55f;
                }
            }
            return w;
        }

        private void text(String s, float x, float base, PDFont f, float size, float[] c) throws IOException {
            if (s == null || s.isEmpty()) return;
            color(c, false);
            float cx = x;
            for (Run r : runs(s, f)) {
                cs.beginText();
                cs.setFont(r.font(), size);
                cs.newLineAtOffset(cx, PH - base);
                cs.showText(r.text());
                cs.endText();
                cx += r.font().getStringWidth(r.text()) / 1000f * size;
            }
        }

        private void textCenter(String s, float cx, float base, PDFont f, float size, float[] c) throws IOException {
            text(s, cx - width(s, f, size) / 2f, base, f, size, c);
        }

        private void textRight(String s, float right, float base, PDFont f, float size, float[] c) throws IOException {
            text(s, right - width(s, f, size), base, f, size, c);
        }

        /** Small-caps style label: upper case, small, bold, grey. */
        private void label(String s, float x, float base, float size) throws IOException {
            text(s.toUpperCase(Locale.ROOT), x, base, bold, size, GREY);
        }

        private void labelCenter(String s, float cx, float base, float size) throws IOException {
            textCenter(s.toUpperCase(Locale.ROOT), cx, base, bold, size, GREY);
        }

        private String fit(String s, PDFont f, float size, float maxW) {
            if (s == null) return "";
            if (width(s, f, size) <= maxW) return s;
            String ell = "…";
            int end = s.length();
            while (end > 0 && width(s.substring(0, end) + ell, f, size) > maxW) end--;
            return s.substring(0, end).stripTrailing() + ell;
        }

        private float fitSize(String s, PDFont f, float size, float maxW, float min) {
            float sz = size;
            while (sz > min && width(s, f, sz) > maxW) sz -= 0.5f;
            return sz;
        }

        private List<String> wrap(String s, PDFont f, float size, float maxW) {
            List<String> lines = new ArrayList<>();
            if (s == null) return lines;
            for (String para : s.replace("\r", "").split("\n", -1)) {
                if (para.isBlank()) {
                    lines.add("");
                    continue;
                }
                StringBuilder line = new StringBuilder();
                for (String word : para.trim().split("\\s+")) {
                    String candidate = line.length() == 0 ? word : line + " " + word;
                    if (width(candidate, f, size) <= maxW) {
                        line.setLength(0);
                        line.append(candidate);
                        continue;
                    }
                    if (line.length() > 0) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    String w = word;
                    while (width(w, f, size) > maxW && w.length() > 1) {
                        int cut = w.length() - 1;
                        while (cut > 1 && width(w.substring(0, cut), f, size) > maxW) cut--;
                        lines.add(w.substring(0, cut));
                        w = w.substring(cut);
                    }
                    line.append(w);
                }
                if (line.length() > 0) lines.add(line.toString());
            }
            return lines;
        }

        private void rect(float x, float top, float w, float h, float lw, float[] c) throws IOException {
            color(c, true);
            cs.setLineWidth(lw);
            cs.addRect(x, PH - top - h, w, h);
            cs.stroke();
        }

        private void fill(float x, float top, float w, float h, float[] c) throws IOException {
            color(c, false);
            cs.addRect(x, PH - top - h, w, h);
            cs.fill();
        }

        private void line(float x1, float t1, float x2, float t2, float lw, float[] c) throws IOException {
            color(c, true);
            cs.setLineWidth(lw);
            cs.moveTo(x1, PH - t1);
            cs.lineTo(x2, PH - t2);
            cs.stroke();
        }

        private void circle(float cx, float ct, float r, boolean filled) throws IOException {
            float y0 = PH - ct;
            float k = 0.5523f * r;
            cs.moveTo(cx + r, y0);
            cs.curveTo(cx + r, y0 + k, cx + k, y0 + r, cx, y0 + r);
            cs.curveTo(cx - k, y0 + r, cx - r, y0 + k, cx - r, y0);
            cs.curveTo(cx - r, y0 - k, cx - k, y0 - r, cx, y0 - r);
            cs.curveTo(cx + k, y0 - r, cx + r, y0 - k, cx + r, y0);
            cs.closePath();
            color(INK, true);
            cs.setLineWidth(0.6f);
            if (filled) {
                color(INK, false);
                cs.fillAndStroke();
            } else {
                cs.stroke();
            }
        }

        /** Boxed panel with a label centred on its bottom edge, like the official sheet. */
        private void panel(float x, float top, float w, float h, String lbl) throws IOException {
            rect(x, top, w, h, 0.9f, INK);
            if (lbl != null) labelCenter(lbl, x + w / 2f, top + h - 3.5f, 6f);
        }

        /** Boxed panel with a title band at the top. */
        private void titled(float x, float top, float w, float h, String title) throws IOException {
            fill(x, top, w, 11, BAND);
            textCenter(title.toUpperCase(Locale.ROOT), x + w / 2f, top + 8f, bold, 6.5f, WHITE);
            rect(x, top, w, h, 0.9f, INK);
        }

        /** {@code n} empty tick circles right-aligned at {@code right}, or a write-in box when there are too many. */
        private void ticks(int n, float right, float centre, int maxCircles) throws IOException {
            if (n <= 0) return;
            if (n > maxCircles) {
                String t = "/ " + n;
                float tw = width(t, bold, 8);
                textRight(t, right, centre + 3, bold, 8, INK);
                rect(right - tw - 30, centre - 6, 26, 12, 0.6f, INK);
                return;
            }
            float step = 8.5f;
            for (int i = 0; i < n; i++) circle(right - 3.2f - i * step, centre, 3f, false);
        }

        // ── Page 1 ──────────────────────────────────────────────────────────

        private void newPage() throws IOException {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
        }

        private void frontPage() throws IOException {
            newPage();
            fill(M, M, CW, 16, BAND);
            text("ALMERION", M + 6, M + 11.5f, bold, 8.5f, WHITE);
            textRight("CHARACTER SHEET", M + CW - 6, M + 11.5f, bold, 8.5f, WHITE);

            float top = 50;
            panel(M, top, 200, 54, "Character name");
            String name = nz(pc.getName());
            float nsz = fitSize(name, bold, 17, 188, 8);
            text(fit(name, bold, nsz, 188), M + 6, top + 30, bold, nsz, INK);
            line(M + 6, top + 38, M + 194, top + 38, 0.4f, RULE);

            float gx = M + 208;
            float gw = CW - 208;
            rect(gx, top, gw, 54, 0.9f, INK);
            float cellW = gw / 3f;
            String player = nz(pc.getPlayerName());
            if (player.equalsIgnoreCase("unassigned")) player = "";
            String xp = pc.getXp() + " / " + Progression.XP_PER_LEVEL;
            String[][] cells = {
                    {className() + " " + level, "Class & level"}, {nz(pc.getSubclass()), "Subclass"}, {raceName(), "Race"},
                    {player, "Player name"}, {xp, "Experience points"}, {nz(campaign), "Campaign"}
            };
            for (int i = 0; i < 6; i++) {
                float cx = gx + 6 + (i % 3) * cellW;
                float ct = top + (i < 3 ? 4 : 30);
                float vw = cellW - 12;
                float vs = fitSize(cells[i][0], serif, 9.5f, vw, 6);
                text(fit(cells[i][0], serif, vs, vw), cx, ct + 11, serif, vs, INK);
                line(cx, ct + 14, cx + vw, ct + 14, 0.4f, RULE);
                label(cells[i][1], cx, ct + 20.5f, 5.5f);
            }

            float bodyTop = 112;
            abilityColumn(bodyTop);
            savesAndSkills(bodyTop);
            proficienciesBox(552, M, 202, BOTTOM - 552);
            combatColumn(bodyTop);
            resourceColumn(bodyTop);
        }

        private void abilityColumn(float top) throws IOException {
            float x = M;
            float w = 62;
            for (int i = 0; i < 6; i++) {
                float t = top + i * 72;
                rect(x, t, w, 66, 0.9f, INK);
                labelCenter(AB_KEYS[i], x + w / 2f, t + 10, 5.6f);
                int sc = score(i);
                textCenter(signed(Progression.modifier(sc)), x + w / 2f, t + 37, bold, 20, INK);
                float bw = 28;
                rect(x + (w - bw) / 2f, t + 45, bw, 15, 0.7f, INK);
                textCenter(String.valueOf(sc), x + w / 2f, t + 56, serif, 9, INK);
            }
        }

        private void savesAndSkills(float top) throws IOException {
            float x = M + 68;
            float w = 134;

            rect(x, top, w, 24, 0.9f, INK);
            rect(x + 4, top + 4, 16, 16, 0.8f, INK);
            label("Inspiration", x + 26, top + 15, 6.5f);

            float t = top + 30;
            rect(x, t, w, 24, 0.9f, INK);
            rect(x + 4, t + 4, 22, 16, 0.8f, INK);
            textCenter(signed(prof), x + 15, t + 16, bold, 9, INK);
            label("Proficiency bonus", x + 32, t + 15, 6.5f);

            t = top + 60;
            panel(x, t, w, 90, "Saving throws");
            for (int i = 0; i < 6; i++) {
                float rt = t + 13 + i * 11.5f;
                boolean p = saveProficient(i);
                circle(x + 9, rt - 3, 3f, p);
                int v = Progression.modifier(score(i)) + (p ? prof : 0);
                textRight(signed(v), x + 34, rt, bold, 8, INK);
                line(x + 16, rt + 1.5f, x + 35, rt + 1.5f, 0.3f, RULE);
                text(cap(AB_KEYS[i]), x + 40, rt, serif, 8, INK);
            }

            t = top + 156;
            float rowH = 12.6f;
            float skH = 16 + SKILLS.length * rowH;
            panel(x, t, w, skH, "Skills");
            for (int i = 0; i < SKILLS.length; i++) {
                float rt = t + 11 + i * rowH;
                circle(x + 9, rt - 3, 3f, false);
                textRight(signed(skillValue(i)), x + 34, rt, bold, 7.5f, INK);
                line(x + 16, rt + 1.5f, x + 35, rt + 1.5f, 0.3f, RULE);
                text(SKILLS[i][0], x + 40, rt, serif, 7.5f, INK);
                textRight("(" + cap(SKILLS[i][1]) + ")", x + w - 4, rt, serif, 6, GREY);
            }

            t = top + 156 + skH + 6;
            rect(x, t, w, 24, 0.9f, INK);
            rect(x + 4, t + 4, 22, 16, 0.8f, INK);
            textCenter(String.valueOf(10 + mod("wis")), x + 15, t + 16, bold, 9, INK);
            label("Passive perception (WIS)", x + 30, t + 15, 5.8f);
        }

        /** The character's stored bonus for {@code SKILLS[i]} (same order as {@link com.dnd.model.character.stats.Skills#ALL}). */
        private int skillValue(int i) {
            return pc.getSkills().get(com.dnd.model.character.stats.Skills.ALL.get(i).key());
        }

        private void proficienciesBox(float top, float x, float w, float h) throws IOException {
            panel(x, top, w, h, "Other proficiencies & languages");
            float lt = top + 12;
            List<String> printed = new ArrayList<>();
            if (!kit.getResistances().isEmpty()) printed.add("Resistant: " + String.join(", ", kit.getResistances()));
            printed.add("Skill values above are the character's skill bonuses (proficiency bonus " + signed(prof) + ").");
            for (String p : printed) {
                for (String l : wrap(p, serif, 7, w - 10)) {
                    if (lt > top + h - 14) break;
                    text(l, x + 5, lt, serif, 7, GREY);
                    lt += 9;
                }
            }
            lt += 4;
            for (float lr = lt; lr < top + h - 10; lr += 13) line(x + 5, lr, x + w - 5, lr, 0.35f, RULE);
        }

        private void combatColumn(float top) throws IOException {
            float x = M + 208;
            float w = 160;
            float bw = (w - 8) / 3f;
            String[][] trio = {
                    {String.valueOf(armorClass()), "Armor class"},
                    {signed(mod("dex")), "Initiative"},
                    {speed() + " ft", "Speed"}
            };
            for (int i = 0; i < 3; i++) {
                float bx = x + i * (bw + 4);
                panel(bx, top, bw, 46, trio[i][1]);
                float vs = fitSize(trio[i][0], bold, 16, bw - 6, 8);
                textCenter(trio[i][0], bx + bw / 2f, top + 28, bold, vs, INK);
            }

            float t = top + 52;
            panel(x, t, w, 76, "Current hit points");
            label("Hit point maximum", x + 6, t + 11, 6);
            textRight(String.valueOf(pc.getMaxHitPoints()), x + w - 6, t + 12, bold, 10, INK);
            line(x + 6, t + 15, x + w - 6, t + 15, 0.4f, RULE);
            if (pc.getMaxHitPoints() > 0 && pc.getCurrentHitPoints() < pc.getMaxHitPoints()) {
                textRight("at print: " + pc.getCurrentHitPoints(), x + w - 6, t + 64, serif, 5.5f, RULE);
            }

            t += 82;
            panel(x, t, w, 40, "Temporary hit points");

            t += 46;
            float hw = (w - 6) / 2f;
            panel(x, t, hw, 52, "Hit dice");
            int sides = cls != null && cls.getHitDie() != null && cls.getHitDie().getSides() > 0 ? cls.getHitDie().getSides() : 8;
            label("Total", x + 5, t + 11, 5.8f);
            text(level + "d" + sides, x + 30, t + 11.5f, bold, 8.5f, INK);
            line(x + 5, t + 15, x + hw - 5, t + 15, 0.4f, RULE);

            float dx = x + hw + 6;
            panel(dx, t, hw, 52, "Death saves");
            label("Successes", dx + 5, t + 17, 5.5f);
            label("Failures", dx + 5, t + 32, 5.5f);
            for (int i = 0; i < 3; i++) {
                circle(dx + hw - 9 - i * 9, t + 15, 3f, false);
                circle(dx + hw - 9 - i * 9, t + 30, 3f, false);
            }

            t += 58;
            attacksBox(x, t, w, 200);
            t += 206;
            float h = BOTTOM - t;
            panel(x, t, w, h, "Conditions & effects");
            for (float lr = t + 14; lr < t + h - 10; lr += 13) line(x + 5, lr, x + w - 5, lr, 0.35f, RULE);
        }

        private void attacksBox(float x, float top, float w, float h) throws IOException {
            titled(x, top, w, h, "Attacks & spellcasting");
            float t = top + 20;
            label("Name", x + 4, t, 5.5f);
            label("Atk", x + 86, t, 5.5f);
            label("Damage / type", x + 108, t, 5.5f);
            t += 4;
            line(x + 3, t, x + w - 3, t, 0.4f, INK);
            List<FeatureRules.Attack> attacks = safe(() -> FeatureRules.attacks(pc, kit, items));
            List<String[]> rows = new ArrayList<>();
            if (attacks != null) {
                for (FeatureRules.Attack a : attacks) rows.add(new String[]{nz(a.name()), signed(a.toHit()), a.damageLabel()});
            }
            if (castAbility != null) {
                rows.add(new String[]{"Spell attack", signed(prof + mod(castAbility)), "save DC " + (8 + prof + mod(castAbility))});
            }
            float rt = t + 11;
            for (String[] r : rows) {
                if (rt > top + h - 40) break;
                text(fit(r[0], serif, 7.5f, 80), x + 4, rt, serif, 7.5f, INK);
                text(r[1], x + 86, rt, bold, 7.5f, INK);
                text(fit(r[2], serif, 7, w - 112), x + 108, rt, serif, 7, INK);
                line(x + 3, rt + 3, x + w - 3, rt + 3, 0.3f, RULE);
                rt += 12;
            }
            List<String> notes = new ArrayList<>();
            if (kit.getExtraAttacks() > 0) notes.add("Extra Attack: " + (kit.getExtraAttacks() + 1) + " attacks per Attack action");
            if (kit.getSneakAttackDice() > 0) notes.add("Sneak Attack: " + kit.getSneakAttackDice() + "d6 once per turn");
            if (!blank(kit.getMartialArtsDie())) notes.add("Martial Arts die: " + kit.getMartialArtsDie());
            for (String n : notes) {
                if (rt > top + h - 20) break;
                text(fit(n, serif, 6.5f, w - 8), x + 4, rt, serif, 6.5f, GREY);
                rt += 9;
            }
            for (float lr = rt + 2; lr < top + h - 4; lr += 12) line(x + 3, lr, x + w - 3, lr, 0.3f, RULE);
        }

        private void resourceColumn(float top) throws IOException {
            float x = M + 374;
            float w = CW - 374;
            float t = top;

            if (castAbility != null) {
                float cw = w / 3f;
                String[][] trio = {
                        {castAbility.toUpperCase(Locale.ROOT), "Casting ability"},
                        {String.valueOf(8 + prof + mod(castAbility)), "Spell save DC"},
                        {signed(prof + mod(castAbility)), "Spell attack"}
                };
                for (int i = 0; i < 3; i++) {
                    panel(x + i * cw, t, cw, 34, null);
                    textCenter(trio[i][0], x + i * cw + cw / 2f, t + 17, bold, 12, INK);
                    labelCenter(trio[i][1], x + i * cw + cw / 2f, t + 29, 4.8f);
                }
                t += 40;
            }

            List<FeatureRules.Action> limited = new ArrayList<>();
            for (FeatureRules.Action a : kit.getActions()) if (a.isLimited()) limited.add(a);
            boolean hasMana = pc.getMaxMana() > 0;
            boolean hpCasting = resource == CastingResource.HIT_POINTS;
            if (!slots.isEmpty() || hasMana || hpCasting || !limited.isEmpty()) {
                float rowH = 15;
                int rows = slots.size() + (hasMana ? 1 : 0) + (hpCasting ? 1 : 0) + limited.size();
                int heads = (slots.isEmpty() ? 0 : 1) + (limited.isEmpty() ? 0 : 1);
                float h = Math.min(BOTTOM - t - 80, 16 + rows * rowH + heads * 11 + 4);
                titled(x, t, w, h, "Resources (tick when used)");
                float rt = t + 22;
                float limit = t + h - 4;
                if (!slots.isEmpty()) {
                    label(resource == CastingResource.ENERGY_CHARGES ? "Energy charges" : "Spell slots", x + 5, rt, 5.8f);
                    rt += 11;
                    for (SpellSlot s : slots) {
                        if (rt > limit) break;
                        String lbl = resource == CastingResource.ENERGY_CHARGES
                                ? SpellSlots.rowLabel(s.getLevel(), resource)
                                : SpellSlots.ordinal(s.getLevel()) + " level";
                        text(fit(lbl, serif, 7f, w - 16 - Math.min(s.getMax(), 7) * 9f), x + 5, rt, serif, 7f, INK);
                        ticks(s.getMax(), x + w - 5, rt - 2.5f, 7);
                        rt += rowH;
                    }
                }
                if (hasMana && rt <= limit) {
                    text("Mana", x + 5, rt, bold, 7.5f, INK);
                    text("max " + pc.getMaxMana(), x + 32, rt, serif, 7.5f, INK);
                    label("current", x + w - 62, rt, 5.5f);
                    rect(x + w - 33, rt - 9, 28, 12, 0.7f, INK);
                    rt += rowH;
                }
                if (hpCasting && rt <= limit) {
                    text("Spells are paid with hit points", x + 5, rt, serif, 7, GREY);
                    rt += rowH;
                }
                if (!limited.isEmpty() && rt <= limit) {
                    label("Feature uses", x + 5, rt, 5.8f);
                    rt += 11;
                    for (FeatureRules.Action a : limited) {
                        if (rt > limit) break;
                        String rech = switch (a.getRecharge()) {
                            case SHORT_REST -> "SR";
                            case LONG_REST -> "LR";
                            default -> "";
                        };
                        float nameW = w - 82;
                        text(fit(nz(a.getName()), serif, 7.5f, nameW), x + 5, rt, serif, 7.5f, INK);
                        text(rech, x + 5 + nameW + 2, rt, serif, 5.5f, GREY);
                        ticks(a.getMaxUses(), x + w - 5, rt - 2.5f, 6);
                        rt += rowH;
                    }
                }
                t += h + 6;
            }

            float h = BOTTOM - t;
            titled(x, t, w, h, "Features & traits");
            float rt = t + 21;
            Set<String> seen = new LinkedHashSet<>();
            List<String[]> lines = new ArrayList<>();
            for (ClassFeature f : features) {
                String n = nz(f.getName());
                if (n.isBlank() || !seen.add(n.toLowerCase(Locale.ROOT))) continue;
                lines.add(new String[]{"Lv " + f.getLevel(), n});
            }
            for (FeatureRules.Passive p : kit.getPassives()) {
                if (p.name() == null) continue;
                if (seen.add(p.name().toLowerCase(Locale.ROOT))) lines.add(new String[]{"Trait", p.name()});
            }
            boolean truncated = false;
            for (String[] l : lines) {
                if (rt > t + h - 26) {
                    truncated = true;
                    break;
                }
                text(l[0], x + 5, rt, serif, 6, GREY);
                text(fit(l[1], serif, 7.5f, w - 32), x + 27, rt, serif, 7.5f, INK);
                rt += 10.5f;
            }
            if (!lines.isEmpty()) {
                text(truncated ? "… full list and descriptions on page 2" : "Descriptions on page 2",
                        x + 5, rt + 2, serif, 6, GREY);
                rt += 10;
            }
            for (float lr = rt + 6; lr < t + h - 6; lr += 12) line(x + 4, lr, x + w - 4, lr, 0.3f, RULE);
        }

        // ── Flowing pages ───────────────────────────────────────────────────

        private void flowPage() throws IOException {
            newPage();
            fill(M, M, CW, 16, BAND);
            text(fit(nz(pc.getName()), bold, 8.5f, CW / 2f), M + 6, M + 11.5f, bold, 8.5f, WHITE);
            textRight(className() + " " + level + " · " + raceName(), M + CW - 6, M + 11.5f, bold, 8, WHITE);
            y = M + 30;
        }

        private void ensure(float h) throws IOException {
            if (y + h > BOTTOM) flowPage();
        }

        private void section(String title) throws IOException {
            ensure(46);
            if (y > M + 32) y += 6;
            fill(M, y - 10, CW, 14, BAND);
            text(title.toUpperCase(Locale.ROOT), M + 6, y, bold, 8, WHITE);
            y += 16;
        }

        private void subhead(String s) throws IOException {
            ensure(30);
            y += 3;
            text(s.toUpperCase(Locale.ROOT), M, y, bold, 7.5f, BAND);
            line(M, y + 3, M + CW, y + 3, 0.6f, BAND);
            y += 13;
        }

        private void para(String s, PDFont f, float size, float indent, float[] c) throws IOException {
            float lh = size * 1.32f;
            for (String l : wrap(s, f, size, CW - indent)) {
                ensure(lh);
                text(l, M + indent, y, f, size, c);
                y += lh;
            }
        }

        private void flowPages() throws IOException {
            flowPage();
            portraitSection();
            featuresSection();
            spellsSection();
            equipmentSection();
            storySection();
            notesSection();
        }

        private void featuresSection() throws IOException {
            if (features.isEmpty() && race == null && kit.getPassives().isEmpty()) return;
            section("Features & traits");
            if (!features.isEmpty()) {
                subhead(className() + (blank(pc.getSubclass()) ? "" : " · " + pc.getSubclass()));
                Map<String, FeatureRules.Action> actions = new HashMap<>();
                for (FeatureRules.Action a : kit.getActions()) actions.put(nz(a.getName()).toLowerCase(Locale.ROOT), a);
                for (ClassFeature f : features) {
                    ensure(24);
                    String head = nz(f.getName()) + (blank(f.getSubclass()) ? "" : " (" + f.getSubclass() + ")");
                    text("Lv " + f.getLevel(), M, y, serif, 7, GREY);
                    text(fit(head, bold, 8.5f, CW - 130), M + 26, y, bold, 8.5f, INK);
                    FeatureRules.Action a = actions.get(nz(f.getName()).toLowerCase(Locale.ROOT));
                    if (a != null && a.isLimited()) {
                        textRight((a.isPool() ? "pool " : "") + a.getMaxUses() + " per " + a.getRecharge().label(),
                                M + CW, y, serif, 7, GREY);
                    }
                    y += 11;
                    if (!blank(f.getDescription())) para(f.getDescription(), serif, 8, 26, INK);
                    y += 3;
                }
            }
            if (race != null || !kit.getPassives().isEmpty()) {
                subhead("Race · " + raceName());
                if (race != null && !blank(race.getDescription())) {
                    para(race.getDescription(), serif, 8, 0, INK);
                    y += 2;
                }
                if (race != null && race.getAbilityBonuses() != null && !race.getAbilityBonuses().isEmpty()) {
                    List<String> b = new ArrayList<>();
                    for (Map.Entry<String, Integer> e : race.getAbilityBonuses().entrySet()) {
                        if (e.getValue() != null && e.getValue() != 0) b.add(cap(e.getKey()) + " " + signed(e.getValue()));
                    }
                    if (!b.isEmpty()) para("Ability bonuses: " + String.join(", ", b), serif, 7.5f, 0, GREY);
                }
                Set<String> classNames = new LinkedHashSet<>();
                for (ClassFeature f : features) classNames.add(nz(f.getName()).toLowerCase(Locale.ROOT));
                for (FeatureRules.Passive p : kit.getPassives()) {
                    if (p.name() == null || classNames.contains(p.name().toLowerCase(Locale.ROOT))) continue;
                    ensure(22);
                    text(p.name(), M, y, bold, 8.5f, INK);
                    y += 11;
                    if (!blank(p.description())) para(p.description(), serif, 8, 10, INK);
                    y += 2;
                }
                if (!kit.getResistances().isEmpty()) {
                    para("Damage resistances: " + String.join(", ", kit.getResistances()), serif, 8, 0, INK);
                }
            }
        }

        private void spellsSection() throws IOException {
            List<PlayerCharacter.PlayerSpell> known = pc.getSpells() == null ? List.of() : pc.getSpells();
            if (known.isEmpty() && !isCaster()) return;
            section("Spellcasting");
            List<String> summary = new ArrayList<>();
            if (castAbility != null) {
                summary.add("Casting ability " + castAbility.toUpperCase(Locale.ROOT) + " (" + signed(mod(castAbility)) + ")");
                summary.add("Spell save DC " + (8 + prof + mod(castAbility)));
                summary.add("Spell attack " + signed(prof + mod(castAbility)));
            }
            if (resource != CastingResource.NONE) summary.add("Pays with: " + resource);
            if (pc.getMaxMana() > 0) summary.add("Mana max " + pc.getMaxMana());
            if (!summary.isEmpty()) para(String.join("  ·  ", summary), serif, 8, 0, INK);
            if (resource == CastingResource.ENERGY_CHARGES) {
                para("Energy charges are refilled by burning fuel in the workshop furnace, not by resting.", serif, 7.5f, 0, GREY);
            }
            if (!slots.isEmpty()) {
                para((resource == CastingResource.ENERGY_CHARGES ? "Charges: " : "Slots: ")
                        + describeSlots(slots, resource) + "  (tick used ones on page 1)", serif, 7.5f, 0, GREY);
            }
            y += 2;

            TreeMap<Integer, List<PlayerCharacter.PlayerSpell>> byLevel = new TreeMap<>();
            for (PlayerCharacter.PlayerSpell ps : known) {
                if (ps == null || ps.getSpellId() == null) continue;
                Spell s = spells.apply(ps.getSpellId());
                int lv = s != null ? s.getLevel() : Math.max(0, ps.getRank());
                byLevel.computeIfAbsent(lv, k -> new ArrayList<>()).add(ps);
            }
            for (Map.Entry<Integer, List<PlayerCharacter.PlayerSpell>> e : byLevel.entrySet()) {
                int lv = e.getKey();
                String head = lv == 0 ? "Cantrips" : SpellSlots.ordinal(lv) + " level";
                if (lv > 0 && resource == CastingResource.ENERGY_CHARGES) head += " · " + EnergyCharges.name(lv) + " charge";
                subhead(head);
                List<PlayerCharacter.PlayerSpell> list = e.getValue();
                list.sort(Comparator.comparing(ps -> spellName(ps.getSpellId()), String.CASE_INSENSITIVE_ORDER));
                for (PlayerCharacter.PlayerSpell ps : list) spellEntry(ps);
            }
            ensure(52);
            y += 6;
            label("New spells learned", M, y, 6);
            for (int i = 0; i < 3; i++) {
                y += 14;
                line(M, y, M + CW, y, 0.35f, RULE);
            }
            y += 12;
        }

        private void spellEntry(PlayerCharacter.PlayerSpell ps) throws IOException {
            Spell s = spells.apply(ps.getSpellId());
            ensure(24);
            circle(M + 3.5f, y - 3, 3f, ps.isActive());
            String name = spellName(ps.getSpellId());
            text(name, M + 11, y, bold, 8.5f, INK);
            if (!ps.isActive()) text("(not prepared)", M + 15 + width(name, bold, 8.5f), y, serif, 7, GREY);
            y += 10.5f;
            if (s == null) {
                y += 2;
                return;
            }
            List<String> d = new ArrayList<>();
            if (s.getSchool() != null) d.add(cap(s.getSchool().getValue()));
            d.add(s.getRange() > 0 ? "Range " + s.getRange() + " ft" : "Range self/touch");
            if (s.getRadius() > 0) d.add("Radius " + s.getRadius() + " ft");
            if (s.getConcentration() != null) d.add("Concentration");
            if (s.getCastingMethod() != null) d.add(cap(s.getCastingMethod().getValue().replace('-', ' ')));
            String comp = safe(s::componentsText);
            if (!blank(comp)) d.add("Components " + comp);
            if (resource == CastingResource.MANA && s.getManaCost() > 0) d.add(s.getManaCost() + " mana");
            if (resource == CastingResource.HIT_POINTS && s.getManaCost() > 0) d.add(s.getManaCost() + " HP");
            if (s.getDamage() != null) {
                String dmg = safe(() -> s.getDamage().toString());
                if (!blank(dmg)) d.add(dmg);
            }
            if (s.getCooldownRounds() > 0) d.add("Cooldown " + s.getCooldownRounds() + " rounds");
            para(String.join(" · ", d), serif, 7, 11, GREY);
            if (!blank(s.getDescription())) para(s.getDescription(), serif, 7.8f, 11, INK);
            y += 3;
        }

        private void equipmentSection() throws IOException {
            section("Equipment & inventory");
            float[] colX = {M, M + 14, M + 214, M + 300, M + 336, M + 386};
            tableHeader(colX);
            double totalWeight = 0;
            int row = 0;
            List<PlayerCharacter.PlayerItem> carried = pc.getItems() == null ? List.of() : pc.getItems();
            for (PlayerCharacter.PlayerItem pi : carried) {
                if (pi == null) continue;
                Item it = items.apply(pi.getItemId());
                if (y + 12 > BOTTOM) {
                    flowPage();
                    tableHeader(colX);
                }
                if (row++ % 2 == 1) fill(M, y - 8.5f, CW, 12, SHADE);
                rect(colX[0] + 2, y - 7, 7, 7, 0.6f, INK);
                if (pi.isEquipped()) fill(colX[0] + 3.5f, y - 5.5f, 4, 4, INK);
                String name = it != null && !blank(it.getName()) ? it.getName() : nz(pi.getItemId());
                text(fit(name, serif, 7.8f, 196), colX[1], y, serif, 7.8f, INK);
                String type = it == null ? "" : !blank(it.getType()) ? it.getType() : it.getClass().getSimpleName();
                text(fit(cap(type), serif, 7, 82), colX[2], y, serif, 7, GREY);
                textRight(String.valueOf(pi.getQuantity()), colX[3] + 24, y, serif, 7.8f, INK);
                if (it != null && it.getWeight() > 0) {
                    double w = it.getWeight() * pi.getQuantity();
                    totalWeight += w;
                    textRight(num(w), colX[4] + 34, y, serif, 7.8f, INK);
                }
                List<String> notes = new ArrayList<>();
                if (it != null && it.getDamage() != null && !blank(it.getDamage().getDice())) {
                    notes.add(it.getDamage().getDice() + (blank(it.getDamage().getType()) ? "" : " " + it.getDamage().getType()));
                }
                if (it instanceof Armor a && a.getArmorClassBonus() != 0) notes.add("AC " + signed(a.getArmorClassBonus()));
                if (pi.getCondition() != null && pi.getCondition().getDurability() < 100) {
                    notes.add("durability " + pi.getCondition().getDurability());
                }
                text(fit(String.join(" · ", notes), serif, 7, M + CW - colX[5]), colX[5], y, serif, 7, GREY);
                y += 12;
            }
            for (int i = 0; i < 6; i++) {
                if (y + 12 > BOTTOM) {
                    flowPage();
                    tableHeader(colX);
                }
                rect(colX[0] + 2, y - 7, 7, 7, 0.6f, RULE);
                line(colX[1], y + 2.5f, M + CW, y + 2.5f, 0.35f, RULE);
                y += 12;
            }
            ensure(14);
            line(M, y - 6, M + CW, y - 6, 0.6f, INK);
            label("Total weight carried", colX[2], y + 2, 6);
            textRight(num(totalWeight) + " +", colX[4] + 34, y + 2, bold, 7.8f, INK);
            label("■ = equipped", colX[5], y + 2, 5.5f);
            y += 16;

            ensure(40);
            label("Coins (write in)", M, y + 2, 6);
            y += 6;
            String[] coins = {"CP", "SP", "EP", "GP", "PP"};
            float cw = (CW - 4 * 8) / 5f;
            for (int i = 0; i < 5; i++) {
                float cx = M + i * (cw + 8);
                fill(cx, y, 20, 22, SHADE);
                rect(cx, y, cw, 22, 0.8f, INK);
                line(cx + 20, y, cx + 20, y + 22, 0.8f, INK);
                textCenter(coins[i], cx + 10, y + 14, bold, 7.5f, INK);
            }
            y += 34;

            List<String> blueprints = pc.getKnownBlueprints();
            if (blueprints != null && !blueprints.isEmpty()) {
                subhead("Blueprint book");
                List<String> names = new ArrayList<>();
                for (String id : blueprints) {
                    Item it = items.apply(id);
                    names.add(it != null && !blank(it.getName()) ? it.getName() : id);
                }
                para(String.join(" · ", names), serif, 8, 0, INK);
                y += 4;
            }
        }

        private void tableHeader(float[] colX) throws IOException {
            ensure(30);
            label("Eq", colX[0], y, 5.5f);
            label("Item", colX[1], y, 5.5f);
            label("Type", colX[2], y, 5.5f);
            label("Qty", colX[3] + 12, y, 5.5f);
            label("Weight", colX[4] + 12, y, 5.5f);
            label("Notes", colX[5], y, 5.5f);
            line(M, y + 3, M + CW, y + 3, 0.6f, INK);
            y += 13;
        }

        /**
         * Picture frame (the character's image, or empty to draw in) with the appearance /
         * description text beside it; text longer than the frame continues full width below.
         */
        private void portraitSection() throws IOException {
            final float bw = 150, bh = 190, gap = 12, size = 8.2f, lh = size * 1.32f;
            section("Portrait & appearance");
            float top = y - 8;
            panel(M, top, bw, bh, "Character portrait");
            drawPortrait(M + 3, top + 3, bw - 6, bh - 14);

            float tx = M + bw + gap, tw = CW - bw - gap, limit = top + bh;
            float ty = y;
            String desc = pc.getDescription();
            String rest = null;
            if (blank(desc)) {
                label("Appearance", tx, ty, 6f);
                for (float ly = ty + 15; ly <= limit - 2; ly += 15) line(tx, ly, tx + tw, ly, 0.35f, RULE);
            } else {
                String[] paras = desc.replace("\r", "").split("\n", -1);
                outer:
                for (int pi = 0; pi < paras.length; pi++) {
                    List<String> lines = wrap(paras[pi], serif, size, tw);
                    for (int li = 0; li < lines.size(); li++) {
                        if (ty > limit - 2) {
                            StringBuilder sb = new StringBuilder(String.join(" ", lines.subList(li, lines.size())));
                            for (int k = pi + 1; k < paras.length; k++) sb.append('\n').append(paras[k]);
                            rest = sb.toString();
                            break outer;
                        }
                        text(lines.get(li), tx, ty, serif, size, INK);
                        ty += lh;
                    }
                }
            }
            y = top + bh + 14;
            if (rest != null && !rest.isBlank()) para(rest.strip(), serif, size, 0, INK);
        }

        private void drawPortrait(float x, float top, float w, float h) {
            if (portrait == null) return;
            try {
                PDImageXObject img = PDImageXObject.createFromFileByContent(portrait.toFile(), doc);
                float scale = Math.min(w / img.getWidth(), h / img.getHeight());
                float iw = img.getWidth() * scale, ih = img.getHeight() * scale;
                float ix = x + (w - iw) / 2f, itop = top + (h - ih) / 2f;
                cs.drawImage(img, ix, PH - itop - ih, iw, ih);
            } catch (Exception | LinkageError e) {
                // unreadable picture: leave the frame empty, like a character without one
            }
        }

        private void storySection() throws IOException {
            if (blank(pc.getBackstory())) return;
            section("Backstory");
            para(pc.getBackstory(), serif, 8, 0, INK);
        }

        private void notesSection() throws IOException {
            if (BOTTOM - y < 46 + 6 * 15) flowPage();
            section("Notes");
            while (y + 15 <= BOTTOM) {
                y += 15;
                line(M, y, M + CW, y, 0.35f, RULE);
            }
        }

        // ── Footers ─────────────────────────────────────────────────────────

        private void footers() throws IOException {
            int total = doc.getNumberOfPages();
            String date = LocalDate.now().toString();
            for (int i = 0; i < total; i++) {
                PDPage page = doc.getPage(i);
                try (PDPageContentStream f = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs = f;
                    line(M, PH - 24, M + CW, PH - 24, 0.4f, RULE);
                    text(fit(nz(pc.getName()), serif, 6.5f, 260) + " · printed " + date, M, PH - 15, serif, 6.5f, GREY);
                    textRight("Page " + (i + 1) + " of " + total, M + CW, PH - 15, serif, 6.5f, GREY);
                }
            }
            cs = null;
        }
    }
}
