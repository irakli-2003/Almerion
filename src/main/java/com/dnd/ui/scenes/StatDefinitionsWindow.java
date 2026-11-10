package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.StatSchemas;
import com.dnd.model.character.stats.StatSchema;
import com.dnd.ui.components.EntityForm;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import java.util.Map;
import java.util.Set;

public final class StatDefinitionsWindow {
    private StatDefinitionsWindow() { }

    public static void show(BaseScene owner, CampaignRepositories repos) {
        if (Window.getWindows().stream().anyMatch(w -> w.getProperties().containsKey("almerion.map"))) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Close battle windows before changing campaign stat rules.");
            owner.styleDialog(alert);
            alert.showAndWait();
            return;
        }
        StatSchema schema = repos.statSchema();
        EntityForm form = EntityForm.of(StatSchema.class, schema, Set.of(), Map.of());
        Label hint = owner.body("Add or remove abilities and skills here. IDs are permanent value keys."
            + " Remap ability bindings to existing ability IDs before deleting a referenced stat."
            + " Skill ability fields use ability IDs. The perception binding controls passive perception."
            + " Scores use the modifier (score - 10) / 2, rounded down; skill values are independent bonuses.");
        hint.setWrapText(true);
        Label error = owner.body("");
        error.setWrapText(true);
        VBox content = new VBox(10, hint, form.getNode(), error);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(850);
        scroll.setPrefViewportHeight(650);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Stats & Skills");
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        owner.styleDialog(dialog);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                form.writeTo(schema);
                StatSchemas.save(repos.campaignRoot(), schema);
            } catch (RuntimeException ex) {
                error.setText(ex.getMessage());
                event.consume();
            }
        });
        dialog.showAndWait();
    }
}
