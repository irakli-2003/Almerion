package com.dnd.ui.scenes;

import com.dnd.data.CampaignPaths;
import com.dnd.data.CampaignRepositories;
import com.dnd.data.IdHandler;
import com.dnd.data.MapTemplates;
import com.dnd.model.world.map.GameMap;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

final class MapCreationDialog {
    private MapCreationDialog() {}

    static void show(BaseScene owner, CampaignRepositories repos, Consumer<GameMap> created) {
        try {
            var templates = MapTemplates.builtIns();
            Dialog<GameMap> dialog = new Dialog<>();
            dialog.setTitle("Create Map from Template");
            dialog.setHeaderText("Choose a layout, then give this location its own unique name.");
            ComboBox<MapTemplates.Template> template = new ComboBox<>(FXCollections.observableArrayList(templates));
            template.setId("map-template");
            template.getSelectionModel().selectFirst();
            template.setMaxWidth(Double.MAX_VALUE);
            TextField name = new TextField();
            name.setId("map-location-name");
            name.setPromptText("Location name, e.g. Queen's Castle");
            Spinner<Integer> width = new Spinner<>(GameMap.MIN_DIMENSION, GameMap.MAX_DIMENSION, 20);
            Spinner<Integer> height = new Spinner<>(GameMap.MIN_DIMENSION, GameMap.MAX_DIMENSION, 20);
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
