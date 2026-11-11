package com.dnd.ui.scenes;

import com.dnd.model.character.*;
import com.dnd.data.JsonMappers;
import com.dnd.model.magic.Spell;
import com.dnd.ui.components.EntityForm;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polyline;
import javafx.stage.Stage;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import static org.junit.Assert.*;

public class UpgradeTreeViewTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @BeforeClass public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException running) { ready.countDown(); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test public void nodesHaveFixedIconsClickableCardsAndNonOverlappingGraphEdges() throws Exception {
        onFx(stage -> {
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
            assertEquals(20, graph.getChildren().stream().filter(n -> n instanceof Polyline).count());
            assertClearConnections(graph);
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
        });
    }

    @Test public void verticalSectionsGrowForUnlimitedChildrenAndRouteEveryKindOfPrerequisite() throws Exception {
        onFx(stage -> {
            var cls = new CharacterClass(); cls.setId("barbarian");
            var tree = cls.getUpgradeTree();
            var parent = tree.getNodes().getFirst();
            for (int i = 0; i < 24; i++) {
                var child = new UpgradeTree.Node();
                child.setId("child-" + i); child.setName("Custom child " + i);
                child.setBranchId("resilience"); child.setParentId(parent.getId());
                tree.getNodes().add(child);
            }
            tree.getNodes().stream().filter(n -> n.getId().equals("potency-2")).findFirst().orElseThrow()
                .getPrerequisites().addAll(java.util.List.of("resilience-3", "resilience-6"));
            tree.getNodes().stream().filter(n -> n.getId().equals("potency-6")).findFirst().orElseThrow()
                .getPrerequisites().add("resilience-1");
            var graph = new UpgradeTreeView(temp.getRoot().toPath(), cls, null, n -> {});
            stage.setScene(new Scene(new javafx.scene.control.ScrollPane(graph), 900, 600));
            stage.show(); graph.applyCss(); graph.layout();
            assertEquals(26, parent.getChildren().size());
            var bands = graph.getChildren().stream().filter(n -> n instanceof Region
                && n.getId() != null && n.getId().startsWith("upgrade-branch-")
                && !n.getId().startsWith("upgrade-branch-heading-")).map(n -> (Region)n).toList();
            assertEquals(2, bands.size());
            assertTrue(bands.getFirst().getBoundsInParent().getMaxX() < bands.getLast().getLayoutX());
            assertTrue(bands.getLast().getWidth() >= 26 * 240);
            for (var branch : tree.getBranches()) {
                var heading = (Label)graph.lookup("#upgrade-branch-heading-" + branch.getId());
                assertEquals(branch.getName(), heading.getText());
                var band = graph.lookup("#upgrade-branch-" + branch.getId()).getBoundsInParent();
                for (var node : tree.getNodes()) if (branch.getId().equals(node.getBranchId())) {
                    var card = (Button)graph.lookup("#upgrade-node-" + node.getId());
                    assertTrue(node.getId(), band.contains(card.getBoundsInParent()));
                    var name = (Label)card.lookup("#upgrade-node-name-" + node.getId());
                    assertFalse(name.getText().startsWith(branch.getName() + ": "));
                    var status = (Label)card.lookup("#upgrade-node-status-" + node.getId());
                    assertFalse(status.getText().contains(branch.getName()));
                    assertTrue(status.getText().contains(Integer.toString(node.getMinimumLevel())));
                }
            }
            var rowY = tree.getNodes().stream().map(n -> graph.lookup("#upgrade-node-" + n.getId()).getLayoutY())
                .distinct().sorted().toList();
            for (int i = 1; i < rowY.size(); i++) assertTrue(rowY.get(i) - rowY.get(i - 1) - 136 >= 200);
            assertClearConnections(graph);
        });
    }

    @Test public void controlZoomWorksFromOtherControlsWithBothKeyboardsAndSurvivesRefresh() throws Exception {
        onFx(stage -> {
            var cls = new CharacterClass(); cls.setId("barbarian");
            var graph = new UpgradeTreeView(temp.getRoot().toPath(), cls, null, n -> {});
            var pane = new DialogPane();
            var field = new TextField();
            var panel = UpgradeTreeWindow.graphPanel(graph, pane);
            pane.setContent(new VBox(panel, field));
            stage.setScene(new Scene(pane, 900, 600)); stage.show(); field.requestFocus();
            var zoom = (Slider)panel.lookup("#upgrade-tree-zoom");
            for (var code : java.util.List.of(KeyCode.PLUS, KeyCode.EQUALS, KeyCode.ADD)) {
                zoom.setValue(0.6); key(field, code, true, false);
                assertEquals(0.7, zoom.getValue(), 0.00001);
            }
            for (var code : java.util.List.of(KeyCode.MINUS, KeyCode.SUBTRACT)) {
                zoom.setValue(0.6); key(field, code, true, false);
                assertEquals(0.5, zoom.getValue(), 0.00001);
            }
            zoom.setValue(0.6);
            key(field, KeyCode.EQUALS, false, false); key(field, KeyCode.EQUALS, true, true);
            assertEquals(0.6, zoom.getValue(), 0.00001);
            zoom.setValue(zoom.getMax()); key(field, KeyCode.ADD, true, false);
            assertEquals(zoom.getMax(), zoom.getValue(), 0.00001);
            zoom.setValue(zoom.getMin()); key(field, KeyCode.SUBTRACT, true, false);
            assertEquals(zoom.getMin(), zoom.getValue(), 0.00001);
            assertEquals(zoom.getValue(), graph.getScaleX(), 0.00001);
            assertEquals(zoom.getValue(), graph.getScaleY(), 0.00001);
            zoom.setValue(0.65);
            var refreshed = UpgradeTreeWindow.graphPanel(new UpgradeTreeView(temp.getRoot().toPath(), cls, null, n -> {}), pane);
            pane.setContent(new VBox(refreshed, field));
            var active = (Slider)refreshed.lookup("#upgrade-tree-zoom");
            assertEquals(0.65, active.getValue(), 0.00001);
            key(field, KeyCode.EQUALS, true, false);
            assertEquals(0.75, active.getValue(), 0.00001);
            assertEquals(0.65, zoom.getValue(), 0.00001);
        });
    }

    @Test public void gmFormEditsUnlimitedChildrenAndPredefinedSpellAlternatives() throws Exception {
        onFx(stage -> {
            var n = new UpgradeTree.Node(); n.setId("test"); n.setName("Test");
            n.setBranchId("branch"); n.setParentId(UpgradeRules.ROOT);
            List<EntityForm.Ref> children = new ArrayList<>(), spells = new ArrayList<>();
            for (int i = 0; i < 18; i++) {
                children.add(new EntityForm.Ref("child-" + i, "Child " + i));
                spells.add(new EntityForm.Ref("spell-" + i, "Spell " + i));
                n.getChildren().add("child-" + i);
            }
            n.getReward().setSpellIds(List.of("spell-0"));
            var alternatives = new UpgradeTree.SpellAlternatives(); alternatives.setSpellId("spell-0");
            alternatives.setAlternativeSpellIds(spells.stream().skip(1).map(EntityForm.Ref::id).toList());
            n.getReward().setSpellAlternatives(List.of(alternatives));
            var form = EntityForm.of(UpgradeTree.Node.class, n, Set.of("id", "imagePath", "minimumLevel"), Map.of(
                "branchId", List.of(new EntityForm.Ref("branch", "Branch")),
                "parentId", List.of(new EntityForm.Ref(UpgradeRules.ROOT, "Foundation")),
                "children", children, "prerequisites", children, "exclusiveWith", children,
                "spellIds", spells, "spellId", spells, "alternativeSpellIds", spells));
            stage.setScene(new Scene(new ScrollPane(form.getNode()), 900, 600));
            stage.show(); form.getNode().applyCss(); ((Region)form.getNode()).layout();
            var labels = form.getNode().lookupAll(".label").stream().filter(node -> node instanceof Label)
                .map(node -> ((Label)node).getText()).toList();
            for (String expected : List.of("Parent Id", "Children", "Spell Alternatives", "Spell Id", "Alternative Spell Ids")) {
                assertTrue(expected + ": " + labels, labels.stream().anyMatch(text -> text.startsWith(expected)));
            }
            assertFalse(labels.contains("Minimum Level"));
            var edited = JsonMappers.create().convertValue(n, UpgradeTree.Node.class);
            form.writeTo(edited);
            assertEquals(UpgradeRules.ROOT, edited.getParentId());
            assertEquals(n.getChildren(), edited.getChildren());
            assertEquals(List.of("spell-0"), edited.getReward().getSpellIds());
            assertEquals(alternatives.getAlternativeSpellIds(),
                edited.getReward().getSpellAlternatives().getFirst().getAlternativeSpellIds());
        });
    }

    @Test public void spellDialogsOfferOnlyPredefinedOptionsAndWarnBeforeSkippingExhaustedRewards() throws Exception {
        onFx(stage -> {
            var primary = new Spell(); primary.setId("primary"); primary.setName("Primary"); primary.setLevel(1);
            var alternative = new Spell(); alternative.setId("alternative"); alternative.setName("Alternative"); alternative.setLevel(1);
            var reward = new UpgradeTree.Reward(); reward.setSpellIds(List.of("primary"));
            var predefined = new UpgradeTree.SpellAlternatives(); predefined.setSpellId("primary");
            predefined.setAlternativeSpellIds(List.of("alternative")); reward.setSpellAlternatives(List.of(predefined));
            var pc = new PlayerCharacter();
            var known = new PlayerCharacter.PlayerSpell(); known.setSpellId("primary");
            pc.setSpells(new ArrayList<>(List.of(known)));
            Map<String, Spell> spells = Map.of("primary", primary, "alternative", alternative);
            AtomicReference<Throwable> dialogError = new AtomicReference<>();
            var shown = new java.util.concurrent.atomic.AtomicInteger();
            BaseScene owner = new BaseScene(null) {
                @Override public Scene build() { return new Scene(new VBox()); }
                @Override protected void styleDialog(Dialog<?> dialog) {
                    super.styleDialog(dialog);
                    dialog.setOnShown(event -> Platform.runLater(() -> {
                        try {
                            int index = shown.getAndIncrement();
                            if (index == 0) {
                                assertTrue(dialog instanceof ChoiceDialog<?>);
                                var pick = (ChoiceDialog<?>)dialog;
                                assertEquals(1, pick.getItems().size());
                                assertEquals("alternative", ((EntityForm.Ref)pick.getItems().getFirst()).id());
                            } else {
                                assertTrue(dialog instanceof Alert);
                                assertEquals(Alert.AlertType.WARNING, ((Alert)dialog).getAlertType());
                                assertTrue(dialog.getDialogPane().getContentText().contains("No new spell will be learned"));
                            }
                            ((Button)dialog.getDialogPane().lookupButton(index == 1 ? ButtonType.CANCEL : ButtonType.OK)).fire();
                        } catch (Throwable ex) { dialogError.set(ex); dialog.close(); }
                    }));
                }
            };
            assertEquals(Map.of("primary", "alternative"),
                UpgradeTreeWindow.chooseSpellRewards(owner, pc, reward, spells).orElseThrow());
            var knownAlternative = new PlayerCharacter.PlayerSpell(); knownAlternative.setSpellId("alternative");
            pc.setSpells(new ArrayList<>(List.of(known, knownAlternative)));
            var before = JsonMappers.create().valueToTree(pc);
            assertTrue(UpgradeTreeWindow.chooseSpellRewards(owner, pc, reward, spells).isEmpty());
            assertEquals(before, JsonMappers.create().valueToTree(pc));
            assertEquals(Map.of("primary", "primary"),
                UpgradeTreeWindow.chooseSpellRewards(owner, pc, reward, spells).orElseThrow());
            assertEquals(3, shown.get());
            if (dialogError.get() != null) throw new AssertionError(dialogError.get());
        });
    }

    private static void key(javafx.scene.Node target, KeyCode code, boolean control, boolean alt) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, code == KeyCode.EQUALS, control, alt, false));
    }

    private static void assertClearConnections(UpgradeTreeView graph) {
        var cards = graph.getChildren().stream().filter(n -> n instanceof Button).map(n -> (Button)n).toList();
        var edges = graph.getChildren().stream().filter(n -> n instanceof Polyline).map(n -> (Polyline)n).toList();
        for (var edge : edges) {
            assertEquals(Color.web("#d7b85b"), edge.getStroke());
            assertTrue(edge.getStrokeDashArray().isEmpty());
            assertNotNull(edge.getProperties().get("source"));
            assertNotNull(edge.getProperties().get("target"));
            var points = edge.getPoints();
            for (int i = 0; i + 3 < points.size(); i += 2) {
                double x1 = points.get(i), y1 = points.get(i + 1), x2 = points.get(i + 2), y2 = points.get(i + 3);
                assertTrue(x1 == x2 || y1 == y2);
                assertTrue(x1 >= 0 && x1 <= graph.getPrefWidth() && y1 >= 0 && y1 <= graph.getPrefHeight());
                for (var card : cards) {
                    var bounds = card.localToParent(card.getLayoutBounds());
                    boolean crosses = x1 == x2
                        ? x1 > bounds.getMinX() + 0.5 && x1 < bounds.getMaxX() - 0.5
                            && Math.max(y1, y2) > bounds.getMinY() + 0.5 && Math.min(y1, y2) < bounds.getMaxY() - 0.5
                        : y1 > bounds.getMinY() + 0.5 && y1 < bounds.getMaxY() - 0.5
                            && Math.max(x1, x2) > bounds.getMinX() + 0.5 && Math.min(x1, x2) < bounds.getMaxX() - 0.5;
                    assertFalse(edge.getProperties() + " crosses " + card.getId() + " " + bounds
                        + " segment " + x1 + "," + y1 + " to " + x2 + "," + y2
                        + " card size " + card.getWidth() + "," + card.getHeight(), crosses);
                }
            }
        }
    }

    @FunctionalInterface private interface FxTest { void run(Stage stage) throws Exception; }

    private static void onFx(FxTest test) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            Stage stage = new Stage();
            try {
                test.run(stage);
            } catch (Throwable ex) { error.set(ex); }
            finally { stage.close(); done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
