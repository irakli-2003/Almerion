package com.dnd.cli.core;

import com.dnd.cli.storage.CampaignStorage;
import com.dnd.security.FirebaseSessionSync;

public class CliSession {
    private final CampaignStorage storage;
    private final ConsoleIO console;
    private CampaignContext campaignContext;
    /**
     * Id of the {@link com.dnd.model.character.PlayerCharacter} the current
     * player-mode user is playing, once selected via
     * {@code PlayerCharacterSelectionPage}. Unused in DM mode.
     */
    private String activePlayerCharacterId;
    /**
     * Active Firebase sync client, present when an online session is running.
     * Set by the DM when starting an online session; set by players when joining
     * via a session token. {@code null} when offline.
     */
    private FirebaseSessionSync firebaseSync;

    public CliSession(CampaignStorage storage, ConsoleIO console) {
        this.storage = storage;
        this.console = console;
    }

    public CampaignStorage getStorage() {
        return storage;
    }

    public ConsoleIO getConsole() {
        return console;
    }

    public CampaignContext getCampaignContext() {
        return campaignContext;
    }

    public void setCampaignContext(CampaignContext campaignContext) {
        this.campaignContext = campaignContext;
        // A local-network session only ever shows the campaign it was started for.
        com.dnd.lan.LanSessionServer.stopUnlessFor(campaignContext == null ? null : campaignContext.getPath());
    }

    public String getActivePlayerCharacterId() {
        return activePlayerCharacterId;
    }

    public void setActivePlayerCharacterId(String activePlayerCharacterId) {
        this.activePlayerCharacterId = activePlayerCharacterId;
    }

    public FirebaseSessionSync getFirebaseSync() {
        return firebaseSync;
    }

    public void setFirebaseSync(FirebaseSessionSync firebaseSync) {
        if (this.firebaseSync != null) {
            this.firebaseSync.stopListening();
        }
        this.firebaseSync = firebaseSync;
    }

    public boolean isOnline() {
        return firebaseSync != null;
    }
}
