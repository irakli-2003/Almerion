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
    private long roleGeneration;
    private int backgroundActions;
    private final Stage primaryStage;
    private com.dnd.auth.User currentUser;
    private com.dnd.security.PrivateAccount privateAccount;
    /** Email address carried between Register/ForgotPassword and the following Verify/Reset scene. */
    private String pendingAuthEmail;

    public UiSession(CliSession session, CampaignStorage storage, Stage stage) {
        this.session = session;
        this.storage = storage;
        this.primaryStage = stage;
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
    public void setDm(boolean dm) {
        if (this.dm != dm) {
            roleGeneration++;
            lockPrivateAccount();
        }
        this.dm = dm;
    }

    public Stage primaryStage() { return primaryStage; }
    public long roleGeneration() { return roleGeneration; }
    public void beginBackgroundAction() { backgroundActions++; }
    public void endBackgroundAction() { backgroundActions--; }
    public boolean hasBackgroundActions() { return backgroundActions > 0; }

    public boolean isHosting() {
        var online = session.getFirebaseSync();
        var local = com.dnd.lan.LanSessionServer.current();
        return online != null && online.isHost() || local != null && local.isOpen();
    }

    /** Called only after secondary windows have accepted their normal close requests. */
    public void enterPlayerMode() {
        if (isHosting()) throw new IllegalStateException("Stop hosting before switching to Player Mode.");
        if (hasBackgroundActions()) throw new IllegalStateException("Wait for pending actions before switching roles.");
        clearRoleContext();
        dm = false;
    }

    public void enterGmMode() {
        if (dm) return;
        if (hasBackgroundActions()) throw new IllegalStateException("Wait for pending actions before switching roles.");
        clearRoleContext();
        dm = true;
    }

    private void clearRoleContext() {
        com.dnd.sync.CampaignSyncService.shared().closed();
        lockPrivateAccount();
        roleGeneration++;
        session.setFirebaseSync(null);
        session.setCampaignContext(null);
        session.setActivePlayerCharacterId(null);
        sessionToken = null;
        activeEntityCategory = null;
        activeEntityId = null;
        activeMapId = null;
    }

    public com.dnd.auth.User getCurrentUser() { return currentUser; }
    public void setCurrentUser(com.dnd.auth.User currentUser) {
        if (this.currentUser != currentUser) lockPrivateAccount();
        if (currentUser == null) {
            session.setFirebaseSync(null);
            sessionToken = null;
            dm = false;
            com.dnd.cloud.Cloud.signOut();
        }
        this.currentUser = currentUser;
    }
    public com.dnd.security.PrivateAccount getPrivateAccount() { return privateAccount; }
    public void setPrivateAccount(com.dnd.security.PrivateAccount account) {
        if (account != null && (currentUser == null || currentUser.getEmail() == null
            || !currentUser.getEmail().equalsIgnoreCase(account.identity().email()))) {
            account.close();
            throw new SecurityException("Private-file account identity mismatch.");
        }
        lockPrivateAccount();
        privateAccount = account;
    }
    public void lockPrivateAccount() {
        if (privateAccount != null) privateAccount.close();
        privateAccount = null;
    }
    public boolean isLoggedIn() { return currentUser != null; }
    public String getPendingAuthEmail() { return pendingAuthEmail; }
    public void setPendingAuthEmail(String email) { this.pendingAuthEmail = email; }

    public java.nio.file.Path campaignRoot() {
        if (session.getCampaignContext() == null) return null;
        return session.getCampaignContext().getPath();
    }
}
