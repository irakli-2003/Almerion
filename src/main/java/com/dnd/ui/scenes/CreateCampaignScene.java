package com.dnd.ui.scenes;

import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public class CreateCampaignScene extends BaseScene {
    public CreateCampaignScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(SceneType.CAMPAIGN_SELECTION));

        VBox content = new VBox(16);
        content.setPadding(new Insets(30, 60, 40, 60));

        TextField nameField = textField("Campaign name");
        CheckBox blankCheck = checkBox("Start blank (no template data)");
        blankCheck.setStyle("-fx-text-fill: #d0c5a8;");

        Label error = body("");
        error.setStyle("-fx-text-fill: #e07070;");

        Button create = btn("Create Campaign", () -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) { error.setText("Name cannot be empty."); return; }
            try {
                com.dnd.cli.core.CampaignContext ctx =
                    uiSession.getStorage().createCampaignFromDefault(name, blankCheck.isSelected());
                String id = com.dnd.cloud.CampaignMeta.assignNewId(ctx.getPath());
                String author = uiSession.getCurrentUser() == null ? "" : uiSession.getCurrentUser().getUsername();
                error.setText("Registering campaign...");
                background(() -> com.dnd.cloud.CampaignDirectory.shared().register(id, ctx.getName(), author), record -> {
                    uiSession.getSession().setCampaignContext(ctx);
                    uiSession.getRouter().goTo(SceneType.DM_MENU);
                }, failure -> {
                    showError("Campaign created, but not registered online",
                        new RuntimeException(friendlyError(failure)
                            + "\n\nIt is listed under \"not registered to anyone\" - claim it once you're online."));
                    uiSession.getRouter().goTo(SceneType.CAMPAIGN_SELECTION);
                });
            } catch (Exception e) {
                error.setText("Failed: " + e.getMessage());
            }
        });

        content.getChildren().addAll(
            title("Create Campaign"), nameField, blankCheck, error, create
        );
        root.getChildren().add(content);
        return wrapInScene(root);
    }
}
