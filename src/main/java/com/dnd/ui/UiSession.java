package com.dnd.ui;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.storage.CampaignStorage;
import javafx.stage.Stage;

public class UiSession {
    private final CliSession session;
    private final CampaignStorage storage;
    private final SceneRouter router;
    private String sessionToken;
    private EntityCategory activeEntityCategory;
    private String activeEntityId;
    private String activeMapId;
    private boolean dm = false;
    private com.dnd.auth.User currentUser;
    /** Email address carried between Register/ForgotPassword and the following Verify/Reset scene. */
    private String pendingAuthEmail;

    public UiSession(CliSession session, CampaignStorage storage, Stage stage) {
        this.session = session;
        this.storage = storage;
        this.router = new SceneRouter(this, stage);
        com.dnd.ui.scenes.ManualFilesUi.install(this, stage);
    }

    public CliSession getSession() { return session; }
    public CampaignStorage getStorage() { return storage; }
    public SceneRouter getRouter() { return router; }
    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String t) { sessionToken = t; }

    public EntityCategory getActiveEntityCategory() { return activeEntityCategory; }
    public void setActiveEntityCategory(EntityCategory cat) { this.activeEntityCategory = cat; }
    public String getActiveEntityId() { return activeEntityId; }
    public void setActiveEntityId(String id) { this.activeEntityId = id; }
    public String getActiveMapId() { return activeMapId; }
    public void setActiveMapId(String id) { this.activeMapId = id; }
    public boolean isDm() { return dm; }
    public void setDm(boolean dm) { this.dm = dm; }

    public com.dnd.auth.User getCurrentUser() { return currentUser; }
    public void setCurrentUser(com.dnd.auth.User currentUser) { this.currentUser = currentUser; }
    public boolean isLoggedIn() { return currentUser != null; }
    public String getPendingAuthEmail() { return pendingAuthEmail; }
    public void setPendingAuthEmail(String email) { this.pendingAuthEmail = email; }

    public java.nio.file.Path campaignRoot() {
        if (session.getCampaignContext() == null) return null;
        return session.getCampaignContext().getPath();
    }
}
