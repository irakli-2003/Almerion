package com.dnd.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reflective editor for a campaign entity.
 *
 * <p>The DM's world-building screens used to expose only {@code String}, {@code int} and
 * {@code boolean} properties, which meant everything that actually makes a monster a monster -
 * its ability scores, challenge rating, abilities and languages - was invisible even though it
 * was present in the campaign JSON. This builds an editor for the rich types too:</p>
 *
 * <ul>
 *   <li>ability-score blocks ({@link com.dnd.model.character.stats.CoreStats}) as a labelled
 *       grid that shows the derived modifier next to each score;</li>
 *   <li>enums (challenge rating, school, habitat, armour material) as drop-downs;</li>
 *   <li>id references (class, race, map, damage type, item, spell) as name drop-downs, and
 *       id <em>lists</em> (languages, triggered effects) as tick lists, so the DM never types
 *       a raw id;</li>
 *   <li>{@code Map<String, Integer>} / {@code Map<String, String>} blocks (ability bonuses,
 *       saving throws, place attributes, language dictionaries) as key/value rows;</li>
 *   <li>nested objects (hit die, damage, durability, concentration) inline;</li>
 *   <li>lists of nested objects (abilities, carried items, known spells) with add/remove.</li>
 * </ul>
 *
 * <p>Only properties with <em>both</em> a getter and a setter are editable. That is deliberate:
 * it keeps write-only setters such as {@code PlayerCharacter.setPassword} out of the form, which
 * previously appeared as an empty text box and silently hashed the empty string over the
 * character's real password on every save.</p>
 */
public final class EntityForm {

    /** One selectable catalogue entry for an id-reference field. */
    public static final class Ref {
        private final String id;
        private final String label;
        private final Object value;

        public Ref(String id, String label) {
            this(id, label, null);
        }

        /** @param value the catalogue entry itself, for lists that store whole objects rather than ids */
        public Ref(String id, String label, Object value) {
            this.id = id;
            this.label = label;
            this.value = value;
        }

        public String id() {
            return id;
        }

        public Object value() {
            return value;
        }

        @Override
        public String toString() {
            return (label == null || label.isBlank()) ? id : label + "  [" + id + "]";
        }
    }

    /** Nesting limit, so a self-referencing model can never build an infinite form. */
    private static final int MAX_DEPTH = 3;
    private static final List<String> LEADING = List.of("id", "name", "description");
    private static final Set<String> LONG_TEXT = Set.of("description", "overview", "backstory", "notes");
    /** Fields that usually hold several paragraphs get a tall editor; other long text a medium one. */
    private static final Set<String> TALL_TEXT = Set.of("description", "overview", "backstory");
    private static final String LABEL_STYLE = "-fx-text-fill: #a89060; -fx-font-size: 12px;";
    public static final String HINT_STYLE = "-fx-text-fill: #6a5a3a; -fx-font-size: 11px;";
    private static final String HELP_STYLE = "-fx-text-fill: #8a7a5a; -fx-font-size: 11px; -fx-font-style: italic;";
    private static final String OPTION_STYLE = "-fx-text-fill: #b8a070; -fx-font-size: 11px;";
    private static final String COMPACT_REMOVE_STYLE = "-fx-min-width: 0; -fx-padding: 3 8 3 8; -fx-font-size: 11px;";
    private static final String COMPACT_ROW_STYLE =
        "-fx-border-color: #c9a84c33; -fx-border-width: 0 0 1 0; -fx-padding: 4 6 4 6; -fx-background-color: #ffffff05;";
    private static final String GROUP_STYLE =
        "-fx-border-color: #c9a84c66; -fx-border-width: 1px; -fx-padding: 10; -fx-background-color: #ffffff08;";

    private abstract static class Binding {
        final String label;

        Binding(String label) {
            this.label = label;
        }

        abstract void write(Object target) throws Exception;
    }

    private final javafx.scene.layout.Pane node;
    /**
     * Rows inside an object list (spell slots, carried items, abilities...) are drawn compactly:
     * fields side by side with inline labels, help text as a tooltip instead of a line below.
     */
    private final boolean compact;
    private final List<Binding> bindings = new ArrayList<>();
    private final Map<String, List<Ref>> catalogs;
    private Button firstPicker;
    private final int depth;
    /** Class whose properties this form edits; picks context-specific {@link FieldHelp}. */
    private final Class<?> ownerType;

    private EntityForm(Class<?> type, Object value, Set<String> skip,
                       Map<String, List<Ref>> catalogs, int depth) {
        this(type, value, skip, catalogs, depth, false, false);
    }

    /** @param inline a nested part of a compact row (e.g. an item's condition): one line, no wrapping */
    private EntityForm(Class<?> type, Object value, Set<String> skip,
                       Map<String, List<Ref>> catalogs, int depth, boolean compact, boolean inline) {
        this.compact = compact;
        if (inline) {
            HBox line = new HBox(8);
            line.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            this.node = line;
        } else if (compact) {
            javafx.scene.layout.FlowPane flow = new javafx.scene.layout.FlowPane(12, 6);
            flow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            flow.setPrefWrapLength(820);
            this.node = flow;
        } else {
            this.node = new VBox(14);
        }
        this.catalogs = catalogs;
        this.depth = depth;
        this.ownerType = type;
        for (Prop prop : properties(type, skip)) {
            addEditor(prop, value);
        }
    }

    /**
     * @param type     declared entity type, used when {@code value} is {@code null} (a new entity)
     * @param value    the entity being edited, or {@code null} to build an empty form
     * @param skip     property names to leave out (e.g. a map's grid, edited in the Map Editor)
     * @param catalogs id options keyed by property name, for reference fields
     */
    public static EntityForm of(Class<?> type, Object value, Set<String> skip,
                                Map<String, List<Ref>> catalogs) {
        return new EntityForm(value != null ? value.getClass() : type, value, skip, catalogs, 0);
    }

    public Node getNode() {
        return node;
    }

    /** True when the form has no editable properties at all. */
    public boolean isEmpty() {
        return bindings.isEmpty();
    }

    /**
     * Pushes every edited value into {@code target}.
     *
     * <p>Model setters validate (ability scores are 1-30, spell levels 0-9, current durability
     * cannot exceed max), so failures are re-thrown tagged with the field that caused them -
     * "Save failed: strength: ..." is actionable, a bare exception is not.</p>
     */
    public void writeTo(Object target) {
        for (Binding binding : bindings) {
            try {
                binding.write(target);
            } catch (Exception e) {
                throw new IllegalArgumentException(binding.label + ": " + rootMessage(e), e);
            }
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
            ? error.getCause() : error;
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }

    // ── property discovery ──────────────────────────────────────────────────

    private record Prop(String name, Method getter, Method setter) {
        Class<?> type() {
            return setter.getParameterTypes()[0];
        }
    }

    private static List<Prop> properties(Class<?> type, Set<String> skip) {
        Map<String, Prop> found = new LinkedHashMap<>();
        for (Method setter : type.getMethods()) {
            if (!setter.getName().startsWith("set") || setter.getName().length() < 4
                || setter.getParameterCount() != 1) {
                continue;
            }
            String name = decapitalize(setter.getName().substring(3));
            if (skip.contains(name)) {
                continue;
            }
            Method getter = findGetter(type, name, setter.getParameterTypes()[0]);
            if (getter == null) {
                continue;
            }
            found.putIfAbsent(name, new Prop(name, getter, setter));
        }
        List<Prop> props = new ArrayList<>(found.values());
        props.sort(Comparator.comparingInt(EntityForm::orderOf).thenComparing(Prop::name));
        // A model class may list its fields in reading order (verbal, somatic, material...).
        com.fasterxml.jackson.annotation.JsonPropertyOrder order =
            type.getAnnotation(com.fasterxml.jackson.annotation.JsonPropertyOrder.class);
        if (order != null) {
            List<String> names = Arrays.asList(order.value());
            props.sort(Comparator.comparingInt(prop -> {
                int index = names.indexOf(prop.name());
                return index < 0 ? names.size() : index;
            }));
        }
        return props;
    }

    private static int orderOf(Prop prop) {
        int leading = LEADING.indexOf(prop.name());
        if (leading >= 0) {
            return leading;
        }
        Class<?> type = prop.type();
        if (type.isEnum()) {
            return 20;
        }
        if (isScalar(type)) {
            return 10;
        }
        if (Map.class.isAssignableFrom(type)) {
            return 40;
        }
        if (List.class.isAssignableFrom(type)) {
            return 50;
        }
        return 30;
    }

    private static Method findGetter(Class<?> type, String name, Class<?> valueType) {
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String prefix : List.of("get", "is")) {
            try {
                Method getter = type.getMethod(prefix + capitalized);
                if (getter.getParameterCount() == 0
                    && wrap(valueType).isAssignableFrom(wrap(getter.getReturnType()))) {
                    return getter;
                }
            } catch (NoSuchMethodException ignored) {
                // try the next prefix
            }
        }
        return null;
    }

    private static Class<?> wrap(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }

    private static boolean isScalar(Class<?> type) {
        return type == String.class || type == int.class || type == long.class
            || type == double.class || type == boolean.class;
    }

    private static boolean isModelObject(Class<?> type) {
        return type.getName().startsWith("com.dnd.model.") && !type.isEnum();
    }

    private static String decapitalize(String text) {
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    /** Turns {@code challengeRating} into {@code Challenge Rating} for display. */
    static String humanize(String propertyName) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < propertyName.length(); i++) {
            char c = propertyName.charAt(i);
            if (i == 0) {
                out.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c) && !Character.isUpperCase(propertyName.charAt(i - 1))) {
                out.append(' ').append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Type elementType(Method setter, int index) {
        Type generic = setter.getGenericParameterTypes()[0];
        if (generic instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (index < args.length && args[index] instanceof Class<?> cls) {
                return cls;
            }
        }
        return Object.class;
    }

    private static Object read(Method getter, Object owner) {
        if (owner == null) {
            return null;
        }
        try {
            return getter.invoke(owner);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object instantiate(Class<?> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    // ── editors ─────────────────────────────────────────────────────────────

    private void addEditor(Prop prop, Object owner) {
        Class<?> type = prop.type();
        Object current = read(prop.getter(), owner);
        List<Ref> options = catalogs.get(prop.name());

        if (type == String.class && options != null) {
            addRefEditor(prop, (String) current, options);
        } else if (isScalar(type)) {
            addScalarEditor(prop, current);
        } else if (type.isEnum()) {
            addEnumEditor(prop, current);
        } else if (com.dnd.model.character.stats.CoreStats.class.isAssignableFrom(type)) {
            addCoreStatsEditor(prop, current);
        } else if (com.dnd.model.character.stats.Skills.class.isAssignableFrom(type)) {
            addSkillsEditor(prop, current);
        } else if (Map.class.isAssignableFrom(type)) {
            addMapEditor(prop, current);
        } else if (List.class.isAssignableFrom(type)) {
            addListEditor(prop, current, options);
        } else if (isModelObject(type) && depth < MAX_DEPTH) {
            addNestedEditor(prop, current);
        }
    }

    private void addScalarEditor(Prop prop, Object current) {
        Class<?> type = prop.type();
        if (type == boolean.class) {
            CheckBox box = new CheckBox(humanize(prop.name()));
            box.getStyleClass().add("dnd-check-box");
            box.setSelected(Boolean.TRUE.equals(current));
            node.getChildren().add(box);
            bind(prop, () -> box.isSelected());
            return;
        }

        String text = current != null ? String.valueOf(current) : "";
        boolean longText = type == String.class && LONG_TEXT.contains(prop.name());
        Control editor;
        if (longText) {
            TextArea area = new TextArea(text);
            area.setWrapText(true);
            area.setPrefRowCount(compact ? 2 : TALL_TEXT.contains(prop.name()) ? 12 : 6);
            area.setMaxWidth(820);
            if (compact) area.setPrefWidth(420);
            editor = area;
        } else {
            TextField field = new TextField(text);
            field.setMaxWidth(type == String.class ? 500 : 160);
            if (compact) field.setPrefWidth(type == String.class ? 150 : 56);
            field.getStyleClass().add("dnd-text-field");
            editor = field;
        }
        node.getChildren().add(labelled(prop.name(), editor));
        boolean numeric = type == int.class || type == long.class || type == double.class;
        bind(prop, () -> {
            String raw = editor instanceof TextArea area ? area.getText() : ((TextField) editor).getText();
            // A blank numeric field left untouched (most commonly on a brand-new entity)
            // must not coerce the setter to 0 - many setters validate a minimum (e.g. Level
            // must be >= 1), and forcing 0 both fails the save with a confusing error and,
            // if it slipped through, would leave an unloadable record on disk. Skipping the
            // write instead leaves the field at whatever sane default the model already has.
            if (numeric && (raw == null || raw.trim().isEmpty())) {
                return SKIP;
            }
            return parseScalar(type, raw);
        });
    }

    private Object parseScalar(Class<?> type, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (type == String.class) {
            return raw == null ? "" : raw;
        }
        if (text.isEmpty()) {
            return type == double.class ? 0d : type == long.class ? 0L : 0;
        }
        if (type == int.class) return Integer.parseInt(text);
        if (type == long.class) return Long.parseLong(text);
        return Double.parseDouble(text);
    }

    private void addEnumEditor(Prop prop, Object current) {
        ComboBox<Object> combo = new ComboBox<>();
        combo.getItems().add(null);
        combo.getItems().addAll(Arrays.asList(prop.type().getEnumConstants()));
        combo.setMaxWidth(320);
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Object value) {
                return value == null ? "(none)" : enumLabel(value);
            }

            @Override
            public Object fromString(String text) {
                return combo.getItems().stream()
                    .filter(item -> toString(item).equals(text))
                    .findFirst().orElse(null);
            }
        });
        combo.getSelectionModel().select(current);
        javafx.scene.layout.Pane box = labelled(prop.name(), combo);
        // Explain the option currently picked, so the choice can be learned while making it.
        Label optionNote = new Label();
        optionNote.setWrapText(true);
        optionNote.setMaxWidth(620);
        optionNote.setStyle(OPTION_STYLE);
        Runnable explain = () -> {
            String text = FieldHelp.option(combo.getSelectionModel().getSelectedItem());
            optionNote.setText(text == null ? "" : "\u2192 " + text);
            optionNote.setVisible(text != null);
            optionNote.setManaged(text != null);
        };
        combo.valueProperty().addListener((obs, o, n) -> explain.run());
        explain.run();
        if (compact) {
            Tooltip tip = new Tooltip();
            tip.setWrapText(true);
            tip.setMaxWidth(420);
            combo.valueProperty().addListener((obs, o, n) -> tip.setText(optionNote.getText()));
            tip.setText(optionNote.getText());
            combo.setTooltip(tip);
        } else {
            box.getChildren().add(optionNote);
        }
        node.getChildren().add(box);
        bind(prop, combo.getSelectionModel()::getSelectedItem);
    }

    /** Enums here carry a display value for JSON ({@code "1/4"}, {@code "evocation"}); prefer it. */
    private static String enumLabel(Object constant) {
        try {
            Object value = constant.getClass().getMethod("getValue").invoke(constant);
            if (value != null) {
                return String.valueOf(value);
            }
        } catch (Exception ignored) {
            // enums without a JSON value fall through to their constant name
        }
        return ((Enum<?>) constant).name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Id reference (item, spell, class, ...) as a searchable picker: the DM never types a raw id. */
    private void addRefEditor(Prop prop, String currentId, List<Ref> options) {
        List<Ref> all = new ArrayList<>(options);
        Ref[] selected = {all.stream().filter(ref -> ref.id().equals(currentId)).findFirst().orElse(null)};
        if (selected[0] == null && currentId != null && !currentId.isBlank()) {
            // An id that is not in the catalogue any more: keep it rather than silently dropping it.
            selected[0] = new Ref(currentId, "(missing)");
            all.add(selected[0]);
        }
        Button picker = SearchPicker.single(all, selected[0], Ref::toString, true, ref -> selected[0] = ref);
        if (compact) picker.setPrefWidth(260);
        else {
            picker.setPrefWidth(420);
            picker.setMaxWidth(500);
        }
        if (firstPicker == null) firstPicker = picker;
        node.getChildren().add(labelled(prop.name(), picker));
        bind(prop, () -> selected[0] == null ? null : selected[0].id());
    }

    /** Opens the search of this form's first id picker (used right after adding a row). */
    void openFirstPicker() {
        if (firstPicker != null) firstPicker.fire();
    }

    private static final List<String> ABILITIES =
        List.of("strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma");

    private void addCoreStatsEditor(Prop prop, Object current) {
        Object stats = current != null ? current : instantiate(prop.type());
        GridPane grid = statGrid();
        Map<String, TextField> fields = new LinkedHashMap<>();

        for (int i = 0; i < ABILITIES.size(); i++) {
            String ability = ABILITIES.get(i);
            // A freshly constructed stat block holds zeros, which the model rejects on save
            // (scores are 1-30), so unset scores start at the standard average instead.
            int score = intProperty(stats, ability, 10);
            if (score < 1) {
                score = 10;
            }
            TextField field = new TextField(String.valueOf(score));
            field.getStyleClass().add("dnd-text-field");
            field.setPrefWidth(60);
            Label modifier = new Label(modifierText(score));
            modifier.setStyle(HINT_STYLE);
            field.textProperty().addListener((obs, old, text) -> modifier.setText(modifierText(parseIntOr(text, 10))));

            Label caption = new Label(ability.substring(0, 3).toUpperCase(Locale.ROOT));
            caption.setStyle(LABEL_STYLE);
            VBox cell = new VBox(2, caption, new HBox(6, field, modifier));
            ((HBox) cell.getChildren().get(1)).setAlignment(Pos.CENTER_LEFT);
            grid.add(cell, i % 3, i / 3);
            fields.put(ability, field);
        }

        node.getChildren().add(group(prop.name(), grid));
        Object statsTarget = stats;
        bindings.add(new Binding(humanize(prop.name())) {
            @Override
            void write(Object target) throws Exception {
                if (statsTarget == null) {
                    return;
                }
                for (Map.Entry<String, TextField> entry : fields.entrySet()) {
                    String ability = entry.getKey();
                    Method setter = statsTarget.getClass().getMethod(
                        "set" + Character.toUpperCase(ability.charAt(0)) + ability.substring(1), int.class);
                    try {
                        setter.invoke(statsTarget, parseIntOr(entry.getValue().getText(), 10));
                    } catch (InvocationTargetException e) {
                        throw new IllegalArgumentException(ability + ": " + rootMessage(e), e);
                    }
                }
                prop.setter().invoke(target, statsTarget);
            }
        });
    }

    /** All skills as a compact grid of whole-number fields (positive, negative or zero). */
    private void addSkillsEditor(Prop prop, Object current) {
        com.dnd.model.character.stats.Skills skills = current instanceof com.dnd.model.character.stats.Skills sk
            ? sk : new com.dnd.model.character.stats.Skills();
        GridPane grid = statGrid();
        Map<com.dnd.model.character.stats.Skills.Def, TextField> fields = new LinkedHashMap<>();
        int columns = 3;
        int rowsPerColumn = (com.dnd.model.character.stats.Skills.ALL.size() + columns - 1) / columns;
        for (int i = 0; i < com.dnd.model.character.stats.Skills.ALL.size(); i++) {
            com.dnd.model.character.stats.Skills.Def def = com.dnd.model.character.stats.Skills.ALL.get(i);
            int value = skills.get(def.key());
            TextField field = new TextField((value > 0 ? "+" : "") + value);
            field.getStyleClass().add("dnd-text-field");
            field.setPrefWidth(56);
            Label caption = new Label(def.label());
            caption.setStyle(LABEL_STYLE);
            caption.setMinWidth(120);
            Label ability = new Label(def.ability().substring(0, 3).toUpperCase(Locale.ROOT));
            ability.setStyle(HINT_STYLE);
            HBox cell = new HBox(6, caption, field, ability);
            cell.setAlignment(Pos.CENTER_LEFT);
            grid.add(cell, i / rowsPerColumn, i % rowsPerColumn);
            fields.put(def, field);
        }
        node.getChildren().add(group(prop.name(), grid));
        bindings.add(new Binding(humanize(prop.name())) {
            @Override
            void write(Object target) throws Exception {
                com.dnd.model.character.stats.Skills result = new com.dnd.model.character.stats.Skills();
                for (Map.Entry<com.dnd.model.character.stats.Skills.Def, TextField> entry : fields.entrySet()) {
                    String text = entry.getValue().getText() == null ? "" : entry.getValue().getText().trim();
                    if (text.startsWith("+")) text = text.substring(1);
                    int value;
                    try {
                        value = text.isEmpty() ? 0 : Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(entry.getKey().label() + ": enter a whole number like 3, 0 or -1");
                    }
                    result.set(entry.getKey().key(), value);
                }
                prop.setter().invoke(target, result);
            }
        });
    }

    private static GridPane statGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(8);
        return grid;
    }

    private static String modifierText(int score) {
        int modifier = Math.floorDiv(score - 10, 2);
        return (modifier >= 0 ? "+" : "") + modifier;
    }

    private static int parseIntOr(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int intProperty(Object owner, String name, int fallback) {
        if (owner == null) {
            return fallback;
        }
        try {
            Object value = owner.getClass()
                .getMethod("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1)).invoke(owner);
            return value instanceof Number number ? number.intValue() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private void addMapEditor(Prop prop, Object current) {
        Class<?> valueType = (Class<?>) elementType(prop.setter(), 1);
        Map<String, Object> existing = current instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        if (existing.isEmpty() && valueType == Integer.class) {
            // Ability-score style blocks read far better pre-seeded with the six abilities
            // than as an empty box the DM has to guess the keys for.
            for (String ability : ABILITIES) {
                existing.put(ability, 0);
            }
        }

        VBox rows = new VBox(4);
        List<Map.Entry<TextField, TextField>> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : existing.entrySet()) {
            pairs.add(mapRow(rows, pairs, entry.getKey(),
                entry.getValue() == null ? "" : String.valueOf(entry.getValue())));
        }

        Button add = new Button("Add entry");
        add.getStyleClass().add("dnd-button");
        add.setOnAction(e -> pairs.add(mapRow(rows, pairs, "", "")));

        VBox box = new VBox(8, rows, add);
        node.getChildren().add(group(prop.name(), box));
        bind(prop, () -> {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<TextField, TextField> pair : pairs) {
                String key = pair.getKey().getText().trim();
                if (key.isEmpty()) {
                    continue;
                }
                String raw = pair.getValue().getText().trim();
                result.put(key, valueType == Integer.class ? parseIntOr(raw, 0) : raw);
            }
            return result;
        });
    }

    private Map.Entry<TextField, TextField> mapRow(VBox rows, List<Map.Entry<TextField, TextField>> pairs,
                                                   String key, String value) {
        TextField keyField = new TextField(key);
        keyField.getStyleClass().add("dnd-text-field");
        keyField.setPrefWidth(180);
        keyField.setPromptText("key");
        TextField valueField = new TextField(value);
        valueField.getStyleClass().add("dnd-text-field");
        valueField.setPrefWidth(120);
        valueField.setPromptText("value");
        Button remove = new Button("✕");
        remove.getStyleClass().add("danger-button");
        remove.setStyle(COMPACT_REMOVE_STYLE);

        HBox row = new HBox(8, keyField, valueField, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        Map.Entry<TextField, TextField> pair = Map.entry(keyField, valueField);
        remove.setOnAction(e -> {
            rows.getChildren().remove(row);
            pairs.remove(pair);
        });
        rows.getChildren().add(row);
        return pair;
    }

    private void addListEditor(Prop prop, Object current, List<Ref> options) {
        Type element = elementType(prop.setter(), 0);
        Class<?> elementClass = element instanceof Class<?> cls ? cls : Object.class;
        List<?> values = current instanceof List<?> list ? list : List.of();

        if (elementClass == String.class) {
            if (options != null) {
                addRefListEditor(prop, values, options);
            } else {
                addTextListEditor(prop, values);
            }
        } else if (options != null && isModelObject(elementClass) && instantiate(elementClass) == null) {
            // Abstract element types (a spell's required items) cannot be created blank: pick
            // whole entries from the catalogue instead.
            addRefObjectListEditor(prop, values, options);
        } else if (isModelObject(elementClass) && depth < MAX_DEPTH) {
            addObjectListEditor(prop, values, elementClass);
        }
    }

    /** Lists of whole catalogue objects (a spell's required items) as a searchable tick list. */
    private void addRefObjectListEditor(Prop prop, List<?> values, List<Ref> options) {
        List<Ref> all = new ArrayList<>(options);
        List<Ref> initial = new ArrayList<>();
        for (Object value : values) {
            if (value == null) continue;
            String id = invokeString(value, "getId");
            String name = invokeString(value, "getName");
            String key = id != null && !id.isBlank() ? id : name;
            Ref ref = key == null ? null : all.stream().filter(r -> key.equals(r.id())).findFirst().orElse(null);
            if (ref == null) {
                // Not (or no longer) in the catalogue: keep the stored entry as it is.
                ref = new Ref(key == null ? "?" : key, (name == null ? "" : name + " ") + "(missing)", value);
                all.add(ref);
            }
            if (!initial.contains(ref)) initial.add(ref);
        }
        List<Object> selected = new ArrayList<>(initial.stream().map(Ref::value).filter(v -> v != null).toList());
        Button picker = SearchPicker.multi(all, initial, ref -> ref.label == null || ref.label.isBlank() ? ref.id() : ref.label,
            refs -> {
                selected.clear();
                refs.forEach(ref -> {
                    if (ref.value() != null) selected.add(ref.value());
                });
            });
        picker.setPrefWidth(compact ? 260 : 560);
        picker.setMaxWidth(560);
        node.getChildren().add(labelled(prop.name(), picker));
        bind(prop, () -> new ArrayList<>(selected));
    }

    private static String invokeString(Object owner, String method) {
        try {
            Object value = owner.getClass().getMethod(method).invoke(owner);
            return value == null ? null : value.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void addTextListEditor(Prop prop, List<?> values) {
        TextField field = new TextField(String.join(", ", values.stream().map(String::valueOf).toList()));
        field.getStyleClass().add("dnd-text-field");
        field.setMaxWidth(560);
        Label hint = new Label("comma separated");
        hint.setStyle(HINT_STYLE);
        if (compact) field.setPromptText("comma separated");
        javafx.scene.layout.Pane box = labelled(prop.name(), field);
        if (!compact) box.getChildren().add(hint);
        node.getChildren().add(box);
        bind(prop, () -> Arrays.stream(field.getText().split(","))
            .map(String::trim).filter(part -> !part.isEmpty()).toList());
    }

    /** Id lists (languages, triggered effects) as a tick list of catalogue names. */
    private void addRefListEditor(Prop prop, List<?> values, List<Ref> options) {
        List<String> selected = new ArrayList<>(values.stream().map(String::valueOf).toList());
        List<Ref> all = new ArrayList<>(options);
        for (String id : selected) {
            if (all.stream().noneMatch(ref -> ref.id().equals(id))) {
                all.add(new Ref(id, "(missing)"));
            }
        }
        List<Ref> initial = selected.stream()
            .map(id -> all.stream().filter(ref -> ref.id().equals(id)).findFirst().orElseThrow())
            .toList();
        Button picker = SearchPicker.multi(all, initial, ref -> ref.label == null || ref.label.isBlank() ? ref.id() : ref.label,
            refs -> {
                selected.clear();
                refs.forEach(ref -> selected.add(ref.id()));
            });
        picker.setPrefWidth(compact ? 260 : 560);
        picker.setMaxWidth(560);
        node.getChildren().add(labelled(prop.name(), picker));
        bind(prop, () -> List.copyOf(selected));
    }

    private void addObjectListEditor(Prop prop, List<?> values, Class<?> elementClass) {
        VBox rows = new VBox(4);
        List<Map.Entry<Object, EntityForm>> entries = new ArrayList<>();
        for (Object value : values) {
            if (value != null) {
                entries.add(objectRow(rows, entries, value));
            }
        }

        Button add = new Button("Add " + humanize(elementClass.getSimpleName()).toLowerCase(Locale.ROOT));
        add.getStyleClass().add("dnd-button");
        add.setOnAction(e -> {
            Object created = instantiate(elementClass);
            if (created != null) {
                Map.Entry<Object, EntityForm> row = objectRow(rows, entries, created);
                entries.add(row);
                javafx.application.Platform.runLater(row.getValue()::openFirstPicker);
            }
        });

        node.getChildren().add(group(prop.name() + " (" + values.size() + ")", new VBox(10, rows, add)));
        bind(prop, () -> {
            List<Object> result = new ArrayList<>();
            for (Map.Entry<Object, EntityForm> entry : entries) {
                entry.getValue().writeTo(entry.getKey());
                result.add(entry.getKey());
            }
            return result;
        });
    }

    private Map.Entry<Object, EntityForm> objectRow(VBox rows, List<Map.Entry<Object, EntityForm>> entries,
                                                    Object value) {
        EntityForm form = new EntityForm(value.getClass(), value, Set.of("imagePath"), catalogs, depth + 1, true, false);
        Button remove = new Button("\u2715");
        remove.getStyleClass().add("danger-button");
        remove.setStyle(COMPACT_REMOVE_STYLE);
        remove.setTooltip(new Tooltip("Remove"));

        HBox card = new HBox(8, form.getNode(), remove);
        card.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        card.setStyle(COMPACT_ROW_STYLE);
        HBox.setHgrow(form.getNode(), javafx.scene.layout.Priority.ALWAYS);
        remove.setOnAction(e -> {
            rows.getChildren().remove(card);
            entries.removeIf(entry -> entry.getValue() == form);
        });
        rows.getChildren().add(card);
        return Map.entry(value, form);
    }

    private void addNestedEditor(Prop prop, Object current) {
        // A null nested object (e.g. a spell with no damage) still needs a fresh instance to
        // build editable controls for, so the DM *can* fill one in from scratch - but if they
        // leave every field at its untouched default, writeTo below will hit the same setter
        // validation that protects a real save (Dice sides must be positive, etc.). Rather than
        // forcing an invalid or unwanted object into existence, that failure means "nothing was
        // actually filled in" and the property is left null, exactly like the blank-field SKIP
        // used for scalar properties above.
        boolean wasNull = current == null;
        Object instance = current != null ? current : instantiate(prop.type());
        if (instance == null) {
            return;
        }
        EntityForm form = new EntityForm(instance.getClass(), instance, Set.of("imagePath"),
            catalogs, depth + 1, compact, compact);
        if (form.isEmpty()) {
            return;
        }
        node.getChildren().add(group(prop.name(), form.getNode()));
        bindings.add(new Binding(humanize(prop.name())) {
            @Override
            void write(Object target) throws Exception {
                if (wasNull) {
                    try {
                        form.writeTo(instance);
                    } catch (Exception unfilled) {
                        return;
                    }
                } else {
                    form.writeTo(instance);
                }
                prop.setter().invoke(target, instance);
            }
        });
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private interface ValueSupplier {
        Object get();
    }

    /** Sentinel returned by a {@link ValueSupplier} to mean "leave the target's field alone". */
    private static final Object SKIP = new Object();

    private void bind(Prop prop, ValueSupplier supplier) {
        bindings.add(new Binding(humanize(prop.name())) {
            @Override
            void write(Object target) throws Exception {
                Object value = supplier.get();
                if (value == SKIP) return;
                prop.setter().invoke(target, value);
            }
        });
    }

    private javafx.scene.layout.Pane labelled(String propertyName, Node editor) {
        Label label = new Label(humanize(propertyName));
        label.setStyle(LABEL_STYLE);
        if (compact) {
            String text = FieldHelp.field(ownerType, propertyName);
            if (text != null) {
                Tooltip tip = new Tooltip(text);
                tip.setWrapText(true);
                tip.setMaxWidth(420);
                label.setTooltip(tip);
                if (editor instanceof Control c && c.getTooltip() == null) c.setTooltip(tip);
            }
            HBox row = new HBox(5, label, editor);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            return row;
        }
        VBox box = new VBox(4, label, editor);
        Label help = helpLabel(propertyName);
        if (help != null) box.getChildren().add(help);
        return box;
    }

    /** The grey explanatory line under a field, or null when there is nothing to explain. */
    private Label helpLabel(String propertyName) {
        String text = FieldHelp.field(ownerType, propertyName);
        if (text == null) return null;
        Label help = new Label(text);
        help.setWrapText(true);
        help.setMaxWidth(620);
        help.setStyle(HELP_STYLE);
        return help;
    }

    private Node group(String propertyName, Node content) {
        if (compact) {
            Label small = new Label(humanize(propertyName) + ":");
            small.setStyle(LABEL_STYLE);
            HBox inline = new HBox(6, small, content);
            inline.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            return inline;
        }
        Label label = new Label(humanize(propertyName));
        label.getStyleClass().add("section-label");
        Label help = helpLabel(propertyName);
        VBox box = help == null ? new VBox(8, label, content) : new VBox(8, label, help, content);
        box.setStyle(GROUP_STYLE);
        box.setPadding(new Insets(10));
        box.setMaxWidth(920);
        return box;
    }
}
