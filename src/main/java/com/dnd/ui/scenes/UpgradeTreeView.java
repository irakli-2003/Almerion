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

/** A rooted layered graph with spreading children and cross-path prerequisites. */
final class UpgradeTreeView extends Pane {
    static final double ICON_SIZE = 48;
    private static final double COLUMN = 224, HEIGHT = 136, GAP = 32;

    UpgradeTreeView(Path root, CharacterClass cls, PlayerCharacter pc, Consumer<UpgradeTree.Node> clicked) {
        setStyle("-fx-background-color: #101420;");
        UpgradeRules.validate(cls.getUpgradeTree());
        UpgradeTree tree = new UpgradeTree();
        tree.setBranches(cls.getUpgradeTree().getBranches());
        tree.setNodes(new ArrayList<>(cls.getUpgradeTree().getNodes()));
        var foundation = UpgradeRules.foundation(cls, pc);
        tree.getNodes().addFirst(foundation);
        Map<String, Integer> depths = new HashMap<>();
        Map<String, UpgradeTree.Node> nodes = new LinkedHashMap<>();
        tree.getNodes().forEach(n -> nodes.put(n.getId(), n));
        tree.getNodes().forEach(n -> depth(n, nodes, depths));
        int maxDepth = depths.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<List<UpgradeTree.Node>> rows = new ArrayList<>();
        for (int d = 0; d <= maxDepth; d++) rows.add(new ArrayList<>());
        tree.getNodes().forEach(n -> rows.get(depths.get(n.getId())).add(n));
        Map<String, Integer> branchOrder = new HashMap<>();
        for (int i = 0; i < tree.getBranches().size(); i++) branchOrder.put(tree.getBranches().get(i).getId(), i);
        rows.forEach(row -> row.sort(Comparator.comparingInt(n -> branchOrder.getOrDefault(n.getBranchId(), -1))));
        double width = Math.max(COLUMN, rows.stream().mapToInt(List::size).max().orElse(1) * COLUMN);
        Map<String, double[]> coordinates = new HashMap<>();
        for (int d = 0; d < rows.size(); d++) {
            var row = rows.get(d);
            for (int i = 0; i < row.size(); i++) {
                coordinates.put(row.get(i).getId(), new double[]{(i + 0.5) * width / row.size() - (COLUMN - 20) / 2,
                    30 + d * (HEIGHT + GAP)});
            }
        }
        for (var n : tree.getNodes()) {
            double[] end = coordinates.get(n.getId());
            List<String> parents = n.getPrerequisites().isEmpty() && !UpgradeRules.ROOT.equals(n.getId())
                ? List.of(UpgradeRules.ROOT) : n.getPrerequisites();
            for (String parent : parents) {
                double[] start = coordinates.get(parent);
                double sx = start[0] + (COLUMN - 20) / 2, sy = start[1] + HEIGHT;
                double ex = end[0] + (COLUMN - 20) / 2, ey = end[1];
                CubicCurve edge = new CubicCurve(sx, sy, sx, (sy + ey) / 2, ex, (sy + ey) / 2, ex, ey);
                edge.setFill(null);
                boolean crossBranch = !UpgradeRules.ROOT.equals(parent)
                    && !Objects.equals(nodes.get(parent).getBranchId(), n.getBranchId());
                edge.setStroke(Color.web(crossBranch ? "#708db4" : "#b69b5e"));
                edge.setStrokeWidth(crossBranch ? 1.5 : 2);
                if (crossBranch) edge.getStrokeDashArray().setAll(5.0, 4.0);
                edge.setMouseTransparent(true);
                getChildren().add(edge);
            }
        }
        for (var n : tree.getNodes()) {
            var earned = pc == null || pc.getUpgrades() == null ? Optional.<UpgradeProgress.Unlock>empty()
                : pc.getUpgrades().getUnlocks().stream().filter(u -> Objects.equals(cls.getId(), u.getClassId())
                    && Objects.equals(n.getId(), u.getNodeId())).findFirst();
            boolean isRoot = UpgradeRules.ROOT.equals(n.getId());
            boolean unlocked = isRoot || pc != null && UpgradeRules.selected(pc.getUpgrades(), cls.getId(), n.getId());
            String reason = pc == null ? "" : unlocked ? "Already unlocked" : UpgradeRules.blockedReason(pc, cls, n);
            String state = isRoot ? "Unlocked" : pc == null ? "Edit node" : unlocked ? "Unlocked" : reason.isBlank() ? "Available" : "Locked";
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
            String branchName = tree.getBranches().stream().filter(b -> Objects.equals(b.getId(), n.getBranchId()))
                .map(UpgradeTree.Branch::getName).findFirst().orElse("");
            Label status = new Label(UiTranslator.text(state) + " / " + n.getMinimumLevel()
                + (branchName.isBlank() ? "" : "\n" + branchName));
            UiTranslator.skip(status);
            status.setStyle("-fx-text-fill: #c8c8c8;");
            VBox face = new VBox(4, icon, name, status);
            face.setAlignment(Pos.CENTER);
            Button button = new Button();
            button.setManaged(false);
            button.setGraphic(face);
            button.setId("upgrade-node-" + n.getId());
            String color = unlocked ? "#70bc8a" : reason.isBlank() ? "#d7b85b" : "#596071";
            button.setStyle("-fx-background-color: #181c29; -fx-border-color: " + color + "; -fx-border-width: 2; -fx-background-radius: 8; -fx-border-radius: 8;");
            String reference = isRoot && pc != null && pc.getUpgrades() != null
                ? pc.getUpgrades().getUnlocks().stream().filter(u -> u.isHistorical()
                    || tree.getNodes().stream().noneMatch(existing -> Objects.equals(existing.getId(), u.getNodeId())
                        && Objects.equals(cls.getId(), u.getClassId())))
                    .map(u -> u.getName() + "\n" + Objects.toString(u.getDescription(), "")
                        + "\n" + UpgradeTreeWindow.rewardText(u.getReward())).collect(java.util.stream.Collectors.joining("\n\n"))
                : "";
            String details = nameText + "\n"
                + Objects.toString(earned.isPresent() ? earned.get().getDescription() : n.getDescription(), "")
                + "\n" + UpgradeTreeWindow.rewardText(earned.map(UpgradeProgress.Unlock::getReward).orElse(n.getReward()))
                + (reference.isBlank() ? "" : "\n\n" + reference)
                + (reason.isBlank() ? "" : "\n" + UiTranslator.text(reason));
            button.setTooltip(new Tooltip(details));
            if (isRoot) button.setOnAction(event -> {
                var summary = UpgradeRules.foundation(cls, pc);
                summary.setDescription(details);
                clicked.accept(summary);
            });
            else button.setOnAction(event -> clicked.accept(n));
            double[] p = coordinates.get(n.getId());
            button.resizeRelocate(p[0], p[1], COLUMN - 20, HEIGHT);
            getChildren().add(button);
        }
        setMinSize(width, 60 + (maxDepth + 1) * (HEIGHT + GAP));
        setPrefSize(getMinWidth(), getMinHeight());
    }

    private static int depth(UpgradeTree.Node node, Map<String, UpgradeTree.Node> nodes, Map<String, Integer> done) {
        if (done.containsKey(node.getId())) return done.get(node.getId());
        int value = node.getPrerequisites().stream().mapToInt(id -> depth(nodes.get(id), nodes, done) + 1)
            .max().orElse(UpgradeRules.ROOT.equals(node.getId()) ? 0 : 1);
        done.put(node.getId(), value);
        return value;
    }
}
