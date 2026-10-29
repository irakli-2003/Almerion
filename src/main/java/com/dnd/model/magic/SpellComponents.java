package com.dnd.model.magic;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What casting a spell needs: spoken words (verbal), hand gestures (somatic) and/or a material,
 * with the material's description, gold cost and whether casting uses it up.
 *
 * <p>Spell files store this the way they always have - a {@code "components"} letter list
 * ({@code ["V", "S", "M"]}) next to top-level {@code materialComponent}, {@code materialCost}
 * and {@code materialConsumed} - so files written by older versions load unchanged and older
 * versions can still read new files. See {@link Spell} for that mapping.</p>
 */
@JsonPropertyOrder({"verbal", "somatic", "material", "materialComponent", "materialCost", "materialConsumed"})
public class SpellComponents {
    private static final Pattern WORTH = Pattern.compile(",\\s*worth\\s+(\\d+)\\s*gp", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSUMED = Pattern.compile(",\\s*consumed\\s*$", Pattern.CASE_INSENSITIVE);

    private boolean verbal;
    private boolean somatic;
    private boolean material;
    /** What the material component is, e.g. "a pinch of sulfur and bat guano". */
    private String materialComponent;
    /** Gold value of a costly material (0 = trivial, covered by a component pouch or focus). */
    private int materialCost;
    /** True when casting uses the material up. */
    private boolean materialConsumed;

    public SpellComponents() {
    }

    public SpellComponents(boolean verbal, boolean somatic, boolean material) {
        this.verbal = verbal;
        this.somatic = somatic;
        this.material = material;
    }

    public boolean isVerbal() {
        return verbal;
    }

    public void setVerbal(boolean verbal) {
        this.verbal = verbal;
    }

    public boolean isSomatic() {
        return somatic;
    }

    public void setSomatic(boolean somatic) {
        this.somatic = somatic;
    }

    public boolean isMaterial() {
        return material;
    }

    public void setMaterial(boolean material) {
        this.material = material;
    }

    public String getMaterialComponent() {
        return materialComponent;
    }

    public void setMaterialComponent(String materialComponent) {
        this.materialComponent = materialComponent;
    }

    public int getMaterialCost() {
        return materialCost;
    }

    public void setMaterialCost(int materialCost) {
        this.materialCost = Math.max(0, materialCost);
    }

    public boolean isMaterialConsumed() {
        return materialConsumed;
    }

    public void setMaterialConsumed(boolean materialConsumed) {
        this.materialConsumed = materialConsumed;
    }

    /** True when no component is ticked. */
    public boolean none() {
        return !verbal && !somatic && !material;
    }

    /** The ticked components as letters, e.g. ["V", "M"] - the shape spell files store. */
    public List<String> letters() {
        List<String> letters = new ArrayList<>();
        if (verbal) letters.add("V");
        if (somatic) letters.add("S");
        if (material) letters.add("M");
        return letters;
    }

    /** Short component line, e.g. "V, S, M (a bit of fleece)" or "V"; empty when none are ticked. */
    public String text() {
        if (none()) return "";
        StringBuilder sb = new StringBuilder(String.join(", ", letters()));
        if (material && materialComponent != null && !materialComponent.isBlank()) {
            sb.append(" (").append(materialComponent.trim());
            if (materialCost > 0) sb.append(", worth ").append(materialCost).append(" gp");
            if (materialConsumed) sb.append(", consumed");
            sb.append(")");
        }
        return sb.toString();
    }

    /**
     * Reads the older free-form shapes: a letter list ({@code ["V", "S", "M"]}), a single line
     * ({@code "V, S, M (a pinch of sulfur)"}, words such as "verbal" also work) or an object
     * with {@code verbal}/{@code somatic}/{@code material} flags. Only the ticks - and, from a
     * line with a bracketed part, the material's description, cost and consumption - are set.
     */
    public void readLegacy(JsonNode node) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            verbal = node.path("verbal").asBoolean(verbal);
            somatic = node.path("somatic").asBoolean(somatic);
            material = node.path("material").asBoolean(material);
            return;
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(part -> parts.add(part.asText("")));
            readText(String.join(", ", parts));
            return;
        }
        readText(node.asText(""));
    }

    /** See {@link #readLegacy}: parses a line such as "V, S, M (a pinch of sulfur, worth 5 gp)". */
    public void readText(String text) {
        if (text == null) return;
        String letters = text;
        int open = text.indexOf('(');
        if (open >= 0) {
            int close = text.lastIndexOf(')');
            String inside = text.substring(open + 1, close > open ? close : text.length()).trim();
            letters = text.substring(0, open);
            Matcher consumed = CONSUMED.matcher(inside);
            if (consumed.find()) {
                materialConsumed = true;
                inside = inside.substring(0, consumed.start());
            }
            Matcher worth = WORTH.matcher(inside);
            if (worth.find()) {
                materialCost = Integer.parseInt(worth.group(1));
                inside = inside.substring(0, worth.start()) + inside.substring(worth.end());
            }
            if (!inside.isBlank()) materialComponent = inside.trim();
        }
        for (String part : letters.split("[,;/\\s]+")) {
            switch (part.trim().toLowerCase(Locale.ROOT)) {
                case "v", "verbal" -> verbal = true;
                case "s", "somatic" -> somatic = true;
                case "m", "material" -> material = true;
                default -> {
                    // anything else (stray punctuation, "and") is not a component
                }
            }
        }
    }

    @Override
    public String toString() {
        return text();
    }
}
