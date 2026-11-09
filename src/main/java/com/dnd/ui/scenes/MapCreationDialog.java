package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.MapTemplates;
import com.dnd.data.WorldMaps;
import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.MapPin;
import com.dnd.ui.MapRenderer;
import com.dnd.ui.i18n.UiTranslator;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;

import java.util.Objects;
import java.util.function.Consumer;

final class MapCreationDialog {
    private MapCreationDialog() {}

    static void show(BaseScene owner, CampaignRepositories repos, Consumer<GameMap> created) {
        show(owner, repos, false, null, created);
    }

    static void show(BaseScene owner, CampaignRepositories repos, boolean world, Consumer<GameMap> created) {
        show(owner, repos, world, null, created);
    }

    /**
     * {@code world} creates a world map (blank 40 x 30 by default) for the World Map panel.
     * Otherwise it creates a location map, which must be pinned on a world map: the GM picks the
     * world map ({@code worldId} preselects one) and clicks the square where the location is.
     */
    static void show(BaseScene owner, CampaignRepositories repos, boolean world, String worldId,
                     Consumer<GameMap> created) {
        try {
            var templates = MapTemplates.builtIns();
            WorldMaps worlds = new WorldMaps(repos);
            var worldList = world ? java.util.List.<GameMap>of() : worlds.ensure();
            Dialog<GameMap> dialog = new Dialog<>();
            dialog.setTitle(world ? "Create World Map" : "Create Map from Template");
            dialog.setHeaderText(world ? "Choose a layout, then give this world or region its own unique name."
                : "Choose a layout, give this location its own unique name, then click where it is on a world map.");
            ComboBox<MapTemplates.Template> template = new ComboBox<>(FXCollections.observableArrayList(templates));
            template.setId("map-template");
            template.getSelectionModel().selectFirst();
            template.setMaxWidth(Double.MAX_VALUE);
            TextField name = new TextField();
            name.setId("map-location-name");
            name.setPromptText(world ? "World or region name, e.g. Northern Kingdoms" : "Location name, e.g. Queen's Castle");
            Spinner<Integer> width = new Spinner<>(GameMap.MIN_DIMENSION, GameMap.MAX_DIMENSION,
                world ? WorldMaps.DEFAULT_WIDTH : 20);
            Spinner<Integer> height = new Spinner<>(GameMap.MIN_DIMENSION, GameMap.MAX_DIMENSION,
                world ? WorldMaps.DEFAULT_HEIGHT : 20);
            Label size = new Label();
            Runnable updateSize = () -> {
                var layout = template.getValue().layout();
                boolean blank = layout == null;
                width.setDisable(!blank);
                height.setDisable(!blank);
                size.setText(blank ? "Choose the size of the blank map."
                    : "Template size: " + layout.getWidth() + " x " + layout.getHeight());
            };
            template.valueProperty().addListener((obs, old, now) -> updateSize.run());
            updateSize.run();
            Label error = new Label();
            error.setWrapText(true);
            VBox form = new VBox(10, new Label("Template:"), template, new Label("Map name:"), name,
                size, new Label("Width (squares):"), width, new Label("Height (squares):"), height);
            form.setPadding(new Insets(12));
            form.setMinWidth(300);

            ComboBox<GameMap> worldBox = new ComboBox<>(FXCollections.observableArrayList(worldList));
            int[] square = {-1, -1};
            Canvas preview = new Canvas();
            preview.setId("map-world-location");
            Label where = new Label();
            where.setWrapText(true);
            HBox content = new HBox(16, form);
            if (!world) {
                worldBox.setId("map-world");
                worldBox.setMaxWidth(Double.MAX_VALUE);
                worldBox.setConverter(new StringConverter<>() {
                    @Override public String toString(GameMap m) { return m == null ? "" : m.getName(); }
                    @Override public GameMap fromString(String s) { return null; }
                });
                UiTranslator.skip(worldBox);
                MapRenderer renderer = new MapRenderer(repos.campaignRoot());
                Runnable draw = () -> {
                    GameMap w = worldBox.getValue();
                    if (w == null) return;
                    w.ensureGridSize();
                    repos.maps().refreshPinNames(w);
                    double cell = Math.max(4, Math.min(16, 420.0 / Math.max(w.getWidth(), w.getHeight())));
                    renderer.setCellSize(cell);
                    preview.setWidth(w.getWidth() * cell);
                    preview.setHeight(w.getHeight() * cell);
                    renderer.render(preview, w, null);
                    if (square[0] >= 0) {
                        var g = preview.getGraphicsContext2D();
                        g.setStroke(Color.web("#ffd700"));
                        g.setLineWidth(3);
                        g.strokeRect(square[0] * cell, square[1] * cell, cell, cell);
                    }
                    where.setText(square[0] < 0 ? "Click the square where this location is."
                        : "Location: square (" + square[0] + "," + square[1] + ")");
                };
                worldBox.valueProperty().addListener((obs, old, now) -> {
                    square[0] = -1;
                    square[1] = -1;
                    draw.run();
                });
                preview.setOnMouseClicked(e -> {
                    GameMap w = worldBox.getValue();
                    if (w == null) return;
                    double cell = renderer.getCellSize();
                    int x = (int) (e.getX() / cell);
                    int y = (int) (e.getY() / cell);
                    if (x < 0 || y < 0 || x >= w.getWidth() || y >= w.getHeight()) return;
                    MapPin taken = w.linkAt(x, y);
                    if (taken != null) {
                        error.setText("That square already leads to " + taken.getName() + ". Choose another square.");
                        return;
                    }
                    error.setText("");
                    square[0] = x;
                    square[1] = y;
                    draw.run();
                });
                GameMap partyMap = worlds.partyMap();
                String startId = worldId != null ? worldId : partyMap != null ? partyMap.getId() : null;
                worldBox.setValue(worldList.stream().filter(m -> Objects.equals(m.getId(), startId))
                    .findFirst().orElse(worldList.get(0)));
                ScrollPane scroll = new ScrollPane(new Group(preview));
                scroll.setPrefViewportWidth(440);
                scroll.setPrefViewportHeight(340);
                VBox location = new VBox(10, new Label("On world map:"), worldBox, scroll, where);
                location.setPadding(new Insets(12));
                content.getChildren().add(location);
            }
            dialog.getDialogPane().setContent(new VBox(4, content, error));
            ButtonType create = new ButtonType("Create Map", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
            owner.styleDialog(dialog);
            dialog.getDialogPane().lookupButton(create).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                e.consume();
                try {
                    GameMap map;
                    if (world) {
                        map = worlds.create(name.getText(), template.getValue(), width.getValue(), height.getValue());
                    } else {
                        repos.maps().validateName(name.getText(), null);
                        if (worldBox.getValue() == null || square[0] < 0) {
                            throw new IllegalArgumentException("Click the square on the world map where this location is.");
                        }
                        map = worlds.createLocation(name.getText(), template.getValue(), width.getValue(),
                            height.getValue(), worldBox.getValue().getId(), square[0], square[1]);
                    }
                    dialog.setResult(map);
                    dialog.close();
                } catch (RuntimeException ex) {
                    error.setText("Could not create map: " + ex.getMessage());
                }
            });
            dialog.showAndWait().ifPresent(created);
        } catch (RuntimeException e) {
            owner.showError("Could not open map templates", e);
        }
    }
}
