package com.dnd.ui;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.core.SystemConsoleIO;
import com.dnd.cli.storage.CampaignStorage;
import javafx.application.Application;
import javafx.stage.Stage;

import java.util.Scanner;

public class GuiApp extends Application {
    @Override
    public void start(Stage primaryStage) {
        UiFonts.load();
        WindowOrder.install();
        TooltipPersistence.install();
        CampaignStorage storage = new CampaignStorage();
        storage.ensureInitialized();
        CliSession session = new CliSession(storage, new SystemConsoleIO(new Scanner(System.in)));
        UiSession uiSession = new UiSession(session, storage, primaryStage);
        primaryStage.setTitle("DnD Campaign Manager");
        primaryStage.setWidth(900);
        primaryStage.setHeight(650);
        // "Windowed full screen": maximized with the OS chrome still visible, not true
        // (chromeless) full-screen mode - matches what the user asked for on every launch.
        primaryStage.setMaximized(true);
        primaryStage.setOnCloseRequest(e -> {
            if (session.isOnline()) {
                try { session.getFirebaseSync().delete(); } catch (Exception ignored) {}
                session.setFirebaseSync(null);
            }
        });
        uiSession.getRouter().goTo(SceneType.LOGIN);
        primaryStage.show();
    }

    @Override
    public void stop() {
        // The embedded music engine (Chromium) runs its own non-daemon threads, so the JVM
        // would linger after the last window closes unless it is shut down explicitly.
        if (com.dnd.ui.music.MusicPlayer.get().shutdown()) {
            System.exit(0);
        }
    }

    public static void launch(String[] args) {
        Application.launch(GuiApp.class, args);
    }
}
