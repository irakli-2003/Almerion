package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.world.map.*;
import com.dnd.ui.*;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import com.dnd.ui.components.ImageFolders;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.*;

public class MapEditorScene extends BaseScene {

    private static final double CELL_SIZE = 48.0;
    /** Half-size (px) of a corner resize-handle hit box, centered on the corner point. */
    private static final double HANDLE = 8.0;

    /** Active canvas interaction mode, selected via the "Draw Tools" toggle buttons. */
    private enum Tool { SELECT, PEN, LINE, RECT, OVAL, ERASER, PASSABLE, TERRAIN }

    /** Lightweight display node for the "Map Objects" tree; key is a type-prefixed reference
     *  ("layer:<id>", "drawing:<id>", "group:<id>") resolved against the GameMap's lists. */
    private static final class ObjNode {
        final String key;
        final String label;
        ObjNode(String key, String label) { this.key = key; this.label = label; }
        @Override public String toString() { return label; }
    }

    private GameMap map;
    private CampaignRepositories repos;
    private Canvas canvas;
    private TreeView<ObjNode> objectsTree;

    // Unified selection: null, or "layer:<id>" / "drawing:<id>" / "group:<id>".
    private String selectedKey = null;

    // Move state (applies to whichever object selectedKey refers to).
    private boolean dragging = false;
    private double dragLastX, dragLastY;

    // Token drag state: a placed creature token picked up by pressing on it, following the
    // cursor (snapped to the grid) until it is released on another valid, empty box.
    private MapObject draggingToken;
    private int dragHoverCx = -1, dragHoverCy = -1;
    /** The placed token last clicked; "Remove Selected Token" and Delete act on it. */
    private MapObject selectedToken;
    private ContextMenu tokenMenu;

    // Resize state, anchored on the fixed opposite corner. Applies to layers, drawings and groups.
    private boolean resizeActive = false;
    private double anchorX, anchorY;

    // Drawing tool state
    private Tool currentTool = Tool.SELECT;
    /** Terrain painted by the TERRAIN tool; NORMAL erases back to ordinary ground. */
    private TerrainType brushTerrain = TerrainType.DIFFICULT;
    private Color drawColor = Color.web("#c9a84c");
    private boolean drawFilled = false;
    private double drawLineWidth = 2.0;
    /** Opacity given to newly drawn shapes; also the slider's resting value with nothing selected. */
    private double drawOpacity = 1.0;
    private javafx.scene.control.Slider opacitySlider;
    private final List<double[]> penPoints = new ArrayList<>();
    private boolean shapeDrawing = false;
    private double shapeStartX, shapeStartY, shapeCurX, shapeCurY;
    private int drawIdCounter = 0;
    private final Random rng = new Random();

    // Place-tokens panel controls, wired up so canvas clicks/drops can read the current selection
    private ComboBox<String> tokenTypeBox;
    private ListView<String> tokenEntityListView;

    public MapEditorScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        repos = new CampaignRepositories(uiSession.campaignRoot());
        map = repos.maps().getById(uiSession.getActiveMapId());

        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(SceneType.ENTITY_LIST));

        if (map == null) {
            root.getChildren().add(body("Map not found."));
            return wrapInScene(root);
        }

        // Defensive: repair maps whose stored grid doesn't match their width/height (e.g. maps
        // created before GameMap.ensureGridSize() existed) so they can still be opened here.
        map.ensureGridSize();

        HBox header = new HBox(12);
        header.setPadding(new Insets(8, 20, 4, 20));
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label heading = title("Map Editor: " + map.getName());
        header.getChildren().addAll(
            heading,
            btn("Rename Map...", () -> renameMap(heading)),
            btn("Save Map", this::saveMap),
            btn("View Map", () -> uiSession.getRouter().goTo(SceneType.MAP_VIEW))
        );
        root.getChildren().add(header);

        SplitPane split = new SplitPane();
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.28);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox leftPanel = buildLeftPanel();
        leftPanel.setMinWidth(220);
        ScrollPane leftScroll = new ScrollPane(leftPanel);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.setStyle("-fx-background-color: #12122a;");
        leftScroll.setMinWidth(220);

        double canvasW = map.getWidth() * CELL_SIZE;
        double canvasH = map.getHeight() * CELL_SIZE;
        canvas = new Canvas(canvasW, canvasH);
        setupCanvasInteraction();
        setupCanvasDragAndDrop();
        renderCanvas();

        ScrollPane canvasScroll = new ScrollPane(new Group(canvas));
        canvasScroll.setStyle("-fx-background-color: #1a1a2e;");

        split.getItems().addAll(leftScroll, canvasScroll);
        root.getChildren().add(split);
        Scene scene = wrapInScene(root);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                selectedToken = null;
                deselectAll();
                renderCanvas();
            } else if ((e.getCode() == javafx.scene.input.KeyCode.DELETE || e.getCode() == javafx.scene.input.KeyCode.BACK_SPACE)
                    && selectedToken != null && !(e.getTarget() instanceof javafx.scene.control.TextInputControl)) {
                removeToken(selectedToken);
                e.consume();
            }
        });
        return scene;
    }

    /** Clears the current object-tree/canvas selection (used by Esc and by clicking empty canvas). */
    private void deselectAll() {
        if (selectedKey == null && (objectsTree == null || objectsTree.getSelectionModel().getSelectedItems().isEmpty())) return;
        selectedKey = null;
        if (objectsTree != null) objectsTree.getSelectionModel().clearSelection();
        syncOpacitySlider();
        renderCanvas();
    }

    private VBox buildLeftPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: #12122a;");

        panel.getChildren().addAll(
            card(buildPlaceTokensSection()),
            card(buildDrawToolsSection()),
            card(buildObjectsTreeSection()));

        return panel;
    }

    private VBox card(VBox section) {
        section.getStyleClass().add("editor-card");
        return section;
    }

    private Label subLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sub-label");
        return l;
    }

    private Label hintLabel(String text) {
        Label hint = body(text);
        hint.setStyle("-fx-text-fill: #7a6a48; -fx-font-size: 11px; -fx-wrap-text: true;");
        return hint;
    }

    // ---------------------------------------------------------------------
    // Map Objects tree: layers, drawings, and groups; multi-select + grouping.
    // ---------------------------------------------------------------------

    private VBox buildObjectsTreeSection() {
        VBox box = new VBox(6);
        box.getChildren().add(sectionLabel("🖼 Map Objects (layers & drawings)"));

        TreeView<ObjNode> tree = new TreeView<>();
        tree.getStyleClass().add("dnd-list-view");
        tree.setPrefHeight(190);
        tree.setShowRoot(false);
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        objectsTree = tree;
        refreshObjectsTree();

        Button upBtn = btn("↑ Forward", () -> moveSelectedZ(1));
        Button downBtn = btn("↓ Back", () -> moveSelectedZ(-1));
        Button addLayerBtn = btn("+ Add Layer", this::openAddLayerDialog);
        Button deleteBtn = dangerBtn("Delete", () -> deleteSelected(tree));
        Button groupBtn = btn("Group Selected", () -> groupSelected(tree));
        Button ungroupBtn = btn("Ungroup", () -> ungroupSelected(tree));
        deleteBtn.setDisable(true);
        groupBtn.setDisable(true);
        ungroupBtn.setDisable(true);

        tree.getSelectionModel().getSelectedItems().addListener((ListChangeListener<TreeItem<ObjNode>>) c -> {
            List<TreeItem<ObjNode>> sel = tree.getSelectionModel().getSelectedItems();
            if (sel.size() == 1 && sel.get(0) != null && sel.get(0).getValue() != null) {
                selectedKey = sel.get(0).getValue().key;
            } else {
                selectedKey = null;
            }
            deleteBtn.setDisable(sel.isEmpty());
            groupBtn.setDisable(sel.size() < 2);
            ungroupBtn.setDisable(!(selectedKey != null && selectedKey.startsWith("group:")));
            syncOpacitySlider();
            renderCanvas();
        });

        Label hint = hintLabel("Shift+click / Ctrl+click to select multiple objects, then Group.");

        for (Button b : List.of(upBtn, downBtn, addLayerBtn, deleteBtn, groupBtn, ungroupBtn)) {
            b.setMaxWidth(Double.MAX_VALUE);
        }
        GridPane actions = new GridPane();
        actions.setHgap(6);
        actions.setVgap(4);
        actions.addRow(0, subLabel("Add / remove"), new Label());
        actions.addRow(1, addLayerBtn, deleteBtn);
        actions.addRow(2, subLabel("Stacking order"), new Label());
        actions.addRow(3, upBtn, downBtn);
        actions.addRow(4, subLabel("Grouping"), new Label());
        actions.addRow(5, groupBtn, ungroupBtn);
        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        actions.getColumnConstraints().addAll(half, half);

        box.getChildren().addAll(tree, actions, hint);
        return box;
    }

    private void refreshObjectsTree() {
        if (objectsTree == null) return;
        TreeItem<ObjNode> root = new TreeItem<>(new ObjNode("root", "root"));
        Set<String> nested = new HashSet<>();
        for (MapObjectGroup g : map.getGroups()) nested.addAll(g.getMemberKeys());

        // Top-level entries (not nested inside any group), ordered so the topmost row in the
        // tree is the most-forward object on the map - i.e. descending by shared z-order.
        List<TopLevelEntry> entries = new ArrayList<>();
        for (MapObjectGroup g : map.getGroups()) {
            if (!nested.contains("group:" + g.getId())) entries.add(new TopLevelEntry(g.getZOrder(), buildGroupItem(g)));
        }
        for (MapLayer l : map.getLayers()) {
            if (!nested.contains("layer:" + l.getId())) {
                entries.add(new TopLevelEntry(l.getZOrder(),
                    new TreeItem<>(new ObjNode("layer:" + l.getId(), "\uD83D\uDDBC " + l.getLabel()))));
            }
        }
        for (Drawing d : map.getDrawings()) {
            if (!nested.contains("drawing:" + d.getId())) {
                entries.add(new TopLevelEntry(d.getZOrder(),
                    new TreeItem<>(new ObjNode("drawing:" + d.getId(), drawingLabel(d)))));
            }
        }
        entries.sort((a, b) -> Integer.compare(b.zOrder, a.zOrder));
        for (TopLevelEntry entry : entries) root.getChildren().add(entry.item);

        objectsTree.setRoot(root);
        objectsTree.setShowRoot(false);
    }

    /** Pairs a top-level tree item with its shared z-order, purely to sort the tree display. */
    private record TopLevelEntry(int zOrder, TreeItem<ObjNode> item) { }

    private TreeItem<ObjNode> buildGroupItem(MapObjectGroup g) {
        TreeItem<ObjNode> item = new TreeItem<>(new ObjNode("group:" + g.getId(), "\uD83D\uDCC1 " + g.getLabel()));
        item.setExpanded(true);
        for (String key : g.getMemberKeys()) {
            TreeItem<ObjNode> child = buildItemForKey(key);
            if (child != null) item.getChildren().add(child);
        }
        return item;
    }

    private TreeItem<ObjNode> buildItemForKey(String key) {
        if (key.startsWith("layer:")) {
            MapLayer l = findLayer(key.substring(6));
            return l == null ? null : new TreeItem<>(new ObjNode(key, "\uD83D\uDDBC " + l.getLabel()));
        }
        if (key.startsWith("drawing:")) {
            Drawing d = findDrawing(key.substring(8));
            return d == null ? null : new TreeItem<>(new ObjNode(key, drawingLabel(d)));
        }
        if (key.startsWith("group:")) {
            MapObjectGroup g = findGroup(key.substring(6));
            return g == null ? null : buildGroupItem(g);
        }
        return null;
    }

    private String drawingLabel(Drawing d) {
        return "\u270F " + d.getType().name().toLowerCase() + " (" + d.getColor() + ")";
    }

    private MapLayer findLayer(String id) {
        return map.getLayers().stream().filter(l -> id.equals(l.getId())).findFirst().orElse(null);
    }

    private Drawing findDrawing(String id) {
        return map.getDrawings().stream().filter(d -> id.equals(d.getId())).findFirst().orElse(null);
    }

    private MapObjectGroup findGroup(String id) {
        return map.getGroups().stream().filter(g -> id.equals(g.getId())).findFirst().orElse(null);
    }

    private MapLayer selectedLayer() {
        if (selectedKey != null && selectedKey.startsWith("layer:")) return findLayer(selectedKey.substring(6));
        return null;
    }

    /** Reads the shared stacking z-order of any layer/drawing/group key, or 0 if unresolved. */
    private int zOrderOf(String key) {
        if (key == null) return 0;
        if (key.startsWith("layer:")) { MapLayer l = findLayer(key.substring(6)); return l != null ? l.getZOrder() : 0; }
        if (key.startsWith("drawing:")) { Drawing d = findDrawing(key.substring(8)); return d != null ? d.getZOrder() : 0; }
        if (key.startsWith("group:")) { MapObjectGroup g = findGroup(key.substring(6)); return g != null ? g.getZOrder() : 0; }
        return 0;
    }

    private void setZOrderOf(String key, int z) {
        if (key == null) return;
        if (key.startsWith("layer:")) { MapLayer l = findLayer(key.substring(6)); if (l != null) l.setZOrder(z); }
        else if (key.startsWith("drawing:")) { Drawing d = findDrawing(key.substring(8)); if (d != null) d.setZOrder(z); }
        else if (key.startsWith("group:")) { MapObjectGroup g = findGroup(key.substring(6)); if (g != null) g.setZOrder(z); }
    }

    /** Moves the currently selected object one step forward/back in the shared layer/drawing/group stack. */
    private void moveSelectedZ(int delta) {
        if (selectedKey == null) return;
        setZOrderOf(selectedKey, Math.max(0, zOrderOf(selectedKey) + delta));
        refreshObjectsTree();
        renderCanvas();
    }

    /** Highest z-order currently in use across layers/drawings/groups, plus one - so new objects
     *  default to appearing in front of everything else already on the map. */
    private int nextZOrder() {
        int max = -1;
        for (MapLayer l : map.getLayers()) max = Math.max(max, l.getZOrder());
        for (Drawing d : map.getDrawings()) max = Math.max(max, d.getZOrder());
        for (MapObjectGroup g : map.getGroups()) max = Math.max(max, g.getZOrder());
        return max + 1;
    }

    /** Bounding box (in px) of the object referenced by key, recursively for groups. Null if unresolved. */
    private double[] boundsOfKeyPx(String key) {
        if (key == null) return null;
        if (key.startsWith("layer:")) {
            MapLayer l = findLayer(key.substring(6));
            if (l == null) return null;
            return new double[]{l.getX() * CELL_SIZE, l.getY() * CELL_SIZE,
                (l.getX() + l.getWidth()) * CELL_SIZE, (l.getY() + l.getHeight()) * CELL_SIZE};
        }
        if (key.startsWith("drawing:")) {
            Drawing d = findDrawing(key.substring(8));
            if (d == null) return null;
            List<Double> gp = d.getPoints();
            if (gp.size() < 2) return null;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int i = 0; i + 1 < gp.size(); i += 2) {
                double px = gp.get(i) * CELL_SIZE, py = gp.get(i + 1) * CELL_SIZE;
                minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                minY = Math.min(minY, py); maxY = Math.max(maxY, py);
            }
            return new double[]{minX, minY, maxX, maxY};
        }
        if (key.startsWith("group:")) {
            MapObjectGroup g = findGroup(key.substring(6));
            if (g == null) return null;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            boolean any = false;
            for (String m : g.getMemberKeys()) {
                double[] b = boundsOfKeyPx(m);
                if (b == null) continue;
                any = true;
                minX = Math.min(minX, b[0]); maxX = Math.max(maxX, b[2]);
                minY = Math.min(minY, b[1]); maxY = Math.max(maxY, b[3]);
            }
            return any ? new double[]{minX, minY, maxX, maxY} : null;
        }
        return null;
    }

    /** Moves the object referenced by key by the given delta (in grid cells), recursing into groups. */
    private void translateKey(String key, double dxCells, double dyCells) {
        if (key == null) return;
        if (key.startsWith("layer:")) {
            MapLayer l = findLayer(key.substring(6));
            if (l != null) {
                l.setX(Math.max(0, l.getX() + dxCells));
                l.setY(Math.max(0, l.getY() + dyCells));
            }
        } else if (key.startsWith("drawing:")) {
            Drawing d = findDrawing(key.substring(8));
            if (d != null) {
                List<Double> pts = d.getPoints();
                for (int i = 0; i + 1 < pts.size(); i += 2) {
                    pts.set(i, pts.get(i) + dxCells);
                    pts.set(i + 1, pts.get(i + 1) + dyCells);
                }
            }
        } else if (key.startsWith("group:")) {
            MapObjectGroup g = findGroup(key.substring(6));
            if (g != null) {
                for (String member : g.getMemberKeys()) translateKey(member, dxCells, dyCells);
            }
        }
    }

    private void stripKeyFromAllGroups(String key) {
        for (MapObjectGroup g : map.getGroups()) g.getMemberKeys().remove(key);
    }

    private void deleteKeyCascade(String key) {
        if (key == null) return;
        if (key.startsWith("layer:")) {
            String id = key.substring(6);
            map.getLayers().removeIf(l -> id.equals(l.getId()));
        } else if (key.startsWith("drawing:")) {
            String id = key.substring(8);
            map.getDrawings().removeIf(d -> id.equals(d.getId()));
        } else if (key.startsWith("group:")) {
            String id = key.substring(6);
            MapObjectGroup g = findGroup(id);
            if (g != null) {
                List<String> members = new ArrayList<>(g.getMemberKeys());
                map.getGroups().removeIf(gr -> id.equals(gr.getId()));
                for (String m : members) deleteKeyCascade(m);
            }
        }
        stripKeyFromAllGroups(key);
    }

    private void deleteSelected(TreeView<ObjNode> tree) {
        List<String> keys = new ArrayList<>();
        for (TreeItem<ObjNode> item : tree.getSelectionModel().getSelectedItems()) {
            if (item != null && item.getValue() != null) keys.add(item.getValue().key);
        }
        if (keys.isEmpty()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete " + keys.size() + " selected map object(s)?", ButtonType.OK, ButtonType.CANCEL);
        styleDialog(confirm);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                for (String key : keys) deleteKeyCascade(key);
                selectedKey = null;
                refreshObjectsTree();
                renderCanvas();
            }
        });
    }

    private void groupSelected(TreeView<ObjNode> tree) {
        List<String> keys = new ArrayList<>();
        for (TreeItem<ObjNode> item : tree.getSelectionModel().getSelectedItems()) {
            if (item != null && item.getValue() != null && !keys.contains(item.getValue().key)) {
                keys.add(item.getValue().key);
            }
        }
        if (keys.size() < 2) return;
        TextInputDialog nameDialog = new TextInputDialog("Group");
        styleDialog(nameDialog);
        nameDialog.setHeaderText("Name this group");
        nameDialog.setTitle("Group Objects");
        Optional<String> res = nameDialog.showAndWait();
        if (res.isEmpty() || res.get().isBlank()) return;

        for (String k : keys) stripKeyFromAllGroups(k);
        int groupZ = keys.stream().mapToInt(this::zOrderOf).max().orElse(0);
        MapObjectGroup group = new MapObjectGroup("group_" + System.currentTimeMillis(), res.get().trim(), new ArrayList<>(keys));
        group.setZOrder(groupZ);
        map.getGroups().add(group);
        selectedKey = "group:" + group.getId();
        refreshObjectsTree();
        renderCanvas();
    }

    private void ungroupSelected(TreeView<ObjNode> tree) {
        TreeItem<ObjNode> item = tree.getSelectionModel().getSelectedItem();
        if (item == null || item.getValue() == null || !item.getValue().key.startsWith("group:")) return;
        String id = item.getValue().key.substring(6);
        MapObjectGroup g = findGroup(id);
        if (g == null) return;
        map.getGroups().removeIf(gr -> id.equals(gr.getId()));
        for (MapObjectGroup parent : map.getGroups()) {
            if (parent.getMemberKeys().remove("group:" + id)) {
                parent.getMemberKeys().addAll(g.getMemberKeys());
            }
        }
        selectedKey = null;
        refreshObjectsTree();
        renderCanvas();
    }

    /** Draw tools: shape selector, color picker, filled toggle, line width, and clear-all. */
    private VBox buildDrawToolsSection() {
        VBox box = new VBox(6);
        box.getChildren().add(sectionLabel("✎ Draw & Paint"));

        ToggleGroup toolGroup = new ToggleGroup();
        ToggleButton selectBtn = toolToggle("Select/Move", toolGroup);
        ToggleButton penBtn = toolToggle("Pen", toolGroup);
        ToggleButton lineBtn = toolToggle("Line", toolGroup);
        ToggleButton rectBtn = toolToggle("Rect", toolGroup);
        ToggleButton ovalBtn = toolToggle("Oval", toolGroup);
        ToggleButton eraserBtn = toolToggle("Eraser", toolGroup);
        ToggleButton passableBtn = toolToggle("Passable Toggle", toolGroup);
        ToggleButton terrainBtn = toolToggle("Terrain", toolGroup);
        selectBtn.setSelected(true);

        toolGroup.selectedToggleProperty().addListener((obs, old, sel) -> {
            if (sel == null) { selectBtn.setSelected(true); return; }
            if (sel == selectBtn) currentTool = Tool.SELECT;
            else if (sel == penBtn) currentTool = Tool.PEN;
            else if (sel == lineBtn) currentTool = Tool.LINE;
            else if (sel == rectBtn) currentTool = Tool.RECT;
            else if (sel == ovalBtn) currentTool = Tool.OVAL;
            else if (sel == eraserBtn) currentTool = Tool.ERASER;
            else if (sel == passableBtn) currentTool = Tool.PASSABLE;
            else if (sel == terrainBtn) currentTool = Tool.TERRAIN;
            penPoints.clear();
            shapeDrawing = false;
            renderCanvas();
        });

        FlowPane selectRow = new FlowPane(4, 4, selectBtn);
        FlowPane shapeRow = new FlowPane(4, 4, penBtn, lineBtn, rectBtn, ovalBtn, eraserBtn);
        FlowPane groundRow = new FlowPane(4, 4, passableBtn, terrainBtn);

        ChoiceBox<TerrainType> terrainBox = new ChoiceBox<>();
        terrainBox.getItems().setAll(TerrainType.values());
        terrainBox.setValue(brushTerrain);
        terrainBox.setMaxWidth(Double.MAX_VALUE);
        terrainBox.valueProperty().addListener((obs, old, val) -> {
            if (val != null) brushTerrain = val;
        });
        HBox terrainRow = new HBox(6, body("Terrain brush:"), terrainBox);
        terrainRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        ColorPicker colorPicker = new ColorPicker(drawColor);
        colorPicker.setMaxWidth(Double.MAX_VALUE);
        colorPicker.setOnAction(e -> drawColor = colorPicker.getValue());

        CheckBox filledCheck = checkBox("Filled shape (Rect/Oval)");
        filledCheck.selectedProperty().addListener((obs, old, val) -> drawFilled = val);

        Spinner<Integer> widthSpinner = new Spinner<>(1, 12, (int) drawLineWidth);
        widthSpinner.setEditable(true);
        widthSpinner.setMaxWidth(80);
        widthSpinner.valueProperty().addListener((obs, old, val) -> drawLineWidth = val);
        HBox widthRow = new HBox(6, body("Line width:"), widthSpinner);
        widthRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // New shapes take the opacity currently set here, and moving it with something
        // selected fades that object instead - one control covering both cases.
        opacitySlider = new Slider(0, 1, drawOpacity);
        opacitySlider.setMaxWidth(Double.MAX_VALUE);
        opacitySlider.setShowTickMarks(true);
        opacitySlider.setMajorTickUnit(0.25);
        Label opacityValue = body("100%");
        opacitySlider.valueProperty().addListener((obs, old, val) -> {
            double v = val.doubleValue();
            opacityValue.setText(Math.round(v * 100) + "%");
            if (selectedKey != null) applyOpacityToSelection(v);
            else drawOpacity = v;
        });
        HBox opacityRow = new HBox(6, body("Opacity:"), opacitySlider, opacityValue);
        opacityRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(opacitySlider, javafx.scene.layout.Priority.ALWAYS);

        Label hint = hintLabel("Select/Move: click tokens or objects; drag a corner handle to resize layers, shapes and groups. "
            + "Eraser removes the shape under your click. Passable flips a cell between open and wall (solid black). "
            + "Terrain paints the chosen ground; water and climbing cost extra movement unless a "
            + "creature can swim or climb, and difficult ground always costs double.");

        box.getChildren().addAll(
            subLabel("Pointer"), selectRow,
            subLabel("Shapes"), shapeRow,
            subLabel("Ground"), groundRow, terrainRow,
            subLabel("Style (new shapes / selected object)"), colorPicker, filledCheck, widthRow, opacityRow,
            hint);
        return box;
    }

    /** Fades whatever is selected, recursing into groups so a whole group dims together. */
    private void applyOpacityToSelection(double value) {
        applyOpacity(selectedKey, value);
        renderCanvas();
    }

    private void applyOpacity(String key, double value) {
        if (key == null) return;
        if (key.startsWith("layer:")) {
            MapLayer l = findLayer(key.substring(6));
            if (l != null) l.setOpacity(value);
        } else if (key.startsWith("drawing:")) {
            Drawing d = findDrawing(key.substring(8));
            if (d != null) d.setOpacity(value);
        } else if (key.startsWith("group:")) {
            MapObjectGroup g = findGroup(key.substring(6));
            if (g != null) for (String member : g.getMemberKeys()) applyOpacity(member, value);
        }
    }

    /** Reads the selected object's opacity back into the slider when the selection changes. */
    private void syncOpacitySlider() {
        if (opacitySlider == null) return;
        Double current = opacityOf(selectedKey);
        opacitySlider.setValue(current != null ? current : drawOpacity);
    }

    private Double opacityOf(String key) {
        if (key == null) return null;
        if (key.startsWith("layer:")) {
            MapLayer l = findLayer(key.substring(6));
            return l == null ? null : l.getOpacity();
        }
        if (key.startsWith("drawing:")) {
            Drawing d = findDrawing(key.substring(8));
            return d == null ? null : d.getOpacity();
        }
        if (key.startsWith("group:")) {
            MapObjectGroup g = findGroup(key.substring(6));
            if (g == null) return null;
            for (String member : g.getMemberKeys()) {
                Double found = opacityOf(member);
                if (found != null) return found;
            }
        }
        return null;
    }

    private ToggleButton toolToggle(String label, ToggleGroup group) {
        ToggleButton b = new ToggleButton(label);
        b.getStyleClass().add("tool-toggle-button");
        b.setToggleGroup(group);
        return b;
    }

    /** Place-tokens panel: pick an entity type + specific entity, then click or drag onto the map. */
    private VBox buildPlaceTokensSection() {
        VBox box = new VBox(6);
        box.getChildren().add(sectionLabel("♟ Tokens"));

        tokenTypeBox = new ComboBox<>();
        tokenTypeBox.getItems().addAll("Player", "NPC", "Monster", "Beast");
        tokenTypeBox.setValue("NPC");
        tokenTypeBox.setMaxWidth(Double.MAX_VALUE);
        tokenTypeBox.setStyle("-fx-background-color: #0f0f1e; -fx-text-fill: #d0c5a8;");

        tokenEntityListView = new ListView<>();
        tokenEntityListView.getStyleClass().add("dnd-list-view");
        tokenEntityListView.setPrefHeight(120);

        Runnable refreshEntities = () -> {
            tokenEntityListView.getItems().clear();
            switch (tokenTypeBox.getValue()) {
                case "Player"  -> repos.players().list().forEach(p -> tokenEntityListView.getItems().add(p.getId() + " | " + p.getName()));
                case "NPC"     -> repos.npcs().list().forEach(n -> tokenEntityListView.getItems().add(n.getId() + " | " + n.getName()));
                case "Monster" -> repos.monsters().list().forEach(m -> tokenEntityListView.getItems().add(m.getId() + " | " + m.getName()));
                case "Beast"   -> repos.beasts().list().forEach(b -> tokenEntityListView.getItems().add(b.getId() + " | " + b.getName()));
            }
        };
        tokenTypeBox.setOnAction(e -> refreshEntities.run());
        refreshEntities.run();

        // Drag support: drag a row onto the canvas to drop a token there.
        tokenEntityListView.setOnDragDetected(e -> {
            String sel = tokenEntityListView.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Dragboard db = tokenEntityListView.startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.putString(tokenTypeBox.getValue() + "::" + sel);
            db.setContent(content);
            e.consume();
        });

        Label clickHint = hintLabel("Pick an entity, then click an empty cell on the map (or drag it there).");
        Label editHint = hintLabel("Click a placed token to select it (gold ring), drag it to move it."
            + " Right-click it or press Delete to remove it.");

        Button removeTokenBtn = dangerBtn("Remove Selected Token", () -> {
            if (selectedToken == null) {
                Alert info = new Alert(Alert.AlertType.INFORMATION,
                    "Click a token on the map first (with the Select tool), then press this button.", ButtonType.OK);
                info.setHeaderText(null);
                styleDialog(info);
                info.showAndWait();
                return;
            }
            removeToken(selectedToken);
        });
        removeTokenBtn.setMaxWidth(Double.MAX_VALUE);

        box.getChildren().addAll(subLabel("Place new"), tokenTypeBox, tokenEntityListView, clickHint,
            subLabel("Placed tokens"), removeTokenBtn, editHint);
        return box;
    }

    /** Takes a placed token off the map after the DM confirms. */
    private void removeToken(MapObject token) {
        if (token == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Remove " + TokenSupport.nameOf(token) + " from this map?", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        styleDialog(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        map.removeObject(token);
        if (selectedToken == token) selectedToken = null;
        renderCanvas();
    }

    /** Dialog for adding a background layer: name, optional image OR solid color, and placement/size. */
    private void openAddLayerDialog() {
        Stage dialogStage = new Stage();
        com.dnd.ui.WindowOrder.adopt(dialogStage);
        dialogStage.initModality(Modality.APPLICATION_MODAL);
        dialogStage.setTitle("Add Map Layer");

        TextField labelField = textField("Layer name");
        File[] chosenFile = new File[1];
        Label fileLabel = body("No image selected.");
        fileLabel.setStyle("-fx-text-fill: #6a5a3a; -fx-font-size: 11px;");
        Button chooseBtn = btn("Choose Image...", () -> {
            FileChooser fc = ImageFolders.imageChooser("Select Layer Image");
            File chosen = fc.showOpenDialog(dialogStage);
            if (chosen != null) {
                chosenFile[0] = chosen;
                fileLabel.setText(chosen.getName());
                if (labelField.getText() == null || labelField.getText().isBlank()) {
                    labelField.setText(chosen.getName());
                }
            }
        });
        Button clearImageBtn = btn("No Image", () -> {
            chosenFile[0] = null;
            fileLabel.setText("No image selected.");
        });

        ColorPicker layerColorPicker = new ColorPicker();
        layerColorPicker.setMaxWidth(Double.MAX_VALUE);
        boolean[] colorPicked = {false};
        layerColorPicker.setOnAction(e -> colorPicked[0] = true);

        Spinner<Integer> xSpinner = new Spinner<>(0, GameMap.MAX_DIMENSION, 0);
        Spinner<Integer> ySpinner = new Spinner<>(0, GameMap.MAX_DIMENSION, 0);
        Spinner<Integer> wSpinner = new Spinner<>(1, GameMap.MAX_DIMENSION, map.getWidth());
        Spinner<Integer> hSpinner = new Spinner<>(1, GameMap.MAX_DIMENSION, map.getHeight());
        for (Spinner<Integer> s : List.of(xSpinner, ySpinner, wSpinner, hSpinner)) {
            s.setEditable(true);
            s.setMaxWidth(90);
        }

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, body("Name:"), labelField);
        grid.addRow(1, body("Image:"), new HBox(6, chooseBtn, clearImageBtn, fileLabel));
        grid.addRow(2, body("Color (if no image):"), layerColorPicker);
        grid.addRow(3, body("X (cells):"), xSpinner, body("Y (cells):"), ySpinner);
        grid.addRow(4, body("Width (cells):"), wSpinner, body("Height (cells):"), hSpinner);

        Label hint = body("Leave color unset with no image to get a random color.");
        hint.setStyle("-fx-text-fill: #6a5a3a; -fx-font-size: 11px;");

        Label errorLabel = body("");
        errorLabel.setStyle("-fx-text-fill: #cc4444; -fx-font-size: 11px;");

        Button addBtn = btn("Add Layer", () -> {
            if (labelField.getText() == null || labelField.getText().isBlank()) {
                errorLabel.setText("Please give the layer a name.");
                return;
            }
            try {
                String layerId = "layer_" + System.currentTimeMillis();
                String rel = null;
                String fillColorHex = null;
                if (chosenFile[0] != null) {
                    rel = ImageStore.copyImage(uiSession.campaignRoot(),
                        "maps/" + map.getId(), layerId, chosenFile[0]);
                } else {
                    Color chosen = colorPicked[0] ? layerColorPicker.getValue() : randomNonBlackColor();
                    fillColorHex = toHex(sanitizeColor(chosen));
                }
                int nextZ = nextZOrder();
                MapLayer newLayer = new MapLayer(layerId, labelField.getText().trim(), rel,
                    xSpinner.getValue(), ySpinner.getValue(), wSpinner.getValue(), hSpinner.getValue(), nextZ);
                newLayer.setFillColor(fillColorHex);
                map.getLayers().add(newLayer);
                refreshObjectsTree();
                renderCanvas();
                dialogStage.close();
            } catch (Exception ex) {
                errorLabel.setText("Upload failed: " + ex.getMessage());
            }
        });
        Button cancelBtn = btn("Cancel", dialogStage::close);

        HBox buttons = new HBox(10, addBtn, cancelBtn);
        buttons.setPadding(new Insets(8, 0, 0, 0));

        VBox layout = new VBox(10, grid, hint, errorLabel, buttons);
        layout.setPadding(new Insets(14));
        layout.getStyleClass().add("dialog-root");

        dialogStage.setScene(themedScene(layout, 480, 380));
        dialogStage.showAndWait();
    }

    /** Substitutes dark grey for any near-black color, since black is reserved for impassable cells. */
    private Color sanitizeColor(Color c) {
        if (c == null) return randomNonBlackColor();
        double brightness = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
        if (brightness < 0.12) return Color.web("#333333");
        return c;
    }

    private Color randomNonBlackColor() {
        double hue = rng.nextDouble() * 360;
        double sat = 0.5 + rng.nextDouble() * 0.4;
        double bri = 0.55 + rng.nextDouble() * 0.35;
        return Color.hsb(hue, sat, bri);
    }

    private void setupCanvasInteraction() {
        canvas.setOnMousePressed(e -> {
            double mx = e.getX(), my = e.getY();
            dragging = false;
            resizeActive = false;

            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                MapObject token = tokenAtCell((int) (mx / CELL_SIZE), (int) (my / CELL_SIZE));
                if (token != null) {
                    selectedToken = token;
                    renderCanvas();
                    MenuItem remove = new MenuItem("Remove " + TokenSupport.nameOf(token));
                    remove.setOnAction(ev -> removeToken(token));
                    if (tokenMenu != null) tokenMenu.hide();
                    tokenMenu = new ContextMenu(remove);
                    tokenMenu.show(canvas, e.getScreenX(), e.getScreenY());
                }
                return;
            }
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;

            switch (currentTool) {
                case PEN -> { penPoints.clear(); penPoints.add(new double[]{mx, my}); }
                case LINE, RECT, OVAL -> {
                    shapeStartX = mx; shapeStartY = my;
                    shapeCurX = mx; shapeCurY = my;
                    shapeDrawing = true;
                }
                case ERASER -> eraseAt(mx, my);
                case PASSABLE -> togglePassableAt(mx, my);
                case TERRAIN -> paintTerrainAt(mx, my);
                case SELECT -> {
                    int tcx = (int) (mx / CELL_SIZE);
                    int tcy = (int) (my / CELL_SIZE);
                    MapObject tokenHere = tokenAtCell(tcx, tcy);
                    if (tokenHere != null) {
                        selectedToken = tokenHere;
                        draggingToken = tokenHere;
                        dragHoverCx = tcx;
                        dragHoverCy = tcy;
                        break;
                    }
                    selectedToken = null;
                    double[] b = boundsOfKeyPx(selectedKey);
                    if (b != null) {
                        int corner = hitCorner(mx, my, b[0], b[1], b[2], b[3]);
                        if (corner != 0) {
                            beginResize(corner, b);
                        } else if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                            dragging = true;
                            dragLastX = mx; dragLastY = my;
                        } else {
                            // Clicked outside the selected object's bounds: deselect it.
                            deselectAll();
                        }
                    }
                }
            }
        });

        canvas.setOnMouseDragged(e -> {
            if (!e.isPrimaryButtonDown()) return;
            switch (currentTool) {
                case PEN -> { penPoints.add(new double[]{e.getX(), e.getY()}); renderCanvas(); }
                case TERRAIN -> paintTerrainAt(e.getX(), e.getY());
                case LINE, RECT, OVAL -> { shapeCurX = e.getX(); shapeCurY = e.getY(); renderCanvas(); }
                case SELECT -> {
                    if (draggingToken != null) {
                        dragHoverCx = (int) (e.getX() / CELL_SIZE);
                        dragHoverCy = (int) (e.getY() / CELL_SIZE);
                        renderCanvas();
                    } else if (resizeActive) {
                        resizeTo(e.getX() / CELL_SIZE, e.getY() / CELL_SIZE);
                        renderCanvas();
                    } else if (dragging && selectedKey != null) {
                        double dx = (e.getX() - dragLastX) / CELL_SIZE;
                        double dy = (e.getY() - dragLastY) / CELL_SIZE;
                        translateKey(selectedKey, dx, dy);
                        dragLastX = e.getX(); dragLastY = e.getY();
                        renderCanvas();
                    }
                }
                default -> { }
            }
        });

        canvas.setOnMouseReleased(e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            switch (currentTool) {
                case PEN -> {
                    if (penPoints.size() > 1) { commitDrawing(Drawing.Type.FREEHAND, new ArrayList<>(penPoints)); refreshObjectsTree(); }
                    penPoints.clear();
                    renderCanvas();
                }
                case LINE -> { commitShapeIfDrawing(Drawing.Type.LINE); refreshObjectsTree(); renderCanvas(); }
                case RECT -> { commitShapeIfDrawing(Drawing.Type.RECTANGLE); refreshObjectsTree(); renderCanvas(); }
                case OVAL -> { commitShapeIfDrawing(Drawing.Type.OVAL); refreshObjectsTree(); renderCanvas(); }
                case SELECT -> {
                    if (draggingToken != null) {
                        finishTokenDrag((int) (e.getX() / CELL_SIZE), (int) (e.getY() / CELL_SIZE));
                        renderCanvas();
                        break;
                    }
                    boolean wasActive = dragging || resizeActive;
                    MapLayer l = selectedLayer();
                    if (l != null) {
                        l.setX(Math.round(l.getX() * 2) / 2.0);
                        l.setY(Math.round(l.getY() * 2) / 2.0);
                        l.setWidth(Math.round(l.getWidth() * 2) / 2.0);
                        l.setHeight(Math.round(l.getHeight() * 2) / 2.0);
                    }
                    dragging = false;
                    resizeActive = false;
                    resizeOriginBox = null;
                    resizeLayerBoxes.clear();
                    resizeDrawingPoints.clear();
                    if (!wasActive) {
                        // Not a move/resize: treat as a click-to-place for the selected token.
                        placeSelectedTokenAt(e.getX(), e.getY());
                    }
                    renderCanvas();
                }
                default -> { }
            }
        });

        canvas.setOnScroll(e -> {
            MapLayer l = selectedLayer();
            if (l != null && e.isControlDown()) {
                double delta = e.getDeltaY() > 0 ? 0.5 : -0.5;
                l.setWidth(Math.max(1, l.getWidth() + delta));
                l.setHeight(Math.max(1, l.getHeight() + delta));
                renderCanvas();
                e.consume();
            }
        });
    }

    /** Returns which corner (1=NW,2=NE,3=SE,4=SW) of the box [x0,y0,x1,y1] the point is near, or 0. */
    private int hitCorner(double mx, double my, double x0, double y0, double x1, double y1) {
        if (near(mx, my, x0, y0)) return 1;
        if (near(mx, my, x1, y0)) return 2;
        if (near(mx, my, x1, y1)) return 3;
        if (near(mx, my, x0, y1)) return 4;
        return 0;
    }

    // ── Resizing ────────────────────────────────────────────────────────────

    /** The selection's geometry at the moment a resize started, in grid cells. */
    private double[] resizeOriginBox;
    /** Original layer boxes and drawing point lists, keyed by object key. */
    private final java.util.Map<String, double[]> resizeLayerBoxes = new java.util.HashMap<>();
    private final java.util.Map<String, List<Double>> resizeDrawingPoints = new java.util.HashMap<>();

    /**
     * Starts a resize from the grabbed corner.
     *
     * <p>The geometry is snapshotted first and every drag scales that snapshot rather than
     * the live object. Scaling the live object would compound rounding on every mouse move,
     * so a shape would visibly drift and distort while the DM was still dragging.</p>
     */
    private void beginResize(int corner, double[] boundsPx) {
        resizeActive = true;
        double x0 = boundsPx[0] / CELL_SIZE, y0 = boundsPx[1] / CELL_SIZE;
        double x1 = boundsPx[2] / CELL_SIZE, y1 = boundsPx[3] / CELL_SIZE;
        resizeOriginBox = new double[]{x0, y0, x1, y1};
        switch (corner) {
            case 1 -> { anchorX = x1; anchorY = y1; }
            case 2 -> { anchorX = x0; anchorY = y1; }
            case 3 -> { anchorX = x0; anchorY = y0; }
            case 4 -> { anchorX = x1; anchorY = y0; }
        }
        resizeLayerBoxes.clear();
        resizeDrawingPoints.clear();
        snapshotGeometry(selectedKey);
    }

    private void snapshotGeometry(String key) {
        if (key == null) return;
        if (key.startsWith("layer:")) {
            MapLayer l = findLayer(key.substring(6));
            if (l != null) resizeLayerBoxes.put(key, new double[]{l.getX(), l.getY(), l.getWidth(), l.getHeight()});
        } else if (key.startsWith("drawing:")) {
            Drawing d = findDrawing(key.substring(8));
            if (d != null) resizeDrawingPoints.put(key, new ArrayList<>(d.getPoints()));
        } else if (key.startsWith("group:")) {
            MapObjectGroup g = findGroup(key.substring(6));
            if (g != null) for (String member : g.getMemberKeys()) snapshotGeometry(member);
        }
    }

    /**
     * Resizes the selection so the grabbed corner follows the pointer while the opposite
     * corner stays put. Drawings scale point by point, which is what lets a freehand sketch
     * or a marked-out area be stretched rather than only image layers.
     */
    private void resizeTo(double mxCells, double myCells) {
        if (resizeOriginBox == null) return;
        double origW = Math.max(0.01, resizeOriginBox[2] - resizeOriginBox[0]);
        double origH = Math.max(0.01, resizeOriginBox[3] - resizeOriginBox[1]);
        double newW = Math.max(0.5, Math.abs(mxCells - anchorX));
        double newH = Math.max(0.5, Math.abs(myCells - anchorY));
        double newX = Math.max(0, mxCells < anchorX ? anchorX - newW : anchorX);
        double newY = Math.max(0, myCells < anchorY ? anchorY - newH : anchorY);
        applyResize(selectedKey, resizeOriginBox[0], resizeOriginBox[1], newX, newY, newW / origW, newH / origH);
    }

    private void applyResize(String key, double origX, double origY,
                             double newX, double newY, double scaleX, double scaleY) {
        if (key == null) return;
        if (key.startsWith("layer:")) {
            double[] box = resizeLayerBoxes.get(key);
            MapLayer l = findLayer(key.substring(6));
            if (l == null || box == null) return;
            l.setX(Math.max(0, newX + (box[0] - origX) * scaleX));
            l.setY(Math.max(0, newY + (box[1] - origY) * scaleY));
            l.setWidth(Math.max(0.5, box[2] * scaleX));
            l.setHeight(Math.max(0.5, box[3] * scaleY));
        } else if (key.startsWith("drawing:")) {
            List<Double> original = resizeDrawingPoints.get(key);
            Drawing d = findDrawing(key.substring(8));
            if (d == null || original == null) return;
            List<Double> scaled = new ArrayList<>(original.size());
            for (int i = 0; i + 1 < original.size(); i += 2) {
                scaled.add(Math.max(0, newX + (original.get(i) - origX) * scaleX));
                scaled.add(Math.max(0, newY + (original.get(i + 1) - origY) * scaleY));
            }
            d.setPoints(scaled);
        } else if (key.startsWith("group:")) {
            MapObjectGroup g = findGroup(key.substring(6));
            if (g != null) {
                for (String member : g.getMemberKeys()) applyResize(member, origX, origY, newX, newY, scaleX, scaleY);
            }
        }
    }

    private boolean near(double mx, double my, double px, double py) {
        return Math.abs(mx - px) <= HANDLE && Math.abs(my - py) <= HANDLE;
    }

    private void togglePassableAt(double px, double py) {
        int cx = (int) (px / CELL_SIZE);
        int cy = (int) (py / CELL_SIZE);
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return;
        GridCell cell = map.getCell(cx, cy);
        cell.setPassable(!cell.isPassable());
        renderCanvas();
    }

    /** Stamps the current terrain brush onto one cell; dragging repeats this per cell entered. */
    private void paintTerrainAt(double px, double py) {
        int cx = (int) (px / CELL_SIZE);
        int cy = (int) (py / CELL_SIZE);
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return;
        GridCell cell = map.getCell(cx, cy);
        if (cell.getTerrain() == brushTerrain) return;
        cell.setTerrain(brushTerrain);
        renderCanvas();
    }

    private void setupCanvasDragAndDrop() {
        canvas.setOnDragOver(e -> {
            if (e.getGestureSource() != canvas && e.getDragboard().hasString()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        canvas.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean success = false;
            if (db.hasString()) {
                String data = db.getString();
                int sep = data.indexOf("::");
                if (sep > 0) {
                    String type = data.substring(0, sep);
                    String entry = data.substring(sep + 2);
                    success = placeTokenAt(type, idFromListEntry(entry), e.getX(), e.getY());
                }
            }
            e.setDropCompleted(success);
            e.consume();
        });
    }

    private void commitShapeIfDrawing(Drawing.Type type) {
        if (!shapeDrawing) return;
        shapeDrawing = false;
        commitDrawing(type, List.of(new double[]{shapeStartX, shapeStartY}, new double[]{shapeCurX, shapeCurY}));
    }

    private void commitDrawing(Drawing.Type type, List<double[]> pixelPoints) {
        List<Double> gridPoints = new ArrayList<>();
        for (double[] p : pixelPoints) {
            gridPoints.add(p[0] / CELL_SIZE);
            gridPoints.add(p[1] / CELL_SIZE);
        }
        Drawing d = new Drawing("drawing_" + System.currentTimeMillis() + "_" + (drawIdCounter++),
            type, toHex(sanitizeColor(drawColor)), drawLineWidth, drawFilled, gridPoints);
        d.setZOrder(nextZOrder());
        d.setOpacity(drawOpacity);
        map.getDrawings().add(d);
    }

    private String toHex(Color c) {
        return String.format("#%02x%02x%02x",
            (int) Math.round(c.getRed() * 255),
            (int) Math.round(c.getGreen() * 255),
            (int) Math.round(c.getBlue() * 255));
    }

    /** Removes the top-most drawing whose bounding box (with a small hit-test margin) contains the click. */
    private void eraseAt(double mx, double my) {
        List<Drawing> list = map.getDrawings();
        for (int i = list.size() - 1; i >= 0; i--) {
            Drawing d = list.get(i);
            List<Double> gp = d.getPoints();
            if (gp.size() < 2) continue;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int j = 0; j + 1 < gp.size(); j += 2) {
                double px = gp.get(j) * CELL_SIZE, py = gp.get(j + 1) * CELL_SIZE;
                minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                minY = Math.min(minY, py); maxY = Math.max(maxY, py);
            }
            double pad = 6;
            if (mx >= minX - pad && mx <= maxX + pad && my >= minY - pad && my <= maxY + pad) {
                String key = "drawing:" + d.getId();
                list.remove(i);
                stripKeyFromAllGroups(key);
                if (key.equals(selectedKey)) selectedKey = null;
                refreshObjectsTree();
                renderCanvas();
                return;
            }
        }
    }

    private String idFromListEntry(String entry) {
        int idx = entry.indexOf(" | ");
        return idx >= 0 ? entry.substring(0, idx) : entry;
    }

    /** The first creature token (player/NPC/monster/beast) occupying a grid cell, if any. */
    private MapObject tokenAtCell(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return null;
        for (MapObject obj : map.getCell(cx, cy).getOccupants()) {
            if (TokenSupport.isCreature(obj)) return obj;
        }
        return null;
    }

    /** Drops the token picked up in {@link #draggingToken} onto the released-on box, if it is
     *  actually free; otherwise it stays exactly where it was, since it was never removed from
     *  its original box while being dragged. */
    private void finishTokenDrag(int cx, int cy) {
        MapObject token = draggingToken;
        draggingToken = null;
        dragHoverCx = -1;
        dragHoverCy = -1;
        if (token == null) return;
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return;
        GridCell target = map.getCell(cx, cy);
        boolean occupiedByOther = target.getOccupants().stream()
            .anyMatch(o -> o != token && TokenSupport.isCreature(o));
        if (!target.isPassable() || occupiedByOther) {
            return;
        }
        map.moveObject(token, cx, cy);
    }

    private void placeSelectedTokenAt(double px, double py) {
        if (tokenEntityListView == null) return;
        String sel = tokenEntityListView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        placeTokenAt(tokenTypeBox.getValue(), idFromListEntry(sel), px, py);
    }

    private boolean placeTokenAt(String type, String id, double px, double py) {
        int cx = (int) (px / CELL_SIZE);
        int cy = (int) (py / CELL_SIZE);
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return false;
        try {
            MapObject token = switch (type) {
                case "Player" -> {
                    var pc = repos.players().getById(id);
                    yield pc != null ? new PlayerToken(pc) : null;
                }
                case "NPC" -> {
                    var n = repos.npcs().getById(id);
                    yield n != null ? new NpcToken(n) : null;
                }
                case "Monster" -> {
                    var m = repos.monsters().getById(id);
                    yield m != null ? new MonsterToken(m) : null;
                }
                case "Beast" -> {
                    var b = repos.beasts().getById(id);
                    yield b != null ? new BeastToken(b) : null;
                }
                default -> null;
            };
            if (token == null) return false;
            map.placeObject(token, cx, cy);
            renderCanvas();
            return true;
        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Could not place token: " + ex.getMessage());
            styleDialog(alert);
            alert.showAndWait();
            return false;
        }
    }

    /** Renders layers, drawings, and groups together, sorted ascending by their shared z-order,
     *  so the "Map Objects" tree (sorted descending, top = front) and the canvas always agree. */
    private void renderObjectsUnified(GraphicsContext gc) {
        Set<String> nested = new HashSet<>();
        for (MapObjectGroup g : map.getGroups()) nested.addAll(g.getMemberKeys());

        List<String> topKeys = new ArrayList<>();
        for (MapLayer l : map.getLayers()) if (!nested.contains("layer:" + l.getId())) topKeys.add("layer:" + l.getId());
        for (Drawing d : map.getDrawings()) if (!nested.contains("drawing:" + d.getId())) topKeys.add("drawing:" + d.getId());
        for (MapObjectGroup g : map.getGroups()) if (!nested.contains("group:" + g.getId())) topKeys.add("group:" + g.getId());
        topKeys.sort(Comparator.comparingInt(this::zOrderOf));

        for (String key : topKeys) renderKey(gc, key);
    }

    private void renderKey(GraphicsContext gc, String key) {
        if (key.startsWith("layer:")) {
            MapLayer layer = findLayer(key.substring(6));
            if (layer != null) renderLayer(gc, layer);
        } else if (key.startsWith("drawing:")) {
            Drawing d = findDrawing(key.substring(8));
            if (d != null) renderDrawing(gc, d);
        } else if (key.startsWith("group:")) {
            MapObjectGroup g = findGroup(key.substring(6));
            if (g != null) {
                List<String> members = new ArrayList<>(g.getMemberKeys());
                members.sort(Comparator.comparingInt(this::zOrderOf));
                for (String m : members) renderKey(gc, m);
            }
        }
    }

    private void renderLayer(GraphicsContext gc, MapLayer layer) {
        double lx = layer.getX() * CELL_SIZE, ly = layer.getY() * CELL_SIZE;
        double lw = layer.getWidth() * CELL_SIZE, lh = layer.getHeight() * CELL_SIZE;
        boolean drewImage = false;
        gc.save();
        gc.setGlobalAlpha(layer.getOpacity());
        if (layer.getImagePath() != null && uiSession.campaignRoot() != null) {
            Image img = ImageStore.load(uiSession.campaignRoot(), layer.getImagePath());
            if (img != null) {
                gc.drawImage(img, lx, ly, lw, lh);
                drewImage = true;
            }
        }
        if (!drewImage) {
            if (layer.getFillColor() != null) {
                try { gc.setFill(Color.web(layer.getFillColor())); } catch (Exception ex) { gc.setFill(Color.web("#44444488")); }
            } else {
                gc.setFill(Color.web("#44444488"));
            }
            gc.fillRect(lx, ly, lw, lh);
        }
        gc.restore();
    }

    private void renderCanvas() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        gc.setFill(Color.web("#1a1a1a"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        renderObjectsUnified(gc);

        // Terrain tints sit above the artwork but below walls, so painted ground stays visible
        // without hiding the impassable cells the DM needs to spot first.
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                GridCell cell = map.getCell(x, y);
                if (cell == null || cell.getTerrain() == null || cell.getTerrain() == TerrainType.NORMAL) continue;
                gc.setFill(Color.web(switch (cell.getTerrain()) {
                    case WATER -> "#2a6fb055";
                    case CLIMB -> "#8a5a2b55";
                    case DIFFICULT -> "#6b5f2a55";
                    default -> "#00000000";
                }));
                gc.fillRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
            }
        }

        // Impassable cells render as solid black so the editor makes them visually obvious.
        gc.setFill(Color.BLACK);
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                GridCell cell = map.getCell(x, y);
                if (cell != null && !cell.isPassable()) {
                    gc.fillRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
                }
            }
        }

        gc.setStroke(Color.web("#ffffff22"));
        gc.setLineWidth(0.5);
        gc.setLineDashes();
        for (int x = 0; x <= map.getWidth(); x++)
            gc.strokeLine(x * CELL_SIZE, 0, x * CELL_SIZE, map.getHeight() * CELL_SIZE);
        for (int y = 0; y <= map.getHeight(); y++)
            gc.strokeLine(0, y * CELL_SIZE, map.getWidth() * CELL_SIZE, y * CELL_SIZE);

        // Live preview of whatever pen/shape drawing is currently in progress (not yet committed).
        if (currentTool == Tool.PEN && penPoints.size() > 1) {
            paintShape(gc, Drawing.Type.FREEHAND, penPoints, drawColor, drawLineWidth, false);
        } else if (shapeDrawing) {
            Drawing.Type previewType = currentTool == Tool.LINE ? Drawing.Type.LINE
                : currentTool == Tool.OVAL ? Drawing.Type.OVAL : Drawing.Type.RECTANGLE;
            paintShape(gc, previewType,
                List.of(new double[]{shapeStartX, shapeStartY}, new double[]{shapeCurX, shapeCurY}),
                drawColor, drawLineWidth, drawFilled);
        }

        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                GridCell cell = map.getCell(x, y);
                int slot = 0;
                for (MapObject obj : cell.getOccupants()) {
                    if (obj == draggingToken) { slot++; continue; }
                    drawToken(gc, obj, x, y, slot);
                    slot++;
                }
            }
        }

        // A token mid-drag renders at the box under the cursor instead of its original box, with
        // a highlight ring showing whether that box would actually accept it if released now.
        if (draggingToken != null && dragHoverCx >= 0 && dragHoverCy >= 0
                && dragHoverCx < map.getWidth() && dragHoverCy < map.getHeight()) {
            GridCell hoverCell = map.getCell(dragHoverCx, dragHoverCy);
            boolean valid = hoverCell.isPassable()
                && hoverCell.getOccupants().stream().noneMatch(o -> o != draggingToken && TokenSupport.isCreature(o));
            gc.setStroke(valid ? Color.web("#4caf50") : Color.web("#e53935"));
            gc.setLineWidth(2);
            gc.strokeRect(dragHoverCx * CELL_SIZE + 1, dragHoverCy * CELL_SIZE + 1, CELL_SIZE - 2, CELL_SIZE - 2);
            drawToken(gc, draggingToken, dragHoverCx, dragHoverCy, 0);
        }

        // The selected token gets a gold ring so it's clear what Delete / Remove will act on.
        if (selectedToken != null && draggingToken == null) {
            var pos = selectedToken.getPosition();
            boolean onMap = pos != null && pos.getX() >= 0 && pos.getY() >= 0
                && pos.getX() < map.getWidth() && pos.getY() < map.getHeight()
                && map.getCell(pos.getX(), pos.getY()).getOccupants().contains(selectedToken);
            if (onMap) {
                gc.setStroke(Color.web("#f0d080"));
                gc.setLineWidth(3);
                gc.strokeRect(pos.getX() * CELL_SIZE + 1.5, pos.getY() * CELL_SIZE + 1.5, CELL_SIZE - 3, CELL_SIZE - 3);
            } else {
                selectedToken = null;
            }
        }

        // Selection highlight (dashed box), with corner resize-handles on whatever is selected.
        double[] selBounds = boundsOfKeyPx(selectedKey);
        if (selBounds != null) {
            gc.setStroke(Color.web("#c9a84c"));
            gc.setLineWidth(2);
            gc.setLineDashes(6, 4);
            gc.strokeRect(selBounds[0], selBounds[1], selBounds[2] - selBounds[0], selBounds[3] - selBounds[1]);
            gc.setLineDashes();
            gc.setFill(Color.web("#c9a84c"));
            double hs = 10;
            double[][] corners = {
                {selBounds[0], selBounds[1]}, {selBounds[2], selBounds[1]},
                {selBounds[2], selBounds[3]}, {selBounds[0], selBounds[3]}
            };
            for (double[] c : corners) gc.fillRect(c[0] - hs / 2, c[1] - hs / 2, hs, hs);
        }
    }

    private void renderDrawing(GraphicsContext gc, Drawing d) {
        List<Double> gp = d.getPoints();
        List<double[]> pixelPts = new ArrayList<>();
        for (int i = 0; i + 1 < gp.size(); i += 2) {
            pixelPts.add(new double[]{gp.get(i) * CELL_SIZE, gp.get(i + 1) * CELL_SIZE});
        }
        Color c;
        try { c = Color.web(d.getColor()); } catch (Exception ex) { c = Color.web("#c9a84c"); }
        gc.save();
        gc.setGlobalAlpha(d.getOpacity());
        paintShape(gc, d.getType(), pixelPts, c, d.getLineWidth(), d.isFilled());
        gc.restore();
    }

    private void paintShape(GraphicsContext gc, Drawing.Type type, List<double[]> pts, Color color, double lineWidth, boolean filled) {
        if (pts.isEmpty()) return;
        gc.setStroke(color);
        gc.setFill(color);
        gc.setLineWidth(lineWidth);
        switch (type) {
            case FREEHAND -> {
                if (pts.size() < 2) return;
                for (int i = 1; i < pts.size(); i++) {
                    gc.strokeLine(pts.get(i - 1)[0], pts.get(i - 1)[1], pts.get(i)[0], pts.get(i)[1]);
                }
            }
            case LINE -> {
                double[] a = pts.get(0), b = pts.get(pts.size() - 1);
                gc.strokeLine(a[0], a[1], b[0], b[1]);
            }
            case RECTANGLE -> {
                double[] a = pts.get(0), b = pts.get(pts.size() - 1);
                double x = Math.min(a[0], b[0]), y = Math.min(a[1], b[1]);
                double w = Math.abs(b[0] - a[0]), h = Math.abs(b[1] - a[1]);
                if (filled) gc.fillRect(x, y, w, h); else gc.strokeRect(x, y, w, h);
            }
            case OVAL -> {
                double[] a = pts.get(0), b = pts.get(pts.size() - 1);
                double x = Math.min(a[0], b[0]), y = Math.min(a[1], b[1]);
                double w = Math.abs(b[0] - a[0]), h = Math.abs(b[1] - a[1]);
                if (filled) gc.fillOval(x, y, w, h); else gc.strokeOval(x, y, w, h);
            }
        }
    }

    private void drawToken(GraphicsContext gc, MapObject obj, int cx, int cy, int slot) {
        double bx = cx * CELL_SIZE + 4 + slot * 4;
        double by = cy * CELL_SIZE + 4 + slot * 4;
        double size = CELL_SIZE - 8 - slot * 4;
        if (size < 8) return;

        Color tokenColor;
        String label;
        String imgPath = null;

        if (obj instanceof PlayerToken pt) {
            tokenColor = Color.web("#4466aa"); label = pt.getCharacter() != null ? abbrev(pt.getCharacter().getName()) : "P";
            if (pt.getCharacter() != null) imgPath = pt.getCharacter().getImagePath();
        } else if (obj instanceof NpcToken nt) {
            tokenColor = Color.web("#66aa44"); label = nt.getNpc() != null ? abbrev(nt.getNpc().getName()) : "N";
            if (nt.getNpc() != null) imgPath = nt.getNpc().getImagePath();
        } else if (obj instanceof MonsterToken mt) {
            tokenColor = Color.web("#aa4444"); label = mt.getMonster() != null ? abbrev(mt.getMonster().getName()) : "M";
            if (mt.getMonster() != null) imgPath = mt.getMonster().getImagePath();
        } else {
            tokenColor = Color.web("#aaaa44"); label = obj.getSymbol();
        }

        boolean drewImage = false;
        if (imgPath != null && uiSession.campaignRoot() != null) {
            Image img = ImageStore.load(uiSession.campaignRoot(), imgPath);
            if (img != null) {
                gc.save();
                gc.beginPath();
                gc.arc(bx + size/2, by + size/2, size/2, size/2, 0, 360);
                gc.clip();
                gc.drawImage(img, bx, by, size, size);
                gc.restore();
                drewImage = true;
            }
        }
        if (!drewImage) {
            gc.setFill(tokenColor.deriveColor(0,1,0.5,0.85));
            gc.fillOval(bx, by, size, size);
            gc.setFill(Color.WHITE);
            gc.setFont(javafx.scene.text.Font.font("DejaVu Serif", javafx.scene.text.FontWeight.BOLD, size * 0.4));
            gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
            gc.setTextBaseline(javafx.geometry.VPos.CENTER);
            gc.fillText(label, bx + size/2, by + size/2);
        }
        gc.setStroke(tokenColor);
        gc.setLineWidth(1.5);
        gc.strokeOval(bx, by, size, size);
    }

    private String abbrev(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return String.valueOf(parts[0].charAt(0)).toUpperCase() + String.valueOf(parts[1].charAt(0)).toUpperCase();
    }

    private void saveMap() {
        try {
            repos.maps().save(map);
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Map saved.", ButtonType.OK);
            styleDialog(alert);
            alert.showAndWait();
        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Save failed: " + ex.getMessage());
            styleDialog(alert);
            alert.showAndWait();
        }

    }

    private void renameMap(Label heading) {
        TextInputDialog dialog = new TextInputDialog(map.getName());
        dialog.setTitle("Rename Map");
        dialog.setHeaderText("Give this location a unique name. Existing links will still work.");
        styleDialog(dialog);
        dialog.showAndWait().ifPresent(name -> {
            String previous = map.getName();
            try {
                repos.maps().validateName(name, map.getId());
                map.setName(name.strip());
                repos.maps().save(map);
                heading.setText("Map Editor: " + map.getName());
            } catch (RuntimeException e) {
                map.setName(previous);
                showError("Could not rename map", e);
            }
        });
    }
}
