package com.dnd.ui.scenes;

import com.dnd.data.CampaignPaths;
import com.dnd.data.CampaignRepositories;
import com.dnd.data.IdHandler;
import com.dnd.data.MapTemplates;
import com.dnd.data.WorldMaps;
import com.dnd.model.world.map.GameMap;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

final class MapCreationDialog {
    private MapCreationDialog() {}

    static void show(BaseScene owner, CampaignRepositories repos, Consumer<GameMap> created) {
        show(owner, repos, false, created);
    }

    /** {@code world} creates a world map (blank 40 x 30 by default) for the World Map panel. */
    static void show(BaseScene owner, CampaignRepositories repos, boolean world, Consumer<GameMap> created) {
        try {
            var templates = MapTemplates.builtIns();
            Dialog<GameMap> dialog = new Dialog<>();
            dialog.setTitle(world ? "Create World Map" : "Create Map from Template");
            dialog.setHeaderText(world ? "Choose a layout, then give this world or region its own unique name."
                : "Choose a layout, then give this location its own unique name.");
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
                size, new Label("Width (squares):"), width, new Label("Height (squares):"), height, error);
            form.setPadding(new Insets(12));
            dialog.getDialogPane().setContent(form);
            ButtonType create = new ButtonType("Create Map", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
            owner.styleDialog(dialog);
            dialog.getDialogPane().lookupButton(create).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                e.consume();
                try {
                    if (world) {
                        GameMap map = new WorldMaps(repos).create(name.getText(), template.getValue(),
                            width.getValue(), height.getValue());
                        dialog.setResult(map);
                        dialog.close();
                        return;
                    }
                    repos.maps().validateName(name.getText(), null);
                    var ids = new IdHandler(new CampaignPaths(repos.campaignRoot()).idRegistryFile());
                    String id = ids.generateId(name.getText().strip(), "world/maps.json");
                    GameMap map = MapTemplates.create(template.getValue(), id, name.getText().strip(),
                        width.getValue(), height.getValue());
                    repos.maps().add(map);
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
