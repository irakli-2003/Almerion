package com.dnd.ui.scenes;

import com.dnd.model.character.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.shape.CubicCurve;
import javafx.stage.Stage;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class UpgradeTreeViewTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @BeforeClass public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException running) { ready.countDown(); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test public void nodesHaveFixedIconsClickableCardsAndNonOverlappingGraphEdges() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            Stage stage = new Stage();
            try {
                CharacterClass cls = new CharacterClass(); cls.setId("barbarian");
                var tree = cls.getUpgradeTree();
                var root = tree.getNodes().getFirst();
                var picture = new java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
                javax.imageio.ImageIO.write(picture, "png", temp.getRoot().toPath().resolve("node.png").toFile());
                root.setImagePath("node.png");
                var sibling = new UpgradeTree.Node();
                sibling.setId("sibling"); sibling.setName("Another root");
                sibling.setBranchId(root.getBranchId()); tree.getNodes().add(sibling);
                tree.getNodes().get(5).getPrerequisites().add(root.getId());
                AtomicReference<String> clicked = new AtomicReference<>();
                var graph = new UpgradeTreeView(temp.getRoot().toPath(), cls, null, n -> clicked.set(n.getId()));
                stage.setScene(new Scene(new javafx.scene.control.ScrollPane(graph), 900, 600));
                stage.show(); graph.applyCss(); graph.layout();
                var cards = graph.getChildren().stream().filter(n -> n instanceof Button).map(n -> (Button)n).toList();
                assertEquals(16, cards.size());
                assertEquals(24, graph.getChildren().stream().filter(n -> n instanceof CubicCurve).count());
                for (Button card : cards) {
                    assertEquals(204, card.getWidth(), 0.1);
                    assertEquals(136, card.getHeight(), 0.1);
                    ImageView icon = (ImageView) card.lookup(".image-view");
                    if (icon == null) {
                        var face = (javafx.scene.layout.VBox) card.getGraphic();
                        icon = (ImageView)((javafx.scene.layout.StackPane)face.getChildren().getFirst()).getChildren().getFirst();
                    }
                    assertEquals(48, icon.getFitWidth(), 0.1);
                    assertEquals(48, icon.getFitHeight(), 0.1);
                    if (card.getId().equals("upgrade-node-" + root.getId())) assertNotNull(icon.getImage());
                    for (Button other : cards) {
                        if (other != card) assertFalse(card.getId(), card.getBoundsInParent().intersects(other.getBoundsInParent()));
                    }
                }
                cards.stream().filter(b -> b.getId().equals("upgrade-node-" + root.getId())).findFirst().orElseThrow().fire();
                assertEquals(root.getId(), clicked.get());
                Button foundation = cards.stream().filter(b -> b.getId().equals("upgrade-node-" + UpgradeRules.ROOT))
                    .findFirst().orElseThrow();
                assertEquals((graph.getPrefWidth() - foundation.getWidth()) / 2, foundation.getLayoutX(), 0.1);
                assertTrue(cards.stream().filter(b -> b != foundation).allMatch(b -> b.getLayoutY() > foundation.getLayoutY()));
                assertEquals(4, cards.stream().filter(b -> !b.getId().equals("upgrade-node-sibling"))
                    .map(Button::getLayoutY).distinct().count());
                var pc = new PlayerCharacter();
                pc.setClassId("barbarian"); pc.setLevel(5);
                UpgradeRules.initialize(pc, cls);
                var migrated = new UpgradeTreeView(temp.getRoot().toPath(), cls, pc, n -> fail("Historical training cannot be unlocked twice."));
                var history = migrated.getChildren().stream().filter(n -> n instanceof Button b
                    && b.getId().startsWith("upgrade-node-saved-progression-")).map(n -> (Button)n).toList();
                assertEquals(0, history.size());
                var baseline = (Button) migrated.lookup("#upgrade-node-" + UpgradeRules.ROOT);
                assertNotNull(baseline);
                assertTrue(baseline.getTooltip().getText().contains("Existing level"));
                assertEquals(0, UpgradeRules.available(pc));
                var repos = new com.dnd.data.CampaignRepositories(java.nio.file.Path.of("src", "main", "resources",
                    "data", "custom-campaigns", "zurabaach"));
                var previewClass = repos.classes().getById("barbarian");
                var previewCharacter = repos.players().getById("darius");
                UpgradeRules.initialize(previewCharacter, previewClass);
                var preview = new UpgradeTreeView(repos.campaignRoot(), previewClass, previewCharacter, n -> {});
                var scene = new Scene(preview, preview.getPrefWidth(), preview.getPrefHeight());
                scene.getStylesheets().add(getClass().getResource("/com/dnd/ui/styles/dnd-theme.css").toExternalForm());
                stage.setScene(scene); stage.sizeToScene();
                preview.applyCss(); preview.resize(preview.getPrefWidth(), preview.getPrefHeight()); preview.layout();
                var snapshot = preview.snapshot(null, null);
                var output = new java.awt.image.BufferedImage((int)snapshot.getWidth(), (int)snapshot.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < output.getHeight(); y++) for (int x = 0; x < output.getWidth(); x++) {
                    output.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
                }
                var file = java.nio.file.Path.of("target", "test-ui", "upgrade-tree.png");
                java.nio.file.Files.createDirectories(file.getParent());
                javax.imageio.ImageIO.write(output, "png", file.toFile());
            } catch (Throwable ex) { error.set(ex); }
            finally { stage.close(); done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
