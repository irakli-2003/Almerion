package com.dnd.ui.components;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A field-like button that opens a search box over a long catalogue (items, spells, ...):
 * type to filter, then click a match or press Enter for the first one.
 */
public final class SearchPicker {

    private static final String FIELD_STYLE =
        "-fx-background-color: #0f0f1e; -fx-text-fill: #e0d5c0; -fx-border-color: #c9a84c;"
            + " -fx-border-width: 1; -fx-border-radius: 3; -fx-background-radius: 3;"
            + " -fx-padding: 4 8; -fx-cursor: hand; -fx-min-width: 0;";

    private static final String OPTIONS_KEY = SearchPicker.class.getName() + ".options";
    private static final String CHOOSE_KEY = SearchPicker.class.getName() + ".choose";

    private SearchPicker() {
    }

    /** The options of a {@link #single} picker, or null if {@code node} is not one. */
    static List<?> options(javafx.scene.Node node) {
        return node.getProperties().get(OPTIONS_KEY) instanceof List<?> l ? l : null;
    }

    /** Selects {@code value} in a {@link #single} picker, as if clicked in its list. */
    @SuppressWarnings("unchecked")
    static void choose(javafx.scene.Node node, Object value) {
        if (node.getProperties().get(CHOOSE_KEY) instanceof Consumer<?> c) ((Consumer<Object>) c).accept(value);
    }

    /** Picks one option; {@code onChange} receives the new choice (null when "(none)" is chosen). */
    public static <T> Button single(List<T> options, T initial, Function<T, String> label,
                                    boolean allowNone, Consumer<T> onChange) {
        Button field = field();
        Object none = new Object();
        Consumer<T> show = t -> field.setText((t == null ? "(none)" : label.apply(t)) + "   \u25BE");
        show.accept(initial);
        Consumer<T> choose = t -> {
            show.accept(t);
            onChange.accept(t);
        };
        field.getProperties().put(OPTIONS_KEY, List.copyOf(options));
        field.getProperties().put(CHOOSE_KEY, choose);
        field.setOnAction(e -> {
            List<Object> all = new ArrayList<>();
            if (allowNone) all.add(none);
            all.addAll(options);
            ListView<Object> list = new ListView<>();
            list.setCellFactory(v -> new ListCell<>() {
                @Override
                protected void updateItem(Object item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item == none ? "(none)" : label(label, item));
                }
            });
            @SuppressWarnings("unchecked")
            Consumer<Object> pick = o -> {
                if (o == null) return;
                choose.accept(o == none ? null : (T) o);
            };
            open(field, all, o -> o == none ? "(none)" : label(label, o), list, pick, true);
        });
        return field;
    }

    /** Ticks any number of options; {@code onChange} receives the full ticked list after each change. */
    public static <T> Button multi(List<T> options, List<T> initial, Function<T, String> label,
                                   Consumer<List<T>> onChange) {
        Button field = field();
        List<T> ticked = new ArrayList<>(initial);
        Runnable show = () -> field.setText((ticked.isEmpty() ? "(none)"
            : String.join(", ", ticked.stream().map(label).toList())) + "   \u25BE");
        show.run();
        field.setOnAction(e -> {
            Map<Object, BooleanProperty> state = new IdentityHashMap<>();
            for (T t : options) {
                BooleanProperty p = new SimpleBooleanProperty(ticked.contains(t));
                p.addListener((o, was, is) -> {
                    if (is && !ticked.contains(t)) ticked.add(t);
                    if (!is) ticked.remove(t);
                    show.run();
                    onChange.accept(List.copyOf(ticked));
                });
                state.put(t, p);
            }
            ListView<Object> list = new ListView<>();
            list.setCellFactory(CheckBoxListCell.forListView(state::get, new javafx.util.StringConverter<>() {
                @Override
                public String toString(Object o) {
                    return o == null ? "" : label(label, o);
                }

                @Override
                public Object fromString(String s) {
                    return null;
                }
            }));
            Consumer<Object> toggle = o -> {
                BooleanProperty p = o == null ? null : state.get(o);
                if (p != null) p.set(!p.get());
            };
            open(field, new ArrayList<>(options), o -> label(label, o), list, toggle, false);
        });
        return field;
    }

    @SuppressWarnings("unchecked")
    private static <T> String label(Function<T, String> label, Object o) {
        return label.apply((T) o);
    }

    private static Button field() {
        Button b = new Button();
        b.setStyle(FIELD_STYLE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setTextOverrun(OverrunStyle.ELLIPSIS);
        b.setMnemonicParsing(false);
        return b;
    }

    private static void open(Button anchor, List<Object> all, Function<Object, String> text,
                             ListView<Object> list, Consumer<Object> pick, boolean closeOnPick) {
        if (anchor.getScene() == null || anchor.getScene().getWindow() == null) return;
        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        TextField search = new TextField();
        search.setPromptText(closeOnPick ? "Type to search... (Enter picks the first match)"
            : "Type to search... (click to tick or untick)");
        search.getStyleClass().add("dnd-text-field");
        list.getStyleClass().add("dnd-list-view");
        list.setPrefHeight(Math.min(320, Math.max(60, all.size() * 30 + 6)));
        list.setPlaceholder(new Label("No match."));

        Runnable filter = () -> {
            String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
            list.getItems().setAll(all.stream()
                .filter(o -> q.isEmpty() || text.apply(o).toLowerCase(Locale.ROOT).contains(q))
                .toList());
        };
        search.textProperty().addListener((o, a, b) -> filter.run());
        filter.run();

        Consumer<Object> choose = o -> {
            if (o == null) return;
            pick.accept(o);
            if (closeOnPick) popup.hide();
        };
        list.setOnMouseClicked(e -> {
            if (closeOnPick && e.getButton() == MouseButton.PRIMARY) choose.accept(list.getSelectionModel().getSelectedItem());
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE && !closeOnPick) {
                choose.accept(list.getSelectionModel().getSelectedItem());
                e.consume();
            }
        });
        search.setOnAction(e -> {
            if (!list.getItems().isEmpty()) choose.accept(list.getItems().get(0));
        });
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN && !list.getItems().isEmpty()) {
                list.requestFocus();
                list.getSelectionModel().selectFirst();
                e.consume();
            }
        });

        VBox box = new VBox(6, search, list);
        box.getStyleClass().add("picker-box");
        box.setStyle("-fx-background-color: #10101f; -fx-border-color: #c9a84c; -fx-border-width: 1;"
            + " -fx-padding: 8; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 12, 0, 0, 4);");
        box.getStylesheets().addAll(anchor.getScene().getStylesheets());
        box.setPrefWidth(Math.max(360, anchor.getWidth()));
        popup.getContent().add(box);

        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, b.getMinX(), b.getMaxY() + 2);
        Platform.runLater(search::requestFocus);
    }
}
