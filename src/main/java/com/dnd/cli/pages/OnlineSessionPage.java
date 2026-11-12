package com.dnd.cli.pages;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.core.CommandSpec;
import com.dnd.cli.core.Page;
import com.dnd.security.FirebaseSessionSync;

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
 * Hosting requires a verified Firebase account and server-checked campaign GM membership.
 */
public class OnlineSessionPage implements Page {

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

        FirebaseSessionSync sync;
        try {
            try {
                com.dnd.cloud.Cloud.account();
            } catch (com.dnd.cloud.CloudStore.CloudException notSignedIn) {
                s.getConsole().print("Verified account email: ");
                String email = s.getConsole().readLine().trim();
                s.getConsole().print("Account password (hidden): ");
                var result = new com.dnd.auth.AuthService().login(email, s.getConsole().readSecret());
                if (!result.success() || result.offline()) {
                    s.getConsole().println(result.message());
                    return this;
                }
            }
            String id = com.dnd.cloud.CampaignMeta.idOf(s.getCampaignContext().getPath())
                .orElseThrow(() -> new IllegalStateException("Register the campaign before hosting it online."));
            sync = FirebaseSessionSync.host(id);
            sync.push(buildSnapshot(s));
            try {
                sync.startChat(s.getCampaignContext().getPath());
            } catch (RuntimeException failure) {
                try { sync.delete(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } catch (FirebaseSessionSync.SyncException | com.dnd.cloud.CloudStore.CloudException | IllegalStateException e) {
            s.getConsole().println("Could not start the online session: " + e.getMessage());
            return this;
        }

        s.setFirebaseSync(sync);

        String token = sync.token();
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
            return this;
        }
        s.setFirebaseSync(null);
        return this;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Serialises what players need from the current campaign (see SessionSnapshot#capture).
     * This is the payload that players receive when they pull from Firebase.
     */
    private String buildSnapshot(CliSession s) {
        return com.dnd.data.SessionSnapshot.capture(s.getCampaignContext().getPath(), s.getCampaignContext().getName());
    }
}
