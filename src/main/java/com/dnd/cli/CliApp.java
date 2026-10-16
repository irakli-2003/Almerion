package com.dnd.cli;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.core.CommandResolver;
import com.dnd.cli.core.CommandSpec;
import com.dnd.cli.core.ConsoleIO;
import com.dnd.cli.core.Page;
import com.dnd.cli.pages.DmMenuPage;
import com.dnd.data.CampaignRepositories;
import com.dnd.data.DataAccessException;
import com.dnd.security.FirebaseSessionSync;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public class CliApp {
    public static void main(String[] args) {
        com.dnd.data.AppHome.seedBundledData();
        // Try to launch JavaFX GUI; fall back to CLI if no display is available
        if (!isHeadless()) {
            com.dnd.ui.GuiApp.launch(args);
            return;
        }

        AppContext context;
        try {
            context = AppContext.build();
        } catch (DataAccessException e) {
            System.out.println("Failed to initialize campaign storage: " + e.getMessage());
            return;
        }

        runLoop(context.getLandingPage(), context.getSession());
    }

    private static boolean isHeadless() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return true;
        // On Windows there's always a display unless explicitly headless
        return false;
    }

    private static void runLoop(Page landingPage, CliSession session) {
        Page current = landingPage;
        ConsoleIO console = session.getConsole();

        while (true) {
            boolean canGoBack = current.getParent() != null;
            renderPage(console, current, canGoBack);

            String input = console.readLine();
            CommandResolver.Result result = CommandResolver.resolve(input, current.getCommands());

            if (result.getType() == CommandResolver.ResultType.INVALID) {
                console.println("invalid command");
                continue;
            }
            if (result.getType() == CommandResolver.ResultType.EXIT) {
                console.println("Exiting.");
                break;
            }
            if (result.getType() == CommandResolver.ResultType.BACK) {
                if (canGoBack) {
                    current = current.getParent();
                    autoPushIfDmMenu(current, session, console);
                }
                continue;
            }

            CommandSpec command = result.getCommand();
            if (command == null) {
                console.println("invalid command");
                continue;
            }

            if (command.getAction() != null) {
                Page target;
                try {
                    target = command.getAction().execute(session);
                } catch (DataAccessException | IllegalArgumentException | IllegalStateException e) {
                    // Boundary for otherwise-uncaught data/validation failures bubbling up from the
                    // data or model layers, so a single bad campaign file doesn't crash the whole CLI.
                    console.println("Action failed: " + e.getMessage());
                    continue;
                }
                if (target != null) {
                    current = target;
                    autoPushIfDmMenu(current, session, console);
                }
                continue;
            }

            Page target = command.getTarget();
            if (target != null) {
                current = target;
                autoPushIfDmMenu(current, session, console);
            }
        }
    }

    /**
     * If the DM just navigated (back or forward) to the main DM menu while an
     * online session is active, silently push the latest campaign state to Firebase.
     * This is the auto-sync hook — the DM never needs to manually trigger a push.
     */
    private static void autoPushIfDmMenu(Page current, CliSession session, ConsoleIO console) {
        if (!(current instanceof DmMenuPage) || !session.isOnline()) {
            return;
        }
        if (session.getCampaignContext() == null) {
            return;
        }
        try {
            ObjectMapper mapper = com.dnd.data.JsonMappers.create();
            CampaignRepositories repos = new CampaignRepositories(session.getCampaignContext().getPath());

            java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
            snapshot.put("campaign", session.getCampaignContext().getName());
            snapshot.put("players", repos.players().list());
            snapshot.put("maps", repos.maps().list());
            snapshot.put("npcs", repos.npcs().list());
            snapshot.put("monsters", repos.monsters().list());

            String json = mapper.writeValueAsString(snapshot);
            session.getFirebaseSync().push(json);
            console.println("[SYNC] Session state pushed to players.");
        } catch (FirebaseSessionSync.SyncException e) {
            console.println("[SYNC] Warning: could not push to Firebase — " + e.getMessage());
        } catch (Exception e) {
            console.println("[SYNC] Warning: could not build snapshot — " + e.getMessage());
        }
    }

    private static void renderPage(ConsoleIO console, Page page, boolean canGoBack) {
        console.println();
        console.println("==================================================");
        console.println(page.getTitle());
        console.println(page.getBody());
        console.println();
        console.println("Commands:");

        List<CommandSpec> commands = page.getCommands();
        for (CommandSpec command : commands) {
            console.println("- " + command.getKey() + " : " + command.getDescription());
        }

        console.println("- exit : Exit program");
        if (canGoBack) {
            console.println("- back (b) : Go to previous page");
        } else {
            console.println("- back (b) : No previous page");
        }
        console.println("==================================================");
        console.println();
    }
}
