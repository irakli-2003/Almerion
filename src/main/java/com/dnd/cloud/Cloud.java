package com.dnd.cloud;

import com.dnd.data.AppHome;

/** Process-wide access to the shared directory, built lazily from {@link CloudConfig}. */
public final class Cloud {
    private static CloudConfig config;
    private static CloudStore store;

    private Cloud() {
    }

    public static synchronized CloudConfig config() {
        if (config == null) config = CloudConfig.load();
        return config;
    }

    public static synchronized CloudStore store() {
        if (store == null) {
            CloudConfig cfg = config();
            store = cfg.enabled()
                ? new CachingCloudStore(new FirebaseCloudStore(cfg.databaseUrl(), cfg.rootPath()),
                    new LocalCloudStore(AppHome.resolve("data", "auth", "directory-cache.json")))
                : new LocalCloudStore(AppHome.resolve("data", "auth", "local-directory.json"));
        }
        return store;
    }

    /** True when the last read had to use the offline copy. */
    public static boolean isOffline() {
        CloudStore s = store();
        return s instanceof CachingCloudStore c && c.isOffline();
    }

    /** Swaps the store (and optionally config) - for tests and tools. */
    public static synchronized void override(CloudStore newStore, CloudConfig newConfig) {
        store = newStore;
        config = newConfig;
    }
}
