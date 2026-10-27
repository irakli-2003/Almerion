package com.dnd.cli.pages;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.core.CommandSpec;
import com.dnd.cli.core.Page;
import com.dnd.data.CampaignRepositories;
import com.dnd.security.FirebaseSessionSync;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * DM page for managing the online Firebase session.
 *
 * <p>From here the DM can:</p>
 * <ul>
 *   <li><b>start</b> — generate a session ID, connect to Firebase, print the
 *       shareable token that players paste into their CLI, then push an initial
 *       snapshot of the current campaign state.</li>
 *   <li><b>push</b> — re-upload the latest campaign state (call this after
 *       any edit so players see the changes immediately).</li>
 *   <li><b>stop</b> — delete the session node from Firebase and disconnect.</li>
 * </ul>
 *
 * <h2>Firebase database URL</h2>
 * The URL is hard-coded to the project's Realtime Database:
 * {@code https://dnd-campaign-f8c23-default-rtdb.europe-west1.firebasedatabase.app}
 */
public class OnlineSessionPage implements Page {

    private static final String FIREBASE_URL =
            "https://dnd-campaign-f8c23-default-rtdb.europe-west1.firebasedatabase.app";

    private final CliSession session;
    private Page parent;

    public OnlineSessionPage(CliSession session, Page parent) {
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
            return "Online session is active. Campaign state is pushed automatically when you navigate the GM menu.\n"
                    + "Use 'stop' to end the session.";
        }
        return "Start an online session to let players connect and see live campaign state.\n"
                + "You will need to share the generated token with your players.";
    }

    @Override
    public List<CommandSpec> getCommands() {
        if (session.isOnline()) {
            return List.of(
                    new CommandSpec("stop", "End the online session", this::stop)
            );
        }
        return List.of(
                new CommandSpec("start", "Start online session and generate player token", this::start)
        );
    }

    @Override
    public Page getParent() {
        return parent;
    }

    // ── Command handlers ─────────────────────────────────────────────────────

    private Page start(CliSession s) {
        if (s.isOnline()) {
            s.getConsole().println("A session is already active. Use 'push' or 'stop'.");
            return this;
        }
        if (s.getCampaignContext() == null) {
            s.getConsole().println("No campaign selected. Please select a campaign first.");
            return this;
        }

        s.getConsole().print("Enter a passphrase for this session (players will need it): ");
        String passphrase = s.getConsole().readLine().trim();
        if (passphrase.isEmpty()) {
            s.getConsole().println("Passphrase cannot be empty.");
            return this;
        }

        String sessionId = FirebaseSessionSync.generateSessionId();
        FirebaseSessionSync sync = new FirebaseSessionSync(FIREBASE_URL, sessionId, passphrase);

        String snapshot = buildSnapshot(s);
        try {
            sync.push(snapshot);
        } catch (FirebaseSessionSync.SyncException e) {
            s.getConsole().println("Failed to connect to Firebase: " + e.getMessage());
            s.getConsole().println("Check your internet connection and try again.");
            return this;
        }

        s.setFirebaseSync(sync);

        String token = FirebaseSessionSync.generateToken(FIREBASE_URL, sessionId, passphrase);
        s.getConsole().println();
        s.getConsole().println("=== SESSION STARTED ===");
        s.getConsole().println("Share this token with your players:");
        s.getConsole().println();
        s.getConsole().println("  " + token);
        s.getConsole().println();
        s.getConsole().println("Campaign state has been pushed to Firebase.");
        return this;
    }

    private Page stop(CliSession s) {
        if (!s.isOnline()) {
            s.getConsole().println("No active session.");
            return this;
        }
        try {
            s.getFirebaseSync().delete();
            s.getConsole().println("Session ended and data removed from Firebase.");
        } catch (FirebaseSessionSync.SyncException e) {
            s.getConsole().println("Warning: could not remove session from Firebase: " + e.getMessage());
        }
        s.setFirebaseSync(null);
        return this;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Serialises the current campaign's players and maps to a compact JSON snapshot.
     * This is the payload that players receive when they pull from Firebase.
     */
    private String buildSnapshot(CliSession s) {
        try {
            ObjectMapper mapper = com.dnd.data.JsonMappers.create();
            CampaignRepositories repos = new CampaignRepositories(s.getCampaignContext().getPath());

            java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
            snapshot.put("campaign", s.getCampaignContext().getName());
            snapshot.put("players", repos.players().list());
            snapshot.put("maps", repos.maps().list());
            snapshot.put("npcs", repos.npcs().list());
            snapshot.put("monsters", repos.monsters().list());

            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            // Fallback: push minimal metadata rather than failing the whole command
            return "{\"campaign\":\"" + s.getCampaignContext().getName() + "\"}";
        }
    }
}
