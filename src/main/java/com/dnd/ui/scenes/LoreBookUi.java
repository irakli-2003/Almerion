package com.dnd.ui.scenes;

import com.dnd.cloud.CampaignMeta;
import com.dnd.data.CharacterNotes;
import com.dnd.data.LoreBook;
import com.dnd.security.PrivateAccount;
import com.dnd.ui.i18n.UiTranslator;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.Locale;

/** Searchable GM-only lore, stored in the same encrypted format as GM character notes. */
final class LoreBookUi {
    public static final String DOCUMENT = "@gm-lore-book";
    private record Opened(CharacterNotes store, CharacterNotes.Loaded loaded, LoreBook book) { }
    private record Selection(LoreBook.Section section, LoreBook.Article article) {
        @Override public String toString() { return article == null ? section.title : article.title; }
    }

    private LoreBookUi() { }

    static void open(BaseScene owner) {
        if (!owner.uiSession.isDm()) throw new SecurityException("Only a GM can open the lore book.");
        Path root = owner.uiSession.campaignRoot();
        if (root == null) throw new IllegalStateException("Open a campaign first.");
        String campaign = CampaignMeta.idOf(root).orElseThrow(
            () -> new IllegalStateException("Register the campaign before using the shared lore book."));
        PrivateFilesUi.unlocked(owner, () -> {
            PrivateAccount account = owner.uiSession.getPrivateAccount();
            owner.background(() -> {
                var recipients = CharacterNotesUi.recipients(owner, account, campaign, false);
                var store = CharacterNotes.loreBook(root, campaign, account);
                var loaded = store.read(DOCUMENT, recipients);
                return new Opened(store, loaded, LoreBook.parse(loaded.text()));
            }, opened -> edit(owner, root, campaign, account, opened),
                error -> owner.showError("Could not open the GM lore book", error));
        });
    }

    private static void edit(BaseScene owner, Path root, String campaign, PrivateAccount account, Opened opened) {
        LoreBook book = opened.book();
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("GM lore book");
        dialog.setHeaderText("Private campaign lore. Reading works offline after setup; saving requires online access."
            + " Sync the campaign to share changes with other GMs.");
        TreeView<Selection> tree = new TreeView<>();
        tree.setShowRoot(false);
        tree.setPrefWidth(300);
        TextField search = owner.textField("Search lore");
        TextField title = UiTranslator.skip(new TextField());
        title.setPromptText("Section or article title");
        TextArea text = UiTranslator.skip(new TextArea());
        text.setWrapText(true);
        text.setPrefColumnCount(72);
        text.setPrefRowCount(24);
        Label empty = new Label("Select a section or article.");
        VBox editor = new VBox(8, empty, title, text);
        VBox.setVgrow(text, Priority.ALWAYS);
        title.setDisable(true);
        text.setDisable(true);
        VBox navigation = new VBox(8, search, tree);
        VBox.setVgrow(tree, Priority.ALWAYS);
        SplitPane split = new SplitPane(navigation, editor);
        split.setDividerPositions(0.30);
        VBox.setVgrow(split, Priority.ALWAYS);
        boolean[] updating = {false};
        boolean[] dirty = {false};
        boolean[] saving = {false};

        Runnable rebuild = () -> {
            String query = search.getText().strip().toLowerCase(Locale.ROOT);
            TreeItem<Selection> rootItem = new TreeItem<>();
            for (LoreBook.Section section : book.sections) {
                boolean sectionMatches = section.title.toLowerCase(Locale.ROOT).contains(query);
                TreeItem<Selection> branch = new TreeItem<>(new Selection(section, null));
                for (LoreBook.Article article : section.articles) {
                    if (sectionMatches || article.title.toLowerCase(Locale.ROOT).contains(query)
                        || article.text.toLowerCase(Locale.ROOT).contains(query)) {
                        branch.getChildren().add(new TreeItem<>(new Selection(section, article)));
                    }
                }
                if (sectionMatches || !branch.getChildren().isEmpty()) {
                    branch.setExpanded(true);
                    rootItem.getChildren().add(branch);
                }
            }
            tree.setRoot(rootItem);
        };
        tree.getSelectionModel().selectedItemProperty().addListener((obs, previous, current) -> {
            updating[0] = true;
            Selection selected = current == null ? null : current.getValue();
            title.setDisable(selected == null);
            text.setDisable(selected == null || selected.article() == null);
            title.setText(selected == null ? "" : selected.toString());
            text.setText(selected == null || selected.article() == null ? "" : selected.article().text);
            empty.setText(selected == null ? "Select a section or article."
                : selected.article() == null ? "Section" : "Article");
            updating[0] = false;
        });
        title.textProperty().addListener((obs, previous, value) -> {
            var item = tree.getSelectionModel().getSelectedItem();
            if (updating[0] || item == null) return;
            if (item.getValue().article() == null) item.getValue().section().title = value;
            else item.getValue().article().title = value;
            dirty[0] = true;
            tree.refresh();
        });
        text.textProperty().addListener((obs, previous, value) -> {
            var item = tree.getSelectionModel().getSelectedItem();
            if (updating[0] || item == null || item.getValue().article() == null) return;
            item.getValue().article().text = value;
            dirty[0] = true;
        });
        search.textProperty().addListener((obs, previous, value) -> rebuild.run());
        rebuild.run();

        Button section = owner.btn("Add section", () -> {
            prompt(owner, "New lore section").ifPresent(name -> {
                book.sections.add(new LoreBook.Section(name));
                dirty[0] = true;
                search.clear();
                rebuild.run();
            });
        });
        Button article = owner.btn("Add article", () -> {
            var selected = tree.getSelectionModel().getSelectedItem();
            if (selected == null) {
                owner.showInfo("Lore book", "Select a section first.");
                return;
            }
            LoreBook.Section parent = selected.getValue().section();
            prompt(owner, "New lore article").ifPresent(name -> {
                parent.articles.add(new LoreBook.Article(name, ""));
                dirty[0] = true;
                search.clear();
                rebuild.run();
            });
        });
        Button delete = owner.dangerBtn("Delete selected", () -> {
            var selected = tree.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete the selected lore entry? Deleting a section also deletes its articles.",
                ButtonType.OK, ButtonType.CANCEL);
            owner.styleDialog(confirmation);
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            Selection value = selected.getValue();
            if (value.article() == null) book.sections.remove(value.section());
            else value.section().articles.remove(value.article());
            dirty[0] = true;
            rebuild.run();
        });
        VBox content = new VBox(10, split, new FlowPane(8, 8, section, article, delete));
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        owner.styleDialog(dialog);
        var saveButton = dialog.getDialogPane().lookupButton(save);
        var cancelButton = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        dialog.setOnCloseRequest(event -> {
            if (saving[0]) { event.consume(); return; }
            if (dirty[0]) {
                Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, "Discard unsaved lore changes?",
                    ButtonType.OK, ButtonType.CANCEL);
                owner.styleDialog(confirmation);
                if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) event.consume();
            }
        });
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (!owner.uiSession.isDm() || !root.equals(owner.uiSession.campaignRoot())
                || account != owner.uiSession.getPrivateAccount()) {
                owner.showError("Could not save lore", new SecurityException("The GM context changed."));
                return;
            }
            String draft;
            try {
                draft = book.serialize();
            } catch (RuntimeException error) {
                owner.showError("Could not save lore", error);
                return;
            }
            saving[0] = true;
            saveButton.setDisable(true);
            cancelButton.setDisable(true);
            content.setDisable(true);
            owner.background(() -> {
                var recipients = CharacterNotesUi.recipients(owner, account, campaign, true);
                opened.store().save(DOCUMENT, opened.loaded(), draft, recipients);
                return true;
            }, ignored -> {
                dirty[0] = false;
                saving[0] = false;
                dialog.close();
            }, error -> {
                saving[0] = false;
                saveButton.setDisable(false);
                cancelButton.setDisable(false);
                content.setDisable(false);
                owner.showError("Could not save lore", error);
            });
        });
        dialog.showAndWait();
    }

    private static java.util.Optional<String> prompt(BaseScene owner, String header) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Lore book");
        dialog.setHeaderText(header);
        owner.styleDialog(dialog);
        return dialog.showAndWait().map(String::strip).filter(name -> !name.isEmpty());
    }
}
