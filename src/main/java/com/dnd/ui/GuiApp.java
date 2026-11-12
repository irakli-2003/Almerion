package com.dnd.ui;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.core.SystemConsoleIO;
import com.dnd.cli.storage.CampaignStorage;
import com.dnd.ui.i18n.AppSettings;
import com.dnd.ui.i18n.TranslationService;
import com.dnd.ui.i18n.UiTranslator;
import javafx.application.Application;
import javafx.stage.Stage;

import java.util.Scanner;

public class GuiApp extends Application {
    @Override
    public void start(Stage primaryStage) {
        UiFonts.load();
        WindowOrder.install();
        TooltipPersistence.install();
        UiTranslator.install(TranslationService.createDefault(), AppSettings.load());
        primaryStage.getIcons().setAll(AppIcons.icons());
        AppIcons.install();
        ReportUi.install();
        CampaignStorage storage = new CampaignStorage();
        storage.ensureInitialized();
        CliSession session = new CliSession(storage, new SystemConsoleIO(new Scanner(System.in)));
        UiSession uiSession = new UiSession(session, storage, primaryStage);
        primaryStage.setTitle("Almerion: Tabletop RPG Campaign Manager");
        primaryStage.setWidth(900);
        primaryStage.setHeight(650);
        // "Windowed full screen": maximized with the OS chrome still visible, not true
        // (chromeless) full-screen mode - matches what the user asked for on every launch.
        primaryStage.setMaximized(true);
        primaryStage.setOnCloseRequest(e -> {
            if (session.isOnline()) {
                if (session.getFirebaseSync().isHost()) {
                    try {
                        session.getFirebaseSync().delete();
                    } catch (com.dnd.security.FirebaseSessionSync.SyncException failure) {
                        System.err.println("Could not remove the hosted session on exit; access expires automatically: " + failure.getMessage());
                    }
                }
                session.setFirebaseSync(null);
            }
            uiSession.lockPrivateAccount();
            com.dnd.cloud.Cloud.signOut();
        });
        uiSession.getRouter().goTo(SceneType.LOGIN);
        primaryStage.show();
        UpdatePrompt.checkInBackground(primaryStage);
    }

    @Override
    public void stop() {
        // Upload the open campaign's last changes to GitHub (bounded, so quitting never hangs).
        com.dnd.sync.CampaignSyncService.shared().shutdown(20_000);
        com.dnd.ui.music.MusicPlayer.get().shutdown();
        com.dnd.lan.LanSessionServer.stopShared();
    }

    public static void launch(String[] args) {
        Application.launch(GuiApp.class, args);
    }
}
