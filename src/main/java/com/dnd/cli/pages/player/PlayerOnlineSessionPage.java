package com.dnd.cli.pages.player;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.core.CommandSpec;
import com.dnd.cli.core.Page;
import com.dnd.security.FirebaseSessionSync;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * Player page for joining and interacting with a live online session.
 *
 * <p>On {@code join} a persistent Firebase SSE connection is opened in the
 * background.  The DM's every {@code push} is received instantly with zero
 * polling — data only flows over the wire when something actually changes.
 * The latest decrypted snapshot is cached in memory; {@code view} displays it
 * without any network call.</p>
 */
public class PlayerOnlineSessionPage implements Page {

    private final CliSession session;
    private Page parent;

    public PlayerOnlineSessionPage(CliSession session, Page parent) {
        this.session = session;
        this.parent = parent;
    }

    public void setParent(Page parent) {
        this.parent = parent;
    }

    @Override
    public String getTitle() {
        return "Online Session" + (session.isOnline() ? " [LIVE]" : "");
    }

    @Override
    public String getBody() {
        if (session.isOnline()) {
            boolean hasData = session.getFirebaseSync().getLatestSnapshot() != null;
            return "Connected — listening for live updates from the GM.\n"
                    + (hasData ? "Type 'view' to see the latest session state."
                               : "Waiting for the GM to push session state...");
        }
        return "Enter the session token shared by your GM to connect to the live game.";
    }

    @Override
    public List<CommandSpec> getCommands() {
        if (session.isOnline()) {
            return Arrays.asList(
                    new CommandSpec("view",       "Show latest session state (instant, no network)", this::view),
                    new CommandSpec("disconnect", "Disconnect from the online session",               this::disconnect)
            );
        }
        return List.of(
                new CommandSpec("join", "Join session with a token from your GM", this::join)
        );
    }

    @Override
    public Page getParent() {
        return parent;
    }

    // ── Command handlers ─────────────────────────────────────────────────────

    private Page join(CliSession s) {
        if (s.isOnline()) {
            s.getConsole().println("Already connected. Use 'disconnect' first.");
            return this;
        }

        s.getConsole().print("Paste the session token from your GM: ");
        String token = s.getConsole().readLine().trim();
        if (token.isEmpty()) {
            s.getConsole().println("Token cannot be empty.");
            return this;
        }

        FirebaseSessionSync sync;
        try {
            sync = FirebaseSessionSync.fromToken(token);
        } catch (IllegalArgumentException e) {
            s.getConsole().println("Invalid token: " + e.getMessage());
            return this;
        }

        // Verify connectivity and initial state with a one-time pull before switching to SSE
        String initialJson;
        try {
            initialJson = sync.pull();
        } catch (FirebaseSessionSync.SyncException e) {
            s.getConsole().println("Could not connect to Firebase: " + e.getMessage());
            return this;
        } catch (com.dnd.security.SessionCipher.CipherException e) {
            s.getConsole().println("Wrong passphrase or corrupted data. Check your token.");
            return this;
        }

        if (initialJson == null) {
            s.getConsole().println("Session not started yet. Ask your GM to start the session first.");
            return this;
        }

        // Start SSE listener — fires callback on every DM push
        sync.startListening(json -> {
            // This runs on the background listener thread.
            // We can't interrupt the user's stdin read, so we print a notification
            // line; the player sees it as soon as they press Enter.
            s.getConsole().println("\n[SESSION UPDATE] The GM pushed new state — type 'view' to see it.");
        });

        s.setFirebaseSync(sync);
        s.getConsole().println("Connected and listening for live updates!");
        s.getConsole().println("Initial session state:");
        printSnapshot(s, initialJson);
        return this;
    }

    private Page view(CliSession s) {
        if (!s.isOnline()) {
            s.getConsole().println("Not connected. Use 'join' first.");
            return this;
        }
        String json = s.getFirebaseSync().getLatestSnapshot();
        if (json == null) {
            s.getConsole().println("No data received yet. Waiting for the GM to push...");
        } else {
            printSnapshot(s, json);
        }
        return this;
    }

    private Page disconnect(CliSession s) {
        s.setFirebaseSync(null);   // also calls stopListening() via CliSession
        s.getConsole().println("Disconnected from online session.");
        return this;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void printSnapshot(CliSession s, String json) {
        try {
            ObjectMapper mapper = com.dnd.data.JsonMappers.create();
            JsonNode root = mapper.readTree(json);

            s.getConsole().println();
            s.getConsole().println("=== SESSION STATE ===");

            if (root.has("campaign")) {
                s.getConsole().println("Campaign: " + root.get("campaign").asText());
            }

            if (root.has("players")) {
                JsonNode players = root.get("players");
                s.getConsole().println("Players (" + players.size() + "):");
                for (JsonNode p : players) {
                    String name = p.has("name") ? p.get("name").asText() : p.path("id").asText("?");
                    int level = p.path("level").asInt(1);
                    s.getConsole().println("  - " + name + " (level " + level + ")");
                }
            }

            if (root.has("maps")) {
                JsonNode maps = root.get("maps");
                s.getConsole().println("Maps (" + maps.size() + "):");
                for (JsonNode m : maps) {
                    String name = m.has("name") ? m.get("name").asText() : m.path("id").asText("?");
                    s.getConsole().println("  - " + name);
                }
            }

            if (root.has("npcs")) {
                s.getConsole().println("NPCs: " + root.get("npcs").size());
            }
            if (root.has("monsters")) {
                s.getConsole().println("Monsters: " + root.get("monsters").size());
            }

            s.getConsole().println();
        } catch (Exception e) {
            s.getConsole().println("(Could not parse session data)");
        }
    }
}
