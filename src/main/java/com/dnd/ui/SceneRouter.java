package com.dnd.ui;

import com.dnd.cli.core.CliSession;
import com.dnd.sync.CampaignRepo;
import com.dnd.sync.CampaignSyncService;
import com.dnd.ui.scenes.*;
import com.dnd.ui.scenes.player.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

public class SceneRouter {
    private final UiSession uiSession;
    private final Stage stage;
    private final Predicate<Path> syncEnabled;
    private final Function<Path, CompletableFuture<CampaignRepo.Result>> sync;
    private SceneType currentType;
    private boolean leaving;

    public SceneRouter(UiSession uiSession, Stage stage) {
        this(uiSession, stage, CampaignRepo::isSynced, CampaignSyncService.shared()::syncNow);
    }

    SceneRouter(UiSession uiSession, Stage stage, Predicate<Path> syncEnabled,
                Function<Path, CompletableFuture<CampaignRepo.Result>> sync) {
        this.uiSession = uiSession;
        this.stage = stage;
        this.syncEnabled = syncEnabled;
        this.sync = sync;
    }

    /** Scenes reachable without being logged in - everything else redirects to LOGIN. */
    private static boolean isPublic(SceneType type) {
        return type == SceneType.LOGIN || type == SceneType.REGISTER || type == SceneType.VERIFY_EMAIL
            || type == SceneType.FORGOT_PASSWORD || type == SceneType.RESET_PASSWORD;
    }

    public void goTo(SceneType type) {
        if (leaving) return;
        if (!isPublic(type) && !uiSession.isLoggedIn()) {
            type = SceneType.LOGIN;
        }
        if (!uiSession.isDm() && gmOnly(type)) type = SceneType.PLAYER_ONLINE_SESSION;
        SceneType target = type;
        if (isCampaignOpen() && !campaignScene(target)) {
            leaveCampaign(() -> show(target));
        } else {
            show(target);
        }
    }

    private void show(SceneType type) {
        if (type == SceneType.DM_MENU) {
            autoPush();
            if (uiSession.campaignRoot() != null) {
                com.dnd.sync.CampaignSyncService.shared().opened(uiSession.campaignRoot());
            }
        } else if (type == SceneType.CAMPAIGN_SELECTION || type == SceneType.LANDING || type == SceneType.LOGIN) {
            // Leaving the campaign: upload what changed and fetch the other computer's changes.
            com.dnd.sync.CampaignSyncService.shared().closed();
        }
        // buildScene() can throw (e.g. a data file failed to load) - if that happens with no
        // try/catch here, the exception escapes into the JavaFX event queue uncaught, the stage
        // keeps its current scene, and the button that triggered navigation looks like it did
        // nothing at all. Surfacing the failure instead keeps the user on a working screen with
        // an actionable message rather than looking stuck.
        Scene scene;
        try {
            scene = buildScene(type);
        } catch (Exception e) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.ERROR,
                "Couldn't open " + type + ": " + rootMessage(e), javafx.scene.control.ButtonType.OK);
            alert.showAndWait();
            return;
        }
        currentType = type;
        stage.setScene(scene);
    }

    /** Run a navigation, role change or exit only after the linked GM campaign has synced. */
    public void leaveCampaign(Runnable leave) {
        if (leaving) return;
        Path campaign = uiSession.campaignRoot();
        if (!uiSession.isDm() || !isCampaignOpen() || campaign == null || !syncEnabled.test(campaign)) {
            leave.run();
            return;
        }
        leaving = true;
        boolean ready = false;
        try {
            if (uiSession.hasBackgroundActions()) {
                notice("Wait for pending campaign actions before closing. Then try again.");
                return;
            }
            if (!ReportUi.prepareCampaignClose(stage.getScene()) || !PlayerModeUi.closeSecondaryWindows(stage)) return;
            if (uiSession.hasBackgroundActions()) {
                notice("Wait for pending campaign actions before closing. Then try again.");
                return;
            }
            ready = true;
        } finally {
            if (!ready) leaving = false;
        }
        Alert progress = syncAlert(Alert.AlertType.INFORMATION, "Syncing saved campaign changes before closing...");
        progress.setGraphic(new ProgressIndicator());
        progress.getDialogPane().lookupButton(ButtonType.OK).setDisable(true);
        progress.setOnCloseRequest(event -> event.consume());
        progress.show();
        CompletableFuture<CampaignRepo.Result> pending;
        try {
            pending = sync.apply(campaign);
        } catch (RuntimeException failure) {
            pending = CompletableFuture.failedFuture(failure);
        }
        pending.whenComplete((result, failure) -> Platform.runLater(() -> {
            progress.setOnCloseRequest(null);
            progress.close();
            if (failure != null || result.outcome() != CampaignRepo.Outcome.SYNCED) {
                leaving = false;
                notice((failure == null ? result.message() : rootMessage(failure))
                    + "\n\n" + com.dnd.ui.i18n.UiTranslator.text(
                        "The campaign is still open. Use GitHub Sync to resolve the problem, then try closing again."));
                return;
            }
            CampaignSyncService.shared().closedAfterSync(campaign);
            leaving = false;
            leave.run();
        }));
    }

    private void notice(String message) {
        syncAlert(Alert.AlertType.WARNING, message).show();
    }

    private Alert syncAlert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.initOwner(stage);
        alert.initModality(javafx.stage.Modality.WINDOW_MODAL);
        alert.setTitle(com.dnd.ui.i18n.UiTranslator.text("GitHub Sync"));
        alert.setHeaderText(null);
        var css = SceneRouter.class.getResource("/com/dnd/ui/styles/dnd-theme.css");
        if (css != null) alert.getDialogPane().getStylesheets().add(css.toExternalForm());
        alert.setContentText(com.dnd.ui.i18n.UiTranslator.text(message));
        return alert;
    }

    public void reload() {
        if (currentType != null) goTo(currentType);
    }

    public boolean isCampaignOpen() {
        return campaignScene(currentType);
    }

    private static boolean campaignScene(SceneType type) {
        return type != null && switch (type) {
            case DM_MENU, DM_ONLINE_SESSION, ENTITY_LIST, ENTITY_DETAIL, MAP_VIEW, MAP_EDITOR, WORLD_MAP, STORYLINE -> true;
            default -> false;
        };
    }

    static boolean gmOnly(SceneType type) {
        return switch (type) {
            case CAMPAIGN_SELECTION, CREATE_CAMPAIGN, DM_MENU, DM_ONLINE_SESSION, ENTITY_LIST, ENTITY_DETAIL,
                 MAP_VIEW, MAP_EDITOR, WORLD_MAP, STORYLINE -> true;
            default -> false;
        };
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }

    private Scene buildScene(SceneType type) {
        switch (type) {
            case LOGIN: return new com.dnd.ui.scenes.LoginScene(uiSession).build();
            case REGISTER: return new com.dnd.ui.scenes.RegisterScene(uiSession).build();
            case VERIFY_EMAIL: return new com.dnd.ui.scenes.VerifyEmailScene(uiSession).build();
            case FORGOT_PASSWORD: return new com.dnd.ui.scenes.ForgotPasswordScene(uiSession).build();
            case RESET_PASSWORD: return new com.dnd.ui.scenes.ResetPasswordScene(uiSession).build();
            case LANDING: return new LandingScene(uiSession).build();
            case CAMPAIGN_SELECTION: return new CampaignSelectionScene(uiSession).build();
            case CREATE_CAMPAIGN: return new CreateCampaignScene(uiSession).build();
            case DM_MENU: return new DmMenuScene(uiSession).build();
            case DM_ONLINE_SESSION: return new OnlineSessionScene(uiSession).build();
            case PLAYER_CHARACTER_SELECTION: return new PlayerCharacterSelectionScene(uiSession).build();
            case PLAYER_HOME: return new PlayerHomeScene(uiSession).build();
            case PLAYER_ONLINE_SESSION: return new PlayerOnlineSessionScene(uiSession).build();
            case ENTITY_LIST: return new com.dnd.ui.scenes.EntityListScene(uiSession).build();
            case ENTITY_DETAIL: return new com.dnd.ui.scenes.EntityDetailScene(uiSession).build();
            case MAP_VIEW: return new com.dnd.ui.scenes.MapViewScene(uiSession).build();
            case MAP_EDITOR: return new com.dnd.ui.scenes.MapEditorScene(uiSession).build();
            case WORLD_MAP: return new com.dnd.ui.scenes.WorldMapScene(uiSession).build();
            case CHARACTER_PROFILE: return new com.dnd.ui.scenes.player.CharacterProfileScene(uiSession).build();
            case STORYLINE: return new com.dnd.ui.scenes.StorylineScene(uiSession).build();
            default: return new LandingScene(uiSession).build();
        }
    }

    private void autoPush() {
        CliSession session = uiSession.getSession();
        if (!session.isOnline() || session.getCampaignContext() == null) return;
        // Same full snapshot as "Push State Now": a partial one would drop catalogs on players'
        // machines and lose the campaign id that membership checks need.
        var context = session.getCampaignContext();
        try {
            session.getFirebaseSync().pushCampaign(context.getPath(), context.getName());
        } catch (Exception e) {
            System.err.println("Online session push failed: " + e.getMessage());
        }
    }
}
