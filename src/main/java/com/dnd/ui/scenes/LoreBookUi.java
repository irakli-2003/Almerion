package com.dnd.ui.scenes;

import com.dnd.cloud.CampaignMeta;
import com.dnd.data.LoreBook;
import com.dnd.data.LoreBookStore;
import com.dnd.data.LoreBookStore.Visibility;
import com.dnd.security.PrivateAccount;
import com.dnd.security.PrivateSessionFiles;
import com.dnd.security.PrivateSessionFiles.Access;
import com.dnd.ui.i18n.UiTranslator;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.Locale;

/** Searchable GM-only lore, optionally protected like a private session file. */
final class LoreBookUi {
    private record Selection(LoreBook.Section section, LoreBook.Article article) {
        @Override public String toString() { return article == null ? section.title : article.title; }
    }

    private LoreBookUi() { }

    static void open(BaseScene owner) {
        if (!owner.uiSession.isDm()) throw new SecurityException("Only a GM can open the lore book.");
        Path root = owner.uiSession.campaignRoot();
        if (root == null) throw new IllegalStateException("Open a campaign first.");
        String campaign = CampaignMeta.idOf(root).orElse(null);
        var store = new LoreBookStore(root, campaign);
        try {
            if (store.visibility() == Visibility.SHARED) {
                var opened = store.open(null, java.util.List.of());
                edit(owner, root, campaign, null, store, opened);
            } else {
                PrivateFilesUi.unlocked(owner, () -> {
                    PrivateAccount account = owner.uiSession.getPrivateAccount();
                    owner.background(() -> {
                        requireCurrent(owner, root, account);
                        return store.open(account, store.visibility() == Visibility.LEGACY_PRIVATE
                            ? CharacterNotesUi.recipients(owner, account, campaign, false, true) : java.util.List.of());
                    }, opened -> {
                        if (root.equals(owner.uiSession.campaignRoot()) && account == owner.uiSession.getPrivateAccount()) {
                            edit(owner, root, campaign, account, store, opened);
                        }
                    }, error -> owner.showError("Could not open the GM lore book", error));
                });
            }
        } catch (java.io.UncheckedIOException | IllegalArgumentException | SecurityException error) {
            owner.showError("Could not open the GM lore book", error);
        }
    }

    private static void edit(BaseScene owner, Path root, String campaign, PrivateAccount account,
                             LoreBookStore store, LoreBookStore.Opened opened) {
        LoreBook book = opened.book();
        boolean editable = opened.editable();
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("GM lore book");
        dialog.setHeaderText(switch (opened.visibility()) {
            case SHARED -> "Shared with campaign GMs. This book does not appear in Player Mode.";
            case LEGACY_PRIVATE -> "Legacy private lore. Upgrade it to manage access like a private session file.";
            case PRIVATE -> switch (opened.access()) {
                case OWNER -> "Private lore book: Owner access.";
                case WRITE -> "Private lore book: Read and Write access.";
                case READ -> "Private lore book: Read-only access.";
            };
        });
        TreeView<Selection> tree = new TreeView<>();
        tree.setShowRoot(false);
        tree.setPrefWidth(300);
        TextField search = owner.textField("Search lore");
        TextField title = UiTranslator.skip(new TextField());
        title.setPromptText("Section or article title");
        TextArea text = UiTranslator.skip(new TextArea());
        text.setWrapText(true);
        title.setEditable(editable);
        text.setEditable(editable);
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
        FlowPane actions = new FlowPane(8, 8);
        if (editable) actions.getChildren().addAll(section, article, delete);
        if (opened.visibility() != Visibility.PRIVATE) {
            boolean legacy = opened.visibility() == Visibility.LEGACY_PRIVATE;
            Button protect = owner.btn(legacy ? "Upgrade private access" : "Make private", () -> {
                if (dirty[0]) {
                    owner.showInfo("Lore book", "Save your changes before changing access.");
                    return;
                }
                if (!confirm(owner, legacy
                    ? "Upgrade this encrypted book and become its owner? It will stay private. Current GMs who already have access keep read and write access."
                    : "Make this book private and become its owner? Earlier Git history, exports and copies may still contain shared text.")) return;
                PrivateFilesUi.unlocked(owner, () -> {
                    var actor = owner.uiSession.getPrivateAccount();
                    owner.background(() -> {
                        requireCurrent(owner, root, actor);
                        if (campaign == null) throw new IllegalStateException("Register the campaign before making its lore book private.");
                        var writers = legacy ? CharacterNotesUi.recipients(owner, actor, campaign, true, true) : java.util.List.<PrivateAccount.Identity>of();
                        store.protect(opened, actor, writers);
                        return true;
                    }, ignored -> reopen(owner, dialog), error -> owner.showError("Could not protect the lore book", error));
                });
            });
            actions.getChildren().add(protect);
        } else if (opened.access() == Access.OWNER) {
            actions.getChildren().addAll(owner.btn("Manage access", () -> {
                if (dirty[0]) {
                    owner.showInfo("Lore book", "Save your changes before changing access.");
                    return;
                }
                PrivateFilesUi.editAccess(owner, account, PrivateSessionFiles.parse(opened.version()).header(),
                    "Lore book access", grants -> {
                        requireCurrent(owner, root, account);
                        store.replaceAccess(opened, account, grants);
                    }, () -> {
                        dialog.close();
                        owner.showInfo("Lore book access updated", "Sync with GitHub, then have the other GM sync to receive the updated book.");
                    });
            }), owner.btn("Make shared", () -> {
                if (dirty[0]) {
                    owner.showInfo("Lore book", "Save your changes before changing access.");
                    return;
                }
                if (!confirm(owner, "Make the lore book readable by anyone with a copy of the campaign files? This saves the book without encryption.")) return;
                owner.background(() -> {
                    requireCurrent(owner, root, account);
                    store.share(opened, account);
                    return true;
                }, ignored -> reopen(owner, dialog), error -> owner.showError("Could not share the lore book", error));
            }));
        }
        VBox content = new VBox(10, split, actions);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        if (editable) dialog.getDialogPane().getButtonTypes().add(save);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
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
        if (editable) saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (!owner.uiSession.isDm() || !root.equals(owner.uiSession.campaignRoot())
                || account != null && account != owner.uiSession.getPrivateAccount()) {
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
                requireCurrent(owner, root, account);
                store.save(opened, draft, account);
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

    private static void requireCurrent(BaseScene owner, Path root, PrivateAccount account) {
        if (!owner.isCurrentRole() || !owner.uiSession.isDm() || !root.equals(owner.uiSession.campaignRoot())
            || account != null && account != owner.uiSession.getPrivateAccount()) {
            throw new SecurityException("The GM context changed.");
        }
    }

    private static void reopen(BaseScene owner, Dialog<?> dialog) {
        if (!dialog.isShowing()) return;
        dialog.close();
        if (!dialog.isShowing()) open(owner);
    }

    private static boolean confirm(BaseScene owner, String text) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        owner.styleDialog(dialog);
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private static java.util.Optional<String> prompt(BaseScene owner, String header) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Lore book");
        dialog.setHeaderText(header);
        owner.styleDialog(dialog);
        return dialog.showAndWait().map(String::strip).filter(name -> !name.isEmpty());
    }
}
