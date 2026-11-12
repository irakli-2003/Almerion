package com.dnd.cloud;

import com.dnd.data.AppHome;
import com.dnd.auth.User;
import com.dnd.security.FirebasePrivateStore;

/** Process-wide access to the shared directory, built lazily from {@link CloudConfig}. */
public final class Cloud {
    private static CloudConfig config;
    private static CloudStore store;
    private static FirebasePrivateStore account;
    private static boolean offline;

    private Cloud() {
    }

    public static synchronized CloudConfig config() {
        if (config == null) config = CloudConfig.load();
        return config;
    }

    public static synchronized CloudStore store() {
        if (store == null) {
            CloudConfig cfg = config();
            if (cfg.enabled()) throw new CloudStore.CloudException("Sign in with a verified Firebase account to use online campaigns.", false);
            store = new LocalCloudStore(AppHome.resolve("data", "auth", "local-directory.json"));
        }
        return store;
    }

    /** True when the last read had to use the offline copy. */
    public static boolean isOffline() {
        CloudStore s = store();
        return offline || s instanceof CachingCloudStore c && c.isOffline();
    }

    public static synchronized User signIn(User hint, String email, String password) {
        signOut();
        User identity = new User();
        identity.setEmail(email);
        FirebasePrivateStore connection = FirebasePrivateStore.connect(config(), identity, password);
        try {
            connection.requireRestrictedNamespace("almerion-cloud", "profiles");
            connection.requireRestrictedNamespace("almerion-cloud", "accountKeys");
            CloudStore remote = connection.scope("almerion-cloud");
            User profile = new CloudProfiles(remote).enroll(connection.uid(), connection.email(), hint);
            store = new CachingCloudStore(remote, cache(connection.uid()));
            account = connection;
            return profile;
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    public static synchronized FirebasePrivateStore account() {
        if (account == null) throw new CloudStore.CloudException("Online hosting requires a verified Firebase login.", false);
        return account;
    }

    public static synchronized boolean hasOnlineAccount() {
        return account != null;
    }

    public static synchronized void useOffline(User user) {
        signOut();
        CloudStore snapshot = user.getProviderUid() == null
            ? new LocalCloudStore(AppHome.resolve("data", "auth", "directory-cache.json")) : cache(user.getProviderUid());
        store = new CloudStore() {
            @Override public com.fasterxml.jackson.databind.JsonNode get(String path) { return snapshot.get(path); }
            @Override public void put(String path, Object value) { throw readOnly(); }
            @Override public void delete(String path) { throw readOnly(); }
            private CloudException readOnly() { return new CloudException("Offline: cloud changes require signing in online again.", true); }
        };
        offline = true;
    }

    private static LocalCloudStore cache(String uid) {
        FirebasePrivateStore.validateKey(uid);
        return new LocalCloudStore(AppHome.resolve("data", "auth", "directory-v2-" + uid + ".json"));
    }

    public static synchronized void signOut() {
        if (account != null) account.close();
        account = null;
        store = null;
        offline = false;
    }

    /** Swaps the store (and optionally config) - for tests and tools. */
    public static synchronized void override(CloudStore newStore, CloudConfig newConfig) {
        signOut();
        store = newStore;
        config = newConfig;
    }
}
