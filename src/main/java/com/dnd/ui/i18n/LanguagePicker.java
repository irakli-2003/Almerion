package com.dnd.ui.i18n;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

/**
 * A compact "🌐 language" button. Clicking it opens a searchable list of every supported
 * language; picking one switches the whole UI and remembers the choice.
 */
public final class LanguagePicker extends Button {
    private static final String CSS_PATH = "/com/dnd/ui/styles/dnd-theme.css";

    private final UiTranslator translator = UiTranslator.get();
    // Held here so the translator's properties only keep weak references to this control.
    private final ChangeListener<Object> refreshLabel = (obs, old, now) -> updateLabel();
    private Popup popup;

    public LanguagePicker() {
        UiTranslator.skip(this);
        getStyleClass().add("dnd-button");
        setStyle("-fx-font-size: 12px; -fx-padding: 4 10 4 10;");
        setFocusTraversable(false);
        setTooltip(new Tooltip("Language"));
        if (translator == null) {
            setDisable(true);
        } else {
            translator.languageProperty().addListener(new javafx.beans.value.WeakChangeListener<>(refreshLabel));
            translator.busyProperty().addListener(new javafx.beans.value.WeakChangeListener<>(refreshLabel));
            setOnAction(e -> togglePopup());
        }
        updateLabel();
    }

    /** Adds a picker to the top-right corner of {@code pane} without disturbing its layout. */
    public static LanguagePicker pinTopRight(Pane pane) {
        LanguagePicker picker = new LanguagePicker();
        picker.setManaged(false);
        pane.getChildren().add(picker);
        Runnable place = () -> {
            picker.autosize();
            picker.relocate(Math.max(0, pane.getWidth() - picker.getWidth() - 14), 12);
        };
        pane.widthProperty().addListener(obs -> place.run());
        picker.textProperty().addListener(obs -> Platform.runLater(place));
        picker.sceneProperty().addListener(obs -> Platform.runLater(place));
        return picker;
    }

    private void updateLabel() {
        Language lang = translator == null ? Language.english() : translator.getLanguage();
        boolean loading = translator != null && translator.busyProperty().get();
        setText("🌐 " + lang.nativeName() + (loading ? " …" : ""));
        String font = lang.fontOverride();
        setStyle("-fx-font-size: 12px; -fx-padding: 4 10 4 10;" + (font == null ? "" : " -fx-font-family: \"" + font + "\";"));
    }

    private void togglePopup() {
        if (popup != null && popup.isShowing()) {
            popup.hide();
            return;
        }
        popup = buildPopup();
        Bounds bounds = localToScreen(getBoundsInLocal());
        if (bounds == null) return;
        popup.show(this, bounds.getMaxX() - 300, bounds.getMaxY() + 4);
    }

    private Popup buildPopup() {
        TextField search = new TextField();
        search.setPromptText("Search languages...");
        search.getStyleClass().add("dnd-text-field");

        FilteredList<Language> shown = new FilteredList<>(FXCollections.observableArrayList(Language.all()));
        ListView<Language> list = new ListView<>(shown);
        list.setPrefSize(300, 360);
        list.setStyle("-fx-control-inner-background: #14141f; -fx-background-color: #14141f;");
        list.setCellFactory(v -> new ListCell<>() {
            {
                setOnMouseClicked(e -> {
                    if (!isEmpty()) choose(getItem());
                });
            }

            @Override
            protected void updateItem(Language item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
                String font = empty || item == null ? null : item.fontOverride();
                setStyle(font == null ? "" : "-fx-font-family: \"" + font + "\";");
            }
        });
        list.getSelectionModel().select(translator.getLanguage());
        list.scrollTo(translator.getLanguage());

        search.textProperty().addListener((obs, old, query) -> {
            shown.setPredicate(lang -> lang.matches(query));
            if (!shown.isEmpty()) list.getSelectionModel().select(0);
        });
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) choose(list.getSelectionModel().getSelectedItem());
            else if (e.getCode() == KeyCode.DOWN) list.requestFocus();
            else if (e.getCode() == KeyCode.ESCAPE) popup.hide();
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) choose(list.getSelectionModel().getSelectedItem());
            else if (e.getCode() == KeyCode.ESCAPE) popup.hide();
        });

        VBox box = new VBox(6, search, list);
        box.setPadding(new Insets(8));
        box.getStylesheets().add(LanguagePicker.class.getResource(CSS_PATH).toExternalForm());
        box.setStyle("-fx-background-color: #16213e; -fx-border-color: #c9a84c; -fx-border-width: 1;");
        UiTranslator.skip(box);

        Popup p = new Popup();
        p.setAutoHide(true);
        p.setHideOnEscape(true);
        p.getContent().add(box);
        p.setOnShown(e -> search.requestFocus());
        return p;
    }

    private void choose(Language language) {
        if (language == null) return;
        if (popup != null) popup.hide();
        translator.setLanguage(language.code());
    }
}
