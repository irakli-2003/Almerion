package com.dnd.ui.scenes;

import com.dnd.model.character.*;
import com.dnd.ui.ImageStore;
import com.dnd.ui.i18n.UiTranslator;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.StrokeLineJoin;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Parent-defined tiers inside named vertical branches, with routed prerequisite links. */
final class UpgradeTreeView extends Pane {
    static final double ICON_SIZE = 48;
    private static final double COLUMN = 240, WIDTH = 204, HEIGHT = 136, CHANNEL = 12;
    private record Section(UpgradeTree.Branch branch, double left, double width) { }
    private record Connection(String parent, String child, boolean adjacent) { }

    UpgradeTreeView(Path root, CharacterClass cls, PlayerCharacter pc, Consumer<UpgradeTree.Node> clicked) {
        setStyle("-fx-background-color: #101420;");
        UpgradeRules.validate(cls.getUpgradeTree());
        UpgradeTree tree = new UpgradeTree();
        tree.setBranches(cls.getUpgradeTree().getBranches());
        tree.setNodes(new ArrayList<>(cls.getUpgradeTree().getNodes()));
        var foundation = UpgradeRules.foundation(cls, pc);
        tree.getNodes().addFirst(foundation);
        Map<String, Integer> tiers = UpgradeRules.tiers(cls.getUpgradeTree());
        Map<String, UpgradeTree.Node> nodes = new LinkedHashMap<>();
        tree.getNodes().forEach(n -> nodes.put(n.getId(), n));
        List<Connection> connections = new ArrayList<>();
        for (var n : cls.getUpgradeTree().getNodes()) for (String parent : UpgradeRules.requiredNodes(n)) {
            connections.add(new Connection(parent, n.getId(), !UpgradeRules.ROOT.equals(parent)
                && tiers.get(n.getId()) == tiers.get(parent) + 1));
        }
        Map<Integer, List<Connection>> outgoingGaps = new HashMap<>(), incomingGaps = new HashMap<>();
        Map<String, List<Connection>> outgoing = new HashMap<>(), incoming = new HashMap<>();
        for (var edge : connections) {
            outgoing.computeIfAbsent(edge.parent(), id -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.child(), id -> new ArrayList<>()).add(edge);
            int sourceGap = tiers.get(edge.parent()), targetGap = tiers.get(edge.child()) - 1;
            outgoingGaps.computeIfAbsent(sourceGap, id -> new ArrayList<>()).add(edge);
            if (sourceGap != targetGap) incomingGaps.computeIfAbsent(targetGap, id -> new ArrayList<>()).add(edge);
        }
        int rootLinks = outgoing.getOrDefault(UpgradeRules.ROOT, List.of()).size();
        long longLinks = connections.stream().filter(e -> !e.adjacent() && !UpgradeRules.ROOT.equals(e.parent())).count();
        double margin = Math.max(40, (Math.max(rootLinks, longLinks) + 3) * CHANNEL);
        double sectionGap = Math.max(96, (rootLinks + 3) * CHANNEL);
        double sectionOffset = Math.max(60, 24 + rootLinks * 4);
        double firstChannels = sectionOffset + 80;
        int maxTier = tiers.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        double[] rowY = new double[maxTier + 1];
        rowY[1] = 24;
        for (int tier = 2; tier <= maxTier; tier++) {
            int gap = tier - 1;
            int count = outgoingGaps.getOrDefault(gap, List.of()).size() + incomingGaps.getOrDefault(gap, List.of()).size();
            double reserve = gap == 1 ? firstChannels : 36;
            rowY[tier] = rowY[gap] + HEIGHT + Math.max(gap == 1 ? 240 : 200, reserve + count * CHANNEL + 36);
        }
        double height = rowY[maxTier] + HEIGHT + Math.max(80,
            72 + outgoingGaps.getOrDefault(maxTier, List.of()).size() * CHANNEL);
        List<Section> sections = new ArrayList<>();
        double nextLeft = margin;
        for (var branch : tree.getBranches()) {
            int columns = 1;
            for (int tier = 2; tier <= maxTier; tier++) {
                int level = tier;
                columns = Math.max(columns, (int)tree.getNodes().stream().filter(n ->
                    Objects.equals(branch.getId(), n.getBranchId()) && tiers.get(n.getId()) == level).count());
            }
            double sectionWidth = columns * COLUMN + 80;
            sections.add(new Section(branch, nextLeft, sectionWidth));
            nextLeft += sectionWidth + sectionGap;
        }
        double width = sections.isEmpty() ? COLUMN + 2 * margin : nextLeft - sectionGap + margin;
        double sectionTop = rowY[1] + HEIGHT + sectionOffset;
        if (!sections.isEmpty()) height = Math.max(height, sectionTop + 160);
        Map<String, double[]> coordinates = new HashMap<>();
        coordinates.put(UpgradeRules.ROOT, new double[]{(width - WIDTH) / 2, rowY[1]});
        for (int tier = 2; tier <= maxTier; tier++) for (var section : sections) {
            int level = tier;
            var row = tree.getNodes().stream().filter(n -> Objects.equals(section.branch().getId(), n.getBranchId())
                && tiers.get(n.getId()) == level).sorted(Comparator.comparingDouble(n ->
                    coordinates.get(n.getParentId())[0])).toList();
            for (int i = 0; i < row.size(); i++) {
                coordinates.put(row.get(i).getId(), new double[]{section.left() + 40
                    + (i + 0.5) * (section.width() - 80) / row.size() - WIDTH / 2, rowY[tier]});
            }
        }
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.get(i);
            Region band = new Region();
            band.setManaged(false); band.setMouseTransparent(true);
            band.setId("upgrade-branch-" + section.branch().getId());
            band.setStyle("-fx-background-color: " + (i % 2 == 0 ? "#191b29" : "#172329")
                + "; -fx-border-color: #716044; -fx-background-radius: 12; -fx-border-radius: 12;");
            band.resizeRelocate(section.left(), sectionTop, section.width(), height - sectionTop - 24);
            Label heading = new Label(section.branch().getName());
            heading.setManaged(false); heading.setMouseTransparent(true); heading.setWrapText(true);
            heading.setAlignment(Pos.CENTER); UiTranslator.skip(heading);
            heading.setId("upgrade-branch-heading-" + section.branch().getId());
            heading.setStyle("-fx-text-fill: #e0c27b; -fx-font-weight: bold; -fx-font-size: 18px;");
            heading.resizeRelocate(section.left() + 40, sectionTop + 20, section.width() - 80, 52);
            getChildren().addAll(band, heading);
        }
        Comparator<Connection> bySource = Comparator.comparingDouble(e -> coordinates.get(e.parent())[0]);
        Comparator<Connection> byTarget = Comparator.comparingDouble(e -> coordinates.get(e.child())[0]);
        outgoing.values().forEach(list -> list.sort(byTarget));
        incoming.values().forEach(list -> list.sort(bySource));
        outgoingGaps.values().forEach(list -> list.sort(bySource.thenComparing(byTarget)));
        incomingGaps.values().forEach(list -> list.sort(byTarget.thenComparing(bySource)));
        List<Double> rootGutters = new ArrayList<>(List.of(margin / 2, width - margin / 2));
        for (int i = 0; i + 1 < sections.size(); i++) {
            var section = sections.get(i);
            rootGutters.add(section.left() + section.width() + sectionGap / 2);
        }
        double rootGutter = rootGutters.stream().min(Comparator.comparingDouble(x -> Math.abs(x - width / 2))).orElseThrow();
        int leftLane = 0, rightLane = 0;
        for (var connection : connections) {
            double[] start = coordinates.get(connection.parent()), end = coordinates.get(connection.child());
            var exits = outgoing.get(connection.parent());
            var entries = incoming.get(connection.child());
            double sx = start[0] + WIDTH * (exits.indexOf(connection) + 1) / (exits.size() + 1);
            double ex = end[0] + WIDTH * (entries.indexOf(connection) + 1) / (entries.size() + 1);
            double sy = start[1] + HEIGHT, ey = end[1];
            int sourceGap = tiers.get(connection.parent()), targetGap = tiers.get(connection.child()) - 1;
            double exitY = rowY[sourceGap] + HEIGHT + (sourceGap == 1 ? firstChannels : 36)
                + outgoingGaps.get(sourceGap).indexOf(connection) * CHANNEL;
            double entryY = sourceGap == targetGap ? exitY
                : rowY[targetGap] + HEIGHT + (targetGap == 1 ? firstChannels : 36)
                    + (outgoingGaps.getOrDefault(targetGap, List.of()).size()
                        + incomingGaps.get(targetGap).indexOf(connection)) * CHANNEL;
            Polyline edge;
            if (UpgradeRules.ROOT.equals(connection.parent())) {
                int index = exits.indexOf(connection);
                double gutter = rootGutter + (index - (rootLinks - 1) / 2.0) * CHANNEL;
                double forkY = sy + 12 + index * 4;
                edge = new Polyline(sx, sy, sx, forkY, gutter, forkY, gutter, entryY, ex, entryY, ex, ey);
            } else if (connection.adjacent()) {
                edge = new Polyline(sx, sy, sx, exitY, ex, exitY, ex, ey);
            } else {
                double gutter = ex < width / 2 ? margin - 20 - leftLane++ * CHANNEL
                    : width - margin + 20 + rightLane++ * CHANNEL;
                edge = new Polyline(sx, sy, sx, exitY, gutter, exitY, gutter, entryY, ex, entryY, ex, ey);
            }
            edge.setFill(null); edge.setStroke(Color.web("#d7b85b")); edge.setStrokeWidth(2);
            edge.setStrokeLineJoin(StrokeLineJoin.ROUND); edge.setMouseTransparent(true);
            edge.getProperties().put("source", connection.parent());
            edge.getProperties().put("target", connection.child());
            edge.getProperties().put("additional", !Objects.equals(connection.parent(), nodes.get(connection.child()).getParentId()));
            getChildren().add(edge);
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
            String branchName = earned.map(UpgradeProgress.Unlock::getBranchName).orElseGet(() ->
                tree.getBranches().stream().filter(b -> Objects.equals(b.getId(), n.getBranchId()))
                    .map(UpgradeTree.Branch::getName).findFirst().orElse(""));
            String displayedName = !branchName.isBlank() && nameText.startsWith(branchName + ": ")
                ? nameText.substring(branchName.length() + 2) : nameText;
            Label name = new Label(displayedName);
            name.setId("upgrade-node-name-" + n.getId());
            name.setWrapText(true);
            name.setMaxWidth(180);
            name.setMinHeight(0); name.setMaxHeight(36);
            UiTranslator.skip(name);
            name.setStyle("-fx-text-fill: #eeeeee; -fx-font-weight: bold; -fx-font-size: 12px;");
            Label status = new Label(UiTranslator.text("Level") + " " + n.getMinimumLevel() + "\n" + UiTranslator.text(state));
            status.setId("upgrade-node-status-" + n.getId());
            status.setMinHeight(0);
            UiTranslator.skip(status);
            status.setStyle("-fx-text-fill: #c8c8c8; -fx-font-size: 11px;");
            VBox face = new VBox(3, icon, name, status);
            face.setAlignment(Pos.CENTER);
            Button button = new Button();
            button.setManaged(false);
            button.setGraphic(face);
            button.setId("upgrade-node-" + n.getId());
            String color = unlocked ? "#70bc8a" : reason.isBlank() ? "#d7b85b" : "#596071";
            button.setStyle("-fx-background-color: #181c29; -fx-border-color: " + color + "; -fx-border-width: 2; -fx-background-radius: 8; -fx-border-radius: 8; -fx-padding: 6 4;");
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
            button.resizeRelocate(p[0], p[1], WIDTH, HEIGHT);
            getChildren().add(button);
        }
        setMinSize(width, height);
        setPrefSize(getMinWidth(), getMinHeight());
    }
}
