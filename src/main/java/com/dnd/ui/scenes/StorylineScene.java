package com.dnd.ui.scenes;

import com.dnd.data.StorylineService;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DM-only screen: a file tree of the campaign's storyline (folders and session text files)
 * on the left, and a horizontally-scrollable nested-arc timeline visualization on the right.
 */
public class StorylineScene extends BaseScene {

    private StorylineService service;
    private TreeView<Path> tree;
    private ScrollPane arcScroll;

    public StorylineScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        service = new StorylineService(uiSession.campaignRoot(), uiSession::getPrivateAccount);
        service.ensureRoot();

        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(SceneType.DM_MENU));

        VBox content = new VBox(12);
        content.setPadding(new Insets(10, 20, 20, 20));
        FlowPane header = new FlowPane(10, 10, title("Storyline"),
            btn("Unlock private files", () -> PrivateFilesUi.unlocked(this, this::refreshAll)),
            btn("Notifications", () -> PrivateFilesUi.notifications(this)),
            btn("Lock private files", () -> { uiSession.lockPrivateAccount(); refreshAll(); }));
        content.getChildren().add(header);

        SplitPane split = new SplitPane();
        split.getItems().addAll(buildTreePane(), buildArcPane());
        split.setDividerPositions(0.3);
        VBox.setVgrow(split, Priority.ALWAYS);

        content.getChildren().add(split);
        VBox.setVgrow(content, Priority.ALWAYS);
        root.getChildren().add(content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return wrapInScene(root);
    }

    private VBox buildTreePane() {
        VBox pane = new VBox(8);
        pane.setPadding(new Insets(0, 10, 0, 0));
        pane.setPrefWidth(260);

        tree = new TreeView<>();
        tree.setShowRoot(true);
        tree.setCellFactory(tv -> new StorylineTreeCell());
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Path selected = selectedPath();
                if (selected != null && service.isSessionFile(selected)) {
                    openEditor(selected);
                }
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);

        Button newFolderBtn = btn("New Folder", this::onNewFolder);
        Button newFileBtn = btn("New File", this::onNewFile);
        MenuButton moveBtn = new MenuButton("Move to Folder");
        moveBtn.getStyleClass().add("dnd-button");
        moveBtn.setTooltip(new Tooltip("Pick a destination folder - one click moves the selected item"));
        moveBtn.getItems().add(new MenuItem("…"));
        moveBtn.setOnShowing(e -> fillMoveMenu(moveBtn));
        Button upBtn = btn("↑", () -> onNudge(true));
        Button downBtn = btn("↓", () -> onNudge(false));
        upBtn.setTooltip(new Tooltip("Move the selected item up among its siblings"));
        downBtn.setTooltip(new Tooltip("Move the selected item down among its siblings"));
        Button deleteBtn = dangerBtn("Delete", this::onDelete);
        Button accessBtn = btn("Session-file access", () -> {
            Path selected = selectedPath();
            if (selected != null && service.isSessionFile(selected)) {
                PrivateFilesUi.access(this, service, selected, this::refreshAll);
            }
        });
        accessBtn.setDisable(true);
        newFolderBtn.setDisable(true);
        newFileBtn.setDisable(true);
        moveBtn.setDisable(true);
        deleteBtn.setDisable(true);
        upBtn.setDisable(true);
        downBtn.setDisable(true);

        tree.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            boolean hasSelection = newV != null;
            boolean isRoot = hasSelection && newV.getValue().equals(service.getRoot());
            newFolderBtn.setDisable(!hasSelection);
            newFileBtn.setDisable(!hasSelection);
            moveBtn.setDisable(!hasSelection || isRoot);
            deleteBtn.setDisable(!hasSelection || isRoot);
            upBtn.setDisable(!hasSelection || isRoot);
            downBtn.setDisable(!hasSelection || isRoot);
            accessBtn.setDisable(!hasSelection || !service.isSessionFile(newV.getValue()));
        });

        FlowPane actions = new FlowPane(6, 6, newFolderBtn, newFileBtn, accessBtn, moveBtn, upBtn, downBtn, deleteBtn);

        Label hint = body("Drag an item onto another to reorder it, or onto a folder to move it in.");
        hint.setStyle("-fx-font-size: 11px;");

        pane.getChildren().addAll(tree, hint, actions);
        refreshTree();
        return pane;
    }

    /** Moves the selected item one slot up ({@code up}) or down among its siblings. */
    private void onNudge(boolean up) {
        Path selected = selectedPath();
        if (selected == null || selected.equals(service.getRoot())) return;
        try {
            if (up) service.moveUp(selected);
            else service.moveDown(selected);
            refreshAll();
            selectPath(selected);
        } catch (RuntimeException e) {
            showError(e.getMessage());
        }
    }

    /** Restores the tree selection to {@code target} after a rebuild. */
    private void selectPath(Path target) {
        if (target == null || tree.getRoot() == null) return;
        selectPathIn(tree.getRoot(), target);
    }

    private boolean selectPathIn(TreeItem<Path> item, Path target) {
        if (target.equals(item.getValue())) {
            tree.getSelectionModel().select(item);
            return true;
        }
        for (TreeItem<Path> child : item.getChildren()) {
            if (selectPathIn(child, target)) return true;
        }
        return false;
    }

    /**
     * Tree cell with drag-and-drop: dropping an item onto a folder moves it into that
     * folder, and dropping it onto a sibling reorders it to that sibling's position.
     * Both outcomes are persisted, so the arc timeline on the right always matches the
     * sequence shown here.
     */
    private final class StorylineTreeCell extends TreeCell<Path> {
        private static final javafx.scene.input.DataFormat PATH_FORMAT =
            new javafx.scene.input.DataFormat("application/x-dnd-storyline-path");

        StorylineTreeCell() {
            setOnDragDetected(e -> {
                Path item = getItem();
                if (item == null || item.equals(service.getRoot())) return;
                javafx.scene.input.Dragboard db = startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.put(PATH_FORMAT, item.toAbsolutePath().toString());
                db.setContent(content);
                e.consume();
            });

            setOnDragOver(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasContent(PATH_FORMAT) && getItem() != null) {
                    e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
                }
                e.consume();
            });

            setOnDragEntered(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasContent(PATH_FORMAT)) {
                    setStyle("-fx-background-color: #2d3a52;");
                }
            });
            setOnDragExited(e -> setStyle(""));

            setOnDragDropped(e -> {
                javafx.scene.input.Dragboard db = e.getDragboard();
                Path target = getItem();
                if (!db.hasContent(PATH_FORMAT) || target == null) {
                    e.setDropCompleted(false);
                    e.consume();
                    return;
                }
                Path source = java.nio.file.Paths.get((String) db.getContent(PATH_FORMAT));
                e.setDropCompleted(handleDrop(source, target));
                e.consume();
            });
        }

        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : displayName(item));
            if (empty || item == null) setStyle("");
        }
    }

    /**
     * @return whether the drop changed anything. Dropping onto a folder moves the item
     *         inside it; dropping onto a file reorders the item to that file's slot.
     */
    private boolean handleDrop(Path source, Path target) {
        if (source.equals(target) || source.equals(service.getRoot())) return false;
        try {
            if (service.isFolder(target) && !target.equals(source.getParent())) {
                service.move(source, target);
            } else {
                Path targetParent = service.isFolder(target) ? target : target.getParent();
                if (targetParent == null) return false;
                Path moved = source;
                if (!targetParent.equals(source.getParent())) {
                    moved = service.move(source, targetParent);
                }
                int targetIndex = service.indexOf(target);
                if (targetIndex >= 0) service.reorder(moved, targetIndex);
                source = moved;
            }
            refreshAll();
            selectPath(source);
            return true;
        } catch (RuntimeException e) {
            showError(e.getMessage());
            return false;
        }
    }

    private ScrollPane buildArcPane() {
        arcScroll = new ScrollPane();
        arcScroll.setFitToHeight(true);
        arcScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        arcScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        refreshArc();
        return arcScroll;
    }

    private String displayName(Path item) {
        return item.equals(service.getRoot()) ? "Storyline" : item.getFileName().toString()
            + (service.isPrivate(item) ? " [private]" : "");
    }

    private void refreshTree() {
        TreeItem<Path> rootItem = buildTreeItem(service.getRoot());
        rootItem.setExpanded(true);
        tree.setRoot(rootItem);
    }

    private TreeItem<Path> buildTreeItem(Path path) {
        TreeItem<Path> item = new TreeItem<>(path);
        if (service.isFolder(path)) {
            for (Path child : service.listChildren(path)) {
                TreeItem<Path> childItem = buildTreeItem(child);
                childItem.setExpanded(true);
                item.getChildren().add(childItem);
            }
        }
        return item;
    }

    private void refreshArc() {
        arcScroll.setContent(StoryArcView.build(service, service.getRoot()));
    }

    private void refreshAll() {
        refreshTree();
        refreshArc();
    }

    private Path selectedPath() {
        TreeItem<Path> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private Path selectedFolder() {
        Path p = selectedPath();
        if (p == null) return null;
        return service.isFolder(p) ? p : p.getParent();
    }

    private void onNewFolder() {
        Path parent = selectedFolder();
        if (parent == null) return;
        promptName("New Folder", "Folder name:").ifPresent(name -> {
            try {
                service.createFolder(parent, name);
                refreshAll();
            } catch (RuntimeException e) {
                showError(e.getMessage());
            }
        });
    }

    private void onNewFile() {
        Path parent = selectedFolder();
        if (parent == null) return;
        PrivateFilesUi.unlocked(this, () -> promptName("New File", "File name:").ifPresent(name ->
            background(() -> service.createFile(parent, name), created -> {
                refreshAll();
                selectPath(created);
            }, error -> showError("Could not create private session file", error))));
    }

    /** Repeatedly prompts until a non-blank name is entered, or the user cancels. */
    private Optional<String> promptName(String dialogTitle, String prompt) {
        while (true) {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle(dialogTitle);
            dialog.setHeaderText(null);
            dialog.setContentText(prompt);
            styleDialog(dialog);
            Optional<String> result = dialog.showAndWait();
            if (result.isEmpty()) return Optional.empty();
            String name = result.get().trim();
            if (!name.isBlank()) return Optional.of(name);
            showError("Name is required.");
        }
    }

    private void onDelete() {
        Path target = selectedPath();
        if (target == null || target.equals(service.getRoot())) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Are you sure you want to delete '" + target.getFileName() + "'?", ButtonType.OK, ButtonType.CANCEL);
        styleDialog(confirm);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                var sceneRoot = tree.getScene().getRoot();
                sceneRoot.setDisable(true);
                com.dnd.sync.CampaignSyncService.shared().localFiles(() -> {
                    service.delete(target);
                    return true;
                }).whenComplete((ok, error) -> javafx.application.Platform.runLater(() -> {
                    sceneRoot.setDisable(false);
                    if (error == null) refreshAll();
                    else showError("Could not delete storyline files", error);
                }));
            }
        });
    }

    /** Lists every valid destination folder right in the menu; choosing one moves the item. */
    private void fillMoveMenu(MenuButton menu) {
        menu.getItems().clear();
        Path source = selectedPath();
        if (source == null || source.equals(service.getRoot())) return;
        List<FolderOption> options = new ArrayList<>();
        collectFolderOptions(service.getRoot(), source, "Storyline", options);
        Path currentParent = source.getParent();
        for (FolderOption opt : options) {
            boolean here = opt.path.equals(currentParent);
            MenuItem item = new MenuItem((here ? "● " : "") + opt.label);
            item.setDisable(here);
            item.setOnAction(e -> {
                try {
                    service.move(source, opt.path);
                    refreshAll();
                } catch (RuntimeException ex) {
                    showError(ex.getMessage());
                }
            });
            menu.getItems().add(item);
        }
        if (menu.getItems().isEmpty()) {
            MenuItem none = new MenuItem("No valid destination folder");
            none.setDisable(true);
            menu.getItems().add(none);
        }
    }

    /** Collects every folder under {@code folder} as a candidate move destination, excluding {@code excludeSubtree} itself and its descendants. */
    private void collectFolderOptions(Path folder, Path excludeSubtree, String label, List<FolderOption> out) {
        if (folder.equals(excludeSubtree) || folder.startsWith(excludeSubtree)) return;
        out.add(new FolderOption(folder, label));
        for (Path child : service.listChildren(folder)) {
            if (service.isFolder(child)) {
                collectFolderOptions(child, excludeSubtree, label + " / " + child.getFileName(), out);
            }
        }
    }

    private void openEditor(Path file) {
        if (service.isPrivate(file)) {
            PrivateFilesUi.unlocked(this, () -> {
                var account = uiSession.getPrivateAccount();
                if (!service.permissions(file).grants().containsKey(account.uid())) {
                    PrivateFilesUi.request(this, service, file);
                    return;
                }
                showEditor(file);
            });
        } else {
            showEditor(file);
        }
    }

    private void showEditor(Path file) {
        try {
            new StorylineEditorWindow(this, service.forEditor(), repos(), file).show();
            refreshAll();
        } catch (RuntimeException e) {
            showError(e.getMessage());
        }
    }

    private com.dnd.data.CampaignRepositories repos() {
        return new com.dnd.data.CampaignRepositories(uiSession.campaignRoot());
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        styleDialog(alert);
        alert.showAndWait();
    }

    private static final class FolderOption {
        final Path path;
        final String label;
        FolderOption(Path path, String label) { this.path = path; this.label = label; }
        @Override public String toString() { return label; }
    }
}
