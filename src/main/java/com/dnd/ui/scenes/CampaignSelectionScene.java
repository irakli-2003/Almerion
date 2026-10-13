package com.dnd.ui.scenes;

import com.dnd.cli.core.CampaignContext;
import com.dnd.ui.SceneType;
import com.dnd.ui.UiSession;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.List;

public class CampaignSelectionScene extends BaseScene {
    public CampaignSelectionScene(UiSession uiSession) { super(uiSession); }

    @Override
    public Scene build() {
        VBox root = new VBox(0);
        root.getStyleClass().add("root");
        root.getChildren().add(backBar(SceneType.LANDING));

        VBox content = new VBox(16);
        content.setPadding(new Insets(30, 60, 40, 60));

        content.getChildren().addAll(
            title("Campaign Selection"),
            body("Choose an existing campaign or create a new one.")
        );

        List<String> campaigns = uiSession.getStorage().listCustomCampaigns();
        ListView<String> list = new ListView<>();
        list.getStyleClass().add("dnd-list-view");
        list.getItems().addAll(campaigns);
        list.setPrefHeight(200);

        Button openBtn = btn("Open Campaign", () -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            Path path = uiSession.getStorage().resolveCustomCampaignPath(selected);
            uiSession.getSession().setCampaignContext(new CampaignContext(selected, path));
            uiSession.getRouter().goTo(SceneType.DM_MENU);
        });

        list.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2
                    && list.getSelectionModel().getSelectedItem() != null) {
                openBtn.fire();
            }
        });

        Button createBtn = btn("Create New Campaign", () ->
            uiSession.getRouter().goTo(SceneType.CREATE_CAMPAIGN));

        Button deleteBtn = dangerBtn("Delete Selected", () -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete campaign '" + selected + "'? This cannot be undone.",
                ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText("Confirm Delete");
            styleDialog(confirm);
            confirm.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    uiSession.getStorage().deleteCampaign(selected);
                    uiSession.getRouter().goTo(SceneType.CAMPAIGN_SELECTION);
                }
            });
        });

        HBox buttons = new HBox(12, openBtn, createBtn, deleteBtn);
        content.getChildren().addAll(list, buttons);
        root.getChildren().add(content);
        VBox.setVgrow(content, Priority.ALWAYS);

        return wrapInScene(root);
    }
}
