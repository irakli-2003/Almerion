package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.world.map.*;
import com.dnd.ui.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public class MapViewScene extends BaseScene {

    public MapViewScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        CampaignRepositories repos = new CampaignRepositories(uiSession.campaignRoot());
        GameMap map = repos.maps().getById(uiSession.getActiveMapId());

        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(uiSession.isDm() ? SceneType.ENTITY_LIST : SceneType.PLAYER_HOME));

        if (map == null) {
            root.getChildren().add(body("Map not found."));
            return wrapInScene(root);
        }

        // Defensive: repair maps whose stored grid doesn't match their width/height (e.g. maps
        // created before GameMap.ensureGridSize() existed) so they can still be viewed here.
        map.ensureGridSize();
        repos.maps().refreshPinNames(map);

        HBox header = new HBox(12, title("Map: " + map.getName()));
        if (uiSession.isDm()) {
            header.getChildren().add(btn("Run Battle",
                () -> new BattleMapWindow(this, uiSession, map.getId()).show()));
        }
        header.setPadding(new Insets(10, 20, 0, 20));
        header.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(header);

        MapRenderer renderer = new MapRenderer(uiSession.campaignRoot());
        double cell = renderer.getCellSize();
        Canvas canvas = new Canvas(map.getWidth() * cell, map.getHeight() * cell);
        renderer.render(canvas, map, null);

        Label infoLabel = body("");
        canvas.setOnMouseClicked(e -> {
            int cx = (int) (e.getX() / cell);
            int cy = (int) (e.getY() / cell);
            if (cx >= 0 && cx < map.getWidth() && cy >= 0 && cy < map.getHeight()) {
                var link = map.linkAt(cx, cy);
                if (e.getClickCount() == 2 && link != null) {
                    if (repos.maps().getById(link.getMapId()) == null) {
                        infoLabel.setText("The linked map no longer exists.");
                    } else {
                        uiSession.setActiveMapId(link.getMapId());
                        uiSession.getRouter().goTo(SceneType.MAP_VIEW);
                    }
                    return;
                }
                GridCell gridCell = map.getCell(cx, cy);
                if (!gridCell.getOccupants().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (MapObject obj : gridCell.getOccupants()) sb.append(obj).append("  ");
                    infoLabel.setText("Cell (" + cx + "," + cy + "): " + sb);
                } else {
                    infoLabel.setText("Cell (" + cx + "," + cy + "): empty");
                }
            }
        });

        ScrollPane scroll = new ScrollPane(new Group(canvas));
        scroll.setStyle("-fx-background: #1a1a2e; -fx-background-color: #1a1a2e;");
        scroll.setPrefViewportWidth(860);
        scroll.setPrefViewportHeight(500);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        HBox infoBar = new HBox(infoLabel);
        infoBar.setPadding(new Insets(6, 20, 6, 20));
        infoBar.setStyle("-fx-background-color: #0f0f1e;");

        root.getChildren().addAll(scroll, infoBar);
        return wrapInScene(root);
    }
}
