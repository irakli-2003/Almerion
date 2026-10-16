package com.dnd.cloud;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads through to a remote store and keeps a local mirror of everything it has seen, so
 * the app still works (read-only) at a table with no internet: logins and campaign lists
 * come from the mirror. Writes always need the remote and fail loudly when offline.
 */
public class CachingCloudStore implements CloudStore {
    private final CloudStore remote;
    private final CloudStore cache;
    private volatile boolean lastReadOffline;

    public CachingCloudStore(CloudStore remote, CloudStore cache) {
        this.remote = remote;
        this.cache = cache;
    }

    @Override
    public JsonNode get(String path) {
        try {
            JsonNode node = remote.get(path);
            lastReadOffline = false;
            try {
                cache.put(path, node);
            } catch (CloudException ignored) {
                // A cache we can't write only costs us offline support.
            }
            return node;
        } catch (CloudException e) {
            if (!e.isOffline()) throw e;
            lastReadOffline = true;
            return cache.get(path);
        }
    }

    @Override
    public void put(String path, Object value) {
        remote.put(path, value);
        cache.put(path, value);
    }

    @Override
    public void delete(String path) {
        remote.delete(path);
        cache.delete(path);
    }

    /** True when the most recent read had to fall back to the offline mirror. */
    public boolean isOffline() {
        return lastReadOffline;
    }
}
