package com.dnd.cloud;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A small JSON tree shared between every install of the app (accounts, campaign memberships,
 * join codes). Paths are slash separated, relative to the store's root, e.g.
 * {@code campaigns/zurabaach/members}.
 */
public interface CloudStore {

    /** @return the node at {@code path}, or {@code null} when nothing is stored there. */
    JsonNode get(String path);

    /** Replaces whatever is at {@code path} with {@code value} (any Jackson-serialisable object). */
    void put(String path, Object value);

    void delete(String path);

    /** Thrown when the shared store can't be reached or rejects a request. */
    class CloudException extends RuntimeException {
        private final boolean offline;

        public CloudException(String message, boolean offline, Throwable cause) {
            super(message, cause);
            this.offline = offline;
        }

        public CloudException(String message, boolean offline) {
            this(message, offline, null);
        }

        /** True when the failure was a network problem rather than a refused request. */
        public boolean isOffline() {
            return offline;
        }
    }
}
