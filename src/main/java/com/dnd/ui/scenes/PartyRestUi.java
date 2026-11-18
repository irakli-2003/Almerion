package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.Progression;
import javafx.beans.binding.Bindings;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

final class PartyRestUi {
    private PartyRestUi() {}

    private record Row(String id, CheckBox shortRest, CheckBox longRest, Spinner<Integer> hp) {}

    static void show(BaseScene owner, CampaignRepositories repos, Consumer<String> status) {
        List<PlayerCharacter> players = repos.players().list().stream()
            .sorted(Comparator.comparing(PlayerCharacter::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
        if (players.isEmpty()) {
            owner.showInfo("Party rest", "No player characters in this campaign.");
            return;
        }
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.addRow(0, owner.body("Character"), owner.body("Short rest"), owner.body("Long rest"),
            owner.body("HP rolled for short rest"));
        List<Row> rows = new ArrayList<>();
        for (PlayerCharacter pc : players) {
            CheckBox shortRest = new CheckBox();
            CheckBox longRest = new CheckBox();
            shortRest.setAccessibleText(pc.getName() + " — Short rest");
            longRest.setAccessibleText(pc.getName() + " — Long rest");
            Spinner<Integer> hp = new Spinner<>(0, Math.max(0, pc.getMaxHitPoints()), 0);
            hp.disableProperty().bind(shortRest.selectedProperty().not());
            shortRest.selectedProperty().addListener((o, a, selected) -> { if (selected) longRest.setSelected(false); });
            longRest.selectedProperty().addListener((o, a, selected) -> { if (selected) shortRest.setSelected(false); });
            rows.add(new Row(pc.getId(), shortRest, longRest, hp));
            grid.addRow(rows.size(), owner.body(pc.getName()), shortRest, longRest, hp);
        }
        Button allShort = owner.btn("Everyone has a short rest", () -> rows.forEach(r -> r.shortRest().setSelected(true)));
        Button allLong = owner.btn("Everyone has a long rest", () -> rows.forEach(r -> r.longRest().setSelected(true)));
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Party rest");
        ButtonType apply = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(apply, ButtonType.CANCEL);
        var choices = rows.stream().flatMap(r -> java.util.stream.Stream.of(
            r.shortRest().selectedProperty(), r.longRest().selectedProperty())).toArray(javafx.beans.Observable[]::new);
        dialog.getDialogPane().lookupButton(apply).disableProperty().bind(Bindings.createBooleanBinding(
            () -> rows.stream().noneMatch(r -> r.shortRest().isSelected() || r.longRest().isSelected()), choices));
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(350, (rows.size() + 1) * 40));
        dialog.getDialogPane().setContent(new VBox(10, new HBox(8, allShort, allLong), scroll));
        owner.styleDialog(dialog);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != apply || !owner.isCurrentRole()
                || !java.util.Objects.equals(owner.uiSession.campaignRoot(), repos.campaignRoot())) return;
        List<String> applied = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        var mapper = com.dnd.data.JsonMappers.forCampaign(repos.campaignRoot());
        for (Row row : rows) {
            if (!row.shortRest().isSelected() && !row.longRest().isSelected()) continue;
            try {
                var before = repos.players().getById(row.id());
                if (before == null) throw new IllegalStateException("Character no longer exists.");
                var after = mapper.convertValue(before, PlayerCharacter.class);
                var cls = after.getClassId() == null ? null : repos.classes().getById(after.getClassId());
                if (row.longRest().isSelected()) Progression.longRest(after, repos.items()::getById);
                else Progression.shortRest(after, cls, row.hp().getValue(), repos.items()::getById);
                repos.players().updateIfUnchanged(before, after);
                applied.add(after.getName());
            } catch (IllegalStateException | IllegalArgumentException | com.dnd.data.DataAccessException error) {
                failed.add(row.id() + ": " + owner.friendlyError(error));
            }
        }
        if (!applied.isEmpty()) status.accept("Rest applied: " + String.join(", ", applied));
        if (!failed.isEmpty()) owner.showInfo("Some rests were not applied", String.join("\n", failed));
    }
}
