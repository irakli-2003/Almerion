package com.dnd.ui.scenes.player;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.PlayerProfileStore;
import com.dnd.lan.LanSheet;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.ui.ImageStore;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import com.dnd.ui.scenes.BaseScene;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.io.ByteArrayInputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PlayerHomeScene extends BaseScene {
    public PlayerHomeScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().addAll(backBar(SceneType.PLAYER_ONLINE_SESSION),
            com.dnd.ui.scenes.PlayerModeUi.banner(this));

        VBox content = new VBox(18);
        content.setPadding(new Insets(20, 40, 40, 40));
        Label mapState = body("No map is shared at the moment");
        ImageView map = new ImageView();
        map.setPreserveRatio(true);
        map.setSmooth(true);
        map.fitWidthProperty().bind(content.widthProperty().subtract(80));
        VBox mapArea = new VBox(8, sectionLabel("Shared map"), mapState, map);
        mapArea.setMinHeight(240);
        VBox sheet = new VBox(12);
        content.getChildren().addAll(mapArea,
            new PlayerTurnPanel(uiSession, uiSession.getSession().getActivePlayerCharacterId()), sheet);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);
        refreshSheet(sheet);

        Scene scene = wrapInScene(root);
        var sync = uiSession.getSession().getFirebaseSync();
        if (sync != null && !sync.isHost()) {
            var executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "player-projector");
                thread.setDaemon(true);
                return thread;
            });
            final long[] lastFrame = {0};
            final String[] lastSnapshot = {null};
            javafx.animation.Timeline refresh = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.seconds(1), e -> {
                    if (lastFrame[0] != 0 && System.nanoTime() - lastFrame[0] > TimeUnit.SECONDS.toNanos(15)) {
                        map.setImage(null);
                        mapState.setText("No map is shared at the moment");
                        lastFrame[0] = 0;
                    }
                    String latest = sync.getAppliedSnapshot();
                    if (latest != null && !latest.equals(lastSnapshot[0])) {
                        lastSnapshot[0] = latest;
                        refreshSheet(sheet);
                    }
                }));
            refresh.setCycleCount(javafx.animation.Animation.INDEFINITE);
            refresh.play();
            executor.scheduleWithFixedDelay(() -> {
                try {
                    byte[] frame = sync.pullProjector();
                    Platform.runLater(() -> {
                        if (executor.isShutdown()) return;
                        if (frame == null) {
                            map.setImage(null);
                            mapState.setText("No map is shared at the moment");
                            lastFrame[0] = 0;
                        } else {
                            Image image = new Image(new ByteArrayInputStream(frame));
                            if (image.isError()) {
                                map.setImage(null);
                                mapState.setText("Could not display the shared map.");
                                lastFrame[0] = 0;
                            } else {
                                map.setImage(image);
                                mapState.setText("");
                                lastFrame[0] = System.nanoTime();
                            }
                        }
                    });
                } catch (RuntimeException error) {
                    Platform.runLater(() -> {
                        if (executor.isShutdown()) return;
                        map.setImage(null);
                        lastFrame[0] = 0;
                        mapState.setText("No map is shared at the moment. Could not check the session: " + error.getMessage());
                    });
                }
            }, 0, 3, TimeUnit.SECONDS);
            scene.windowProperty().addListener((o, oldWindow, newWindow) -> {
                if (oldWindow != null && newWindow == null) {
                    refresh.stop();
                    executor.shutdownNow();
                }
            });
        }
        return scene;
    }

    private void refreshSheet(VBox content) {
        CampaignRepositories repos = new CampaignRepositories(uiSession.campaignRoot());
        String id = uiSession.getSession().getActivePlayerCharacterId();
        String username = uiSession.isLoggedIn() ? uiSession.getCurrentUser().getUsername() : null;
        PlayerCharacter pc = PlayerVitalsPanel.availableCharacter(repos, id, username);
        content.getChildren().clear();
        if (pc == null) {
            content.getChildren().add(body("Character not found."));
            return;
        }
        PlayerProfileStore profiles = new PlayerProfileStore(
            uiSession.isLoggedIn() ? uiSession.getCurrentUser().getUsername() : null);
        profiles.applyTo(pc);
        JsonNode sheet = LanSheet.build(pc, repos, "", s -> s, com.dnd.data.JsonMappers.create());
        ImageView portrait = new ImageView();
        portrait.setFitWidth(180);
        portrait.setFitHeight(180);
        portrait.setPreserveRatio(true);
        Image image = ImageStore.load(profiles.hasOwnPortrait(pc.getId())
            ? profiles.imagesRoot() : uiSession.campaignRoot(), pc.getImagePath());
        if (image != null) portrait.setImage(image);
        VBox overview = new VBox(6, title(sheet.path("name").asText()),
            body(sheet.path("race").asText() + "  |  " + sheet.path("className").asText()
                + (sheet.path("subclass").asText().isBlank() ? "" : " (" + sheet.path("subclass").asText() + ")")
                + "  |  Level " + sheet.path("level").asText()),
            body("HP: " + sheet.path("hp").path("current").asText() + " / " + sheet.path("hp").path("max").asText()
                + "  |  AC: " + sheet.path("armorClass").asText()
                + "  |  Speed: " + sheet.path("speed").asText()
                + "  |  Initiative: " + sheet.path("initiative").asText()),
            body("XP: " + pc.getXp() + (pc.getMaxMana() > 0
                ? "  |  Mana: " + pc.getCurrentMana() + " / " + pc.getMaxMana() : "")),
            body("Proficiency: " + sheet.path("proficiency").asText()
                + "  |  Passive perception: " + sheet.path("passivePerception").asText()
                + "  |  Hit dice: " + sheet.path("hitDice").asText()));
        if (pc.getDescription() != null && !pc.getDescription().isBlank()) {
            Label description = body(pc.getDescription());
            description.setWrapText(true);
            overview.getChildren().add(description);
        }
        content.getChildren().add(new HBox(20, portrait, overview));
        addRows(content, "Ability scores", sheet.path("abilities"), n ->
            n.path("name").asText() + ": " + n.path("score").asText()
                + " (" + n.path("mod").asText() + "), save " + n.path("save").asText());
        addRows(content, "Skills", sheet.path("skills"), n ->
            n.path("name").asText() + ": " + n.path("bonus").asText());
        addRows(content, "Attacks", sheet.path("attacks"), n ->
            n.path("name").asText() + "  " + n.path("toHit").asText() + "  " + n.path("damage").asText());
        addRows(content, "Actions", sheet.path("actions"), PlayerHomeScene::detail);
        addRows(content, "Features", sheet.path("classFeatures"), PlayerHomeScene::detail);
        addRows(content, "Passive abilities", sheet.path("passives"), PlayerHomeScene::detail);
        addRows(content, "Spells / Abilities", sheet.path("spells"), PlayerHomeScene::detail);
        addRows(content, "Inventory", sheet.path("items"), n ->
            detail(n) + "  x" + n.path("quantity").asText() + (n.path("equipped").asBoolean() ? " (equipped)" : ""));
        addRows(content, "Spell slots", sheet.path("slots"), n -> n.path("label").asText() + ": "
            + n.path("current").asText() + " / " + n.path("max").asText());
        addRows(content, "Effects", sheet.path("effects"), PlayerHomeScene::detail);
        if (sheet.has("casting")) {
            JsonNode casting = sheet.path("casting");
            content.getChildren().addAll(sectionLabel("Spellcasting"),
                body(casting.path("ability").asText() + "  |  Save DC " + casting.path("saveDc").asText()
                    + "  |  Attack " + casting.path("attack").asText()));
        }
        addText(content, "Proficiencies and languages", sheet.path("proficiencies").asText());
        addText(content, "Additional features", sheet.path("features").asText());
        addText(content, "Notes", sheet.path("notes").asText());
        addText(content, "Backstory", pc.getBackstory());
        content.getChildren().add(new FlowPane(10, 10,
            btn("Edit My Profile", () -> uiSession.getRouter().goTo(SceneType.CHARACTER_PROFILE)),
            btn("Level up", () -> com.dnd.ui.scenes.UpgradeRequestsUi.show(this)),
            btn("Class Upgrade Tree", () -> com.dnd.ui.scenes.UpgradeTreeWindow.showCharacter(this, repos, pc, () -> {}, false)),
            com.dnd.ui.scenes.SessionChatUi.button(this),
            btn("Online Session", () -> uiSession.getRouter().goTo(SceneType.PLAYER_ONLINE_SESSION))));
    }

    private void addText(VBox content, String heading, String text) {
        if (text == null || text.isBlank()) return;
        Label label = body(text);
        label.setWrapText(true);
        com.dnd.ui.i18n.UiTranslator.skip(label);
        content.getChildren().addAll(sectionLabel(heading), label);
    }

    private static String detail(JsonNode node) {
        String description = node.path("description").asText();
        String uses = node.path("uses").asText();
        return node.path("name").asText() + (uses.isBlank() ? "" : " (" + uses + ")")
            + (description.isBlank() ? "" : " — " + description);
    }

    private void addRows(VBox content, String heading, JsonNode rows,
                         java.util.function.Function<JsonNode, String> format) {
        if (!rows.isArray() || rows.isEmpty()) return;
        content.getChildren().add(sectionLabel(heading));
        for (JsonNode row : rows) {
            Label label = body(format.apply(row));
            label.setWrapText(true);
            com.dnd.ui.i18n.UiTranslator.skip(label);
            content.getChildren().add(label);
        }
    }
}
