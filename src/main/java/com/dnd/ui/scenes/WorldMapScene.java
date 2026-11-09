package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.WorldMaps;
import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.MapPin;
import com.dnd.ui.EntityCategory;
import com.dnd.ui.MapRenderer;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import com.dnd.ui.i18n.UiTranslator;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * GM page "World Map" panel: the campaign's world maps, the doorways from them to location
 * maps, and the single square where the party currently is.
 */
public class WorldMapScene extends BaseScene {
    private enum Tool { NONE, LINK, UNLINK, PARTY }

    private CampaignRepositories repos;
    private WorldMaps worlds;
    private GameMap map;
    private ComboBox<GameMap> picker;
    private final Canvas canvas = new Canvas();
    private final MapRenderer renderer;
    private final Label status = new Label();
    private final Label partyLabel = new Label();
    private Tool tool = Tool.NONE;
    private GameMap linkTarget;

    public WorldMapScene(UiSession uiSession) {
        super(uiSession);
        renderer = new MapRenderer(uiSession.campaignRoot());
        renderer.setCellSize(32);
    }

    private static final StringConverter<GameMap> NAMES = new StringConverter<>() {
        @Override public String toString(GameMap m) {
            return m == null ? "" : (m.getName() == null || m.getName().isBlank() ? m.getId() : m.getName());
        }
        @Override public GameMap fromString(String s) { return null; }
    };

    @Override
    public Scene build() {
        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(SceneType.DM_MENU));
        if (uiSession.campaignRoot() == null) {
            root.getChildren().add(body("Open a campaign first."));
            return wrapInScene(root);
        }
        repos = new CampaignRepositories(uiSession.campaignRoot());
        worlds = new WorldMaps(repos);
        List<GameMap> all;
        try {
            all = worlds.ensure();
        } catch (RuntimeException e) {
            root.getChildren().add(body("World maps could not be loaded: " + friendlyError(e)));
            return wrapInScene(root);
        }

        picker = new ComboBox<>(FXCollections.observableArrayList(all));
        picker.setConverter(NAMES);
        picker.setId("world-map-picker");
        picker.setMinWidth(220);
        UiTranslator.skip(picker);

        HBox header = new HBox(10, title("🧭 World Map"), picker,
            btn("＋ New World Map...", this::createWorldMap),
            btn("Rename...", this::rename),
            btn("Edit Terrain", this::editTerrain),
            dangerBtn("Delete", this::deleteCurrent));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 20, 4, 20));

        ToggleGroup tools = new ToggleGroup();
        ToggleButton link = toolButton("📍 Link Location", "Click a square to make it lead to another map", tools, Tool.LINK);
        ToggleButton unlink = toolButton("✖ Remove Link", "Click a pin to remove it", tools, Tool.UNLINK);
        ToggleButton party = toolButton("🚩 Move Party", "Click the square where the party is now", tools, Tool.PARTY);
        tools.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                tool = Tool.NONE;
                linkTarget = null;
            }
        });
        link.setOnAction(e -> {
            if (!link.isSelected()) return;
            GameMap target = chooseLinkTarget();
            if (target == null) {
                link.setSelected(false);
                return;
            }
            linkTarget = target;
            tool = Tool.LINK;
            status.setText("Click the square that leads to " + NAMES.toString(target) + ". Esc cancels.");
        });
        unlink.setOnAction(e -> {
            tool = unlink.isSelected() ? Tool.UNLINK : Tool.NONE;
            if (unlink.isSelected()) status.setText("Click a pin to remove it. Esc cancels.");
        });
        party.setOnAction(e -> {
            tool = party.isSelected() ? Tool.PARTY : Tool.NONE;
            if (party.isSelected()) status.setText("Click the square where the party is now. Esc cancels.");
        });
        HBox toolbar = new HBox(8, link, unlink, party,
            btn("Hide Party", () -> run(() -> {
                worlds.hideParty();
                status.setText("The party marker is hidden.");
            })),
            btn("Find Party", this::findParty),
            new Separator(javafx.geometry.Orientation.VERTICAL),
            btn("−", () -> zoom(-8)), btn("+", () -> zoom(8)),
            partyLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 20, 8, 20));

        canvas.setOnMouseClicked(e -> {
            if (map == null) return;
            double cell = renderer.getCellSize();
            int cx = (int) (e.getX() / cell);
            int cy = (int) (e.getY() / cell);
            if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return;
            if (tool != Tool.NONE) {
                applyTool(cx, cy);
                tools.selectToggle(null);
                return;
            }
            MapPin pin = map.linkAt(cx, cy);
            if (pin == null) {
                status.setText("Square (" + cx + "," + cy + ")");
            } else if (e.getClickCount() == 2) {
                openPin(pin);
            } else {
                status.setText(pin.getName() + " - double-click to open.");
            }
        });

        ScrollPane scroll = new ScrollPane(new Group(canvas));
        scroll.setStyle("-fx-background: #1a1a2e; -fx-background-color: #1a1a2e;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        HBox statusBar = new HBox(status);
        statusBar.setPadding(new Insets(6, 20, 6, 20));
        statusBar.setStyle("-fx-background-color: #0f0f1e;");
        status.setText("Double-click a pin to open that location.");

        picker.valueProperty().addListener((obs, old, now) -> {
            tools.selectToggle(null);
            show(now);
        });
        GameMap start = selectedStart(all);
        picker.setValue(start);

        root.getChildren().addAll(header, toolbar, scroll, statusBar);
        Scene scene = wrapInScene(root);
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && tool != Tool.NONE) {
                tools.selectToggle(null);
                status.setText("Cancelled.");
                e.consume();
            }
        });
        return scene;
    }

    private ToggleButton toolButton(String text, String tip, ToggleGroup group, Tool kind) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(group);
        button.setTooltip(new Tooltip(tip));
        button.setId("world-tool-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        return button;
    }

    /** The world map last shown, else the one the party is on, else the first. */
    private GameMap selectedStart(List<GameMap> all) {
        String active = uiSession.getActiveMapId();
        for (GameMap m : all) if (Objects.equals(m.getId(), active)) return m;
        GameMap partyMap = worlds.partyMap();
        if (partyMap != null) {
            for (GameMap m : all) if (Objects.equals(m.getId(), partyMap.getId())) return m;
        }
        return all.get(0);
    }

    private void show(GameMap chosen) {
        if (chosen == null) return;
        map = repos.maps().getById(chosen.getId());
        if (map == null) return;
        uiSession.setActiveMapId(map.getId());
        map.ensureGridSize();
        repos.maps().refreshPinNames(map);
        double cell = renderer.getCellSize();
        canvas.setWidth(map.getWidth() * cell);
        canvas.setHeight(map.getHeight() * cell);
        renderer.render(canvas, map, null);
        GameMap partyMap = worlds.partyMap();
        partyLabel.setText(partyMap == null ? "Party: hidden"
            : "Party: " + NAMES.toString(partyMap) + " (" + partyMap.getPartyPosition().getX()
                + "," + partyMap.getPartyPosition().getY() + ")");
    }

    private void reload(String selectId) {
        List<GameMap> all = worlds.list();
        picker.getItems().setAll(all);
        GameMap select = all.stream().filter(m -> Objects.equals(m.getId(), selectId)).findFirst()
            .orElse(all.isEmpty() ? null : all.get(0));
        if (Objects.equals(picker.getValue() == null ? null : picker.getValue().getId(),
                select == null ? null : select.getId())) {
            show(select);
        }
        picker.setValue(select);
    }

    private void applyTool(int x, int y) {
        Tool used = tool;
        GameMap target = linkTarget;
        run(() -> {
            switch (used) {
                case PARTY -> {
                    worlds.moveParty(map.getId(), x, y);
                    status.setText("The party is now at (" + x + "," + y + ") on " + NAMES.toString(map) + ".");
                }
                case UNLINK -> status.setText(worlds.unlink(map.getId(), x, y)
                    ? "Link removed." : "There was no pin on that square.");
                case LINK -> {
                    MapPin pin = worlds.link(map.getId(), target.getId(), x, y);
                    status.setText("Linked to " + pin.getName() + ". Double-click the pin to open it.");
                }
                default -> { }
            }
        });
    }

    /** Runs a world change, then redraws; errors are shown instead of thrown. */
    private void run(Runnable change) {
        try {
            change.run();
        } catch (RuntimeException e) {
            status.setText("Could not save: " + friendlyError(e));
        }
        if (map != null) reload(map.getId());
    }

    private GameMap chooseLinkTarget() {
        List<GameMap> others = new ArrayList<>();
        for (GameMap m : repos.maps().list()) {
            if (m.getId() != null && !m.getId().equals(map.getId())) others.add(m);
        }
        others.sort(Comparator.comparing(NAMES::toString, String.CASE_INSENSITIVE_ORDER));
        ComboBox<GameMap> box = new ComboBox<>(FXCollections.observableArrayList(others));
        box.setConverter(NAMES);
        box.getSelectionModel().selectFirst();
        box.setMaxWidth(Double.MAX_VALUE);
        UiTranslator.skip(box);
        Dialog<GameMap> dialog = new Dialog<>();
        dialog.setTitle("Link Location");
        dialog.setHeaderText("Which map does this place lead to?");
        Button create = new Button("Create New Location Map...");
        create.setOnAction(e -> MapCreationDialog.show(this, repos, created -> {
            box.getItems().add(created);
            box.setValue(created);
        }));
        dialog.getDialogPane().setContent(new VBox(8, new Label("Map:"), box, create));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(bt -> bt == ButtonType.OK ? box.getValue() : null);
        styleDialog(dialog);
        return dialog.showAndWait().orElse(null);
    }

    private void openPin(MapPin pin) {
        GameMap target = repos.maps().getById(pin.getMapId());
        if (target == null) {
            status.setText("The linked map no longer exists. Use Remove Link to delete the pin.");
        } else if (target.isWorldMap()) {
            reload(target.getId());
        } else {
            new BattleMapWindow(this, uiSession, target.getId()).show();
        }
    }

    private void findParty() {
        GameMap partyMap = worlds.partyMap();
        if (partyMap == null) {
            status.setText("The party marker is hidden. Use Move Party to place it.");
        } else {
            reload(partyMap.getId());
        }
    }

    private void createWorldMap() {
        MapCreationDialog.show(this, repos, true, created -> reload(created.getId()));
    }

    private void rename() {
        if (map == null) return;
        TextInputDialog dialog = new TextInputDialog(map.getName());
        dialog.setTitle("Rename World Map");
        dialog.setHeaderText("Give this world map a unique name.");
        styleDialog(dialog);
        dialog.showAndWait().ifPresent(name -> run(() -> {
            repos.maps().validateName(name, map.getId());
            GameMap fresh = repos.maps().getById(map.getId());
            fresh.setName(name.strip());
            repos.maps().update(fresh);
            status.setText("Renamed.");
        }));
    }

    private void editTerrain() {
        if (map == null) return;
        uiSession.setActiveEntityCategory(EntityCategory.MAP);
        uiSession.setActiveMapId(map.getId());
        uiSession.setActiveEntityId(map.getId());
        uiSession.getRouter().goTo(SceneType.MAP_EDITOR);
    }

    private void deleteCurrent() {
        if (map == null) return;
        if (worlds.list().size() <= 1) {
            status.setText("Every campaign needs at least one world map. Create another one before deleting this.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete world map '" + NAMES.toString(map) + "'? Location maps it links to are kept.",
            ButtonType.OK, ButtonType.CANCEL);
        styleDialog(confirm);
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            String id = map.getId();
            try {
                repos.maps().delete(id);
                status.setText("World map deleted.");
                reload(null);
            } catch (RuntimeException e) {
                status.setText("Could not delete: " + friendlyError(e));
            }
        });
    }

    private void zoom(double delta) {
        double size = Math.max(12, Math.min(96, renderer.getCellSize() + delta));
        renderer.setCellSize(size);
        if (map != null) show(map);
    }
}
