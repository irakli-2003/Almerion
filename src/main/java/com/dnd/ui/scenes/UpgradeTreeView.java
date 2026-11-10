package com.dnd.ui.scenes;

import com.dnd.model.character.*;
import com.dnd.ui.ImageStore;
import com.dnd.ui.i18n.UiTranslator;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** A deterministic layered graph; branch columns and prerequisite edges are always visible. */
final class UpgradeTreeView extends Pane {
    static final double ICON_SIZE = 48;
    private static final double COLUMN = 224, HEIGHT = 136, GAP = 32;

    UpgradeTreeView(Path root, CharacterClass cls, PlayerCharacter pc, Consumer<UpgradeTree.Node> clicked) {
        UpgradeRules.validate(cls.getUpgradeTree());
        Set<String> history = new HashSet<>();
        UpgradeTree tree = withHistory(cls, pc, history);
        Map<String, Integer> depths = new HashMap<>();
        Map<String, UpgradeTree.Node> nodes = new LinkedHashMap<>();
        tree.getNodes().forEach(n -> nodes.put(n.getId(), n));
        tree.getNodes().forEach(n -> depth(n, nodes, depths));
        int maxDepth = depths.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<String, Integer> slots = new HashMap<>();
        int[] counts = new int[maxDepth + 1];
        Map<String, Integer> positions = new HashMap<>();
        for (var n : tree.getNodes()) {
            int d = depths.get(n.getId());
            String key = n.getBranchId() + ":" + d;
            int slot = slots.getOrDefault(key, 0);
            positions.put(n.getId(), slot);
            slots.put(key, slot + 1);
            counts[d] = Math.max(counts[d], slot + 1);
        }
        int[] starts = new int[maxDepth + 1];
        for (int d = 1; d <= maxDepth; d++) starts[d] = starts[d - 1] + counts[d - 1];
        Map<String, double[]> coordinates = new HashMap<>();
        for (int column = 0; column < tree.getBranches().size(); column++) {
            var branch = tree.getBranches().get(column);
            Label title = new Label(branch.getName());
            title.setManaged(false);
            UiTranslator.skip(title);
            title.setWrapText(true);
            title.setAlignment(Pos.CENTER);
            title.setStyle("-fx-text-fill: #dbb964; -fx-font-size: 16px; -fx-font-weight: bold;");
            title.resizeRelocate(column * COLUMN + 10, 4, COLUMN - 20, 64);
            getChildren().add(title);
            for (var n : tree.getNodes()) {
                if (!branch.getId().equals(n.getBranchId())) continue;
                double x = column * COLUMN + 10;
                double y = 80 + (starts[depths.get(n.getId())] + positions.get(n.getId())) * (HEIGHT + GAP);
                coordinates.put(n.getId(), new double[]{x, y});
            }
        }
        for (var n : tree.getNodes()) {
            double[] end = coordinates.get(n.getId());
            for (String parent : n.getPrerequisites()) {
                double[] start = coordinates.get(parent);
                double sx = start[0] + (COLUMN - 20) / 2, sy = start[1] + HEIGHT;
                double ex = end[0] + (COLUMN - 20) / 2, ey = end[1];
                CubicCurve edge = new CubicCurve(sx, sy, sx, (sy + ey) / 2, ex, (sy + ey) / 2, ex, ey);
                edge.setFill(null);
                edge.setStroke(Color.web("#9b8451"));
                edge.setStrokeWidth(2);
                edge.setMouseTransparent(true);
                getChildren().add(edge);
            }
        }
        for (var n : tree.getNodes()) {
            var earned = pc == null || pc.getUpgrades() == null ? Optional.<UpgradeProgress.Unlock>empty()
                : pc.getUpgrades().getUnlocks().stream().filter(u -> Objects.equals(cls.getId(), u.getClassId())
                    && Objects.equals(n.getId(), u.getNodeId())).findFirst();
            boolean unlocked = history.contains(n.getId())
                || pc != null && UpgradeRules.selected(pc.getUpgrades(), cls.getId(), n.getId());
            String reason = pc == null ? "" : unlocked ? "Already unlocked" : UpgradeRules.blockedReason(pc, cls, n);
            String state = pc == null ? "Edit node" : unlocked ? "Unlocked" : reason.isBlank() ? "Available" : "Locked";
            ImageView image = new ImageView(ImageStore.load(root,
                earned.isPresent() ? earned.get().getImagePath() : n.getImagePath()));
            image.setFitWidth(ICON_SIZE);
            image.setFitHeight(ICON_SIZE);
            image.setPreserveRatio(true);
            StackPane icon = new StackPane(image);
            icon.setMinSize(ICON_SIZE, ICON_SIZE);
            icon.setMaxSize(ICON_SIZE, ICON_SIZE);
            icon.setStyle("-fx-background-color: #242738; -fx-border-color: #75654b;");
            String nameText = earned.map(UpgradeProgress.Unlock::getName).orElse(n.getName());
            Label name = new Label(nameText);
            name.setWrapText(true);
            name.setMaxWidth(180);
            name.setMaxHeight(40);
            UiTranslator.skip(name);
            name.setStyle("-fx-text-fill: #eeeeee; -fx-font-weight: bold;");
            Label status = new Label(UiTranslator.text(state) + " / " + n.getMinimumLevel());
            status.setStyle("-fx-text-fill: #c8c8c8;");
            VBox face = new VBox(4, icon, name, status);
            face.setAlignment(Pos.CENTER);
            Button button = new Button();
            button.setManaged(false);
            button.setGraphic(face);
            button.setId("upgrade-node-" + n.getId());
            String color = unlocked ? "#70bc8a" : reason.isBlank() ? "#d7b85b" : "#596071";
            button.setStyle("-fx-background-color: #181c29; -fx-border-color: " + color + "; -fx-border-width: 2; -fx-background-radius: 8; -fx-border-radius: 8;");
            button.setTooltip(new Tooltip(nameText + "\n"
                + Objects.toString(earned.isPresent() ? earned.get().getDescription() : n.getDescription(), "")
                + "\n" + UpgradeTreeWindow.rewardText(earned.map(UpgradeProgress.Unlock::getReward).orElse(n.getReward()))
                + (reason.isBlank() ? "" : "\n" + UiTranslator.text(reason))));
            if (!history.contains(n.getId())) button.setOnAction(event -> clicked.accept(n));
            double[] p = coordinates.get(n.getId());
            button.resizeRelocate(p[0], p[1], COLUMN - 20, HEIGHT);
            getChildren().add(button);
        }
        setMinSize(Math.max(COLUMN, tree.getBranches().size() * COLUMN),
            100 + (starts[maxDepth] + Math.max(1, counts[maxDepth])) * (HEIGHT + GAP));
        setPrefSize(getMinWidth(), getMinHeight());
    }

    private static UpgradeTree withHistory(CharacterClass cls, PlayerCharacter pc, Set<String> history) {
        UpgradeTree tree = new UpgradeTree();
        tree.setBranches(new ArrayList<>(cls.getUpgradeTree().getBranches()));
        tree.setNodes(new ArrayList<>(cls.getUpgradeTree().getNodes()));
        if (pc == null || pc.getUpgrades() == null) return tree;
        var saved = pc.getUpgrades().getUnlocks().stream().filter(u ->
            !Objects.equals(u.getClassId(), cls.getId())
                || tree.getNodes().stream().noneMatch(n -> n.getId().equals(u.getNodeId()))).toList();
        if (saved.isEmpty()) return tree;
        var branch = new UpgradeTree.Branch();
        String branchId = "saved-progression";
        while (containsBranch(tree, branchId)) branchId += "-saved";
        branch.setId(branchId); branch.setName(UiTranslator.text("Saved progression"));
        tree.getBranches().add(branch);
        String parent = null;
        int index = 0;
        for (var u : saved) {
            String id = "saved-progression-" + index++;
            while (containsNode(tree, id)) id += "-saved";
            var n = new UpgradeTree.Node();
            n.setId(id); n.setBranchId(branch.getId()); n.setName(u.getName());
            n.setDescription(u.getDescription()); n.setImagePath(u.getImagePath());
            n.setMinimumLevel(Math.max(2, u.getLevel())); n.setReward(u.getReward());
            if (parent != null) n.getPrerequisites().add(parent);
            parent = id;
            tree.getNodes().add(n); history.add(id);
        }
        return tree;
    }

    private static boolean containsBranch(UpgradeTree tree, String id) {
        return tree.getBranches().stream().anyMatch(b -> b.getId().equals(id));
    }
    private static boolean containsNode(UpgradeTree tree, String id) {
        return tree.getNodes().stream().anyMatch(n -> n.getId().equals(id));
    }

    private static int depth(UpgradeTree.Node node, Map<String, UpgradeTree.Node> nodes, Map<String, Integer> done) {
        if (done.containsKey(node.getId())) return done.get(node.getId());
        int value = node.getPrerequisites().stream().mapToInt(id -> depth(nodes.get(id), nodes, done) + 1).max().orElse(0);
        done.put(node.getId(), value);
        return value;
    }
}
