package com.dnd.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

public class CloudStoreTest {

    /** A remote that can be switched off to simulate losing the connection. */
    static class FlakyStore implements CloudStore {
        final LocalCloudStore inner = new LocalCloudStore();
        boolean online = true;

        private void check() {
            if (!online) throw new CloudException("offline", true);
        }

        public JsonNode get(String path) { check(); return inner.get(path); }
        public void put(String path, Object value) { check(); inner.put(path, value); }
        public void delete(String path) { check(); inner.delete(path); }
    }

    @Test
    public void localStoreAddressesNestedPaths() throws Exception {
        Path file = Files.createTempDirectory("cloud").resolve("dir.json");
        LocalCloudStore store = new LocalCloudStore(file);
        store.put("a/b/c", Map.of("x", 1));
        assertEquals(1, store.get("a/b/c/x").asInt());
        assertTrue(store.get("a").has("b"));
        store.delete("a/b");
        assertNull(store.get("a/b/c"));

        store.put("persisted", "yes");
        assertEquals("yes", new LocalCloudStore(file).get("persisted").asText());
    }

    @Test
    public void cachingStoreServesCacheWhenOffline() {
        FlakyStore remote = new FlakyStore();
        CachingCloudStore store = new CachingCloudStore(remote, new LocalCloudStore());
        store.put("campaigns/z", Map.of("name", "Zurabaach"));
        assertEquals("Zurabaach", store.get("campaigns/z/name").asText());
        assertFalse(store.isOffline());

        remote.online = false;
        assertEquals("Zurabaach", store.get("campaigns/z/name").asText());
        assertTrue(store.isOffline());
        assertThrows(CloudStore.CloudException.class, () -> store.put("x", 1));
    }

    @Test
    public void keysAreSafeAndCaseInsensitive() {
        assertEquals(CloudKeys.of("Irakli"), CloudKeys.of("irakli"));
        String key = CloudKeys.of("a.b@c/d#e$[f]");
        assertTrue(key.matches("[a-z0-9_-]+"));
        assertNotEquals(CloudKeys.of("a.b"), CloudKeys.of("a_b"));
    }

    @Test
    public void campaignMetaAssignsStableIds() throws Exception {
        Path root = Files.createTempDirectory("camp");
        String id = CampaignMeta.ensureId(root);
        assertNotNull(id);
        assertEquals(id, CampaignMeta.ensureId(root));
        assertEquals(id, CampaignMeta.idOf(root).orElseThrow());
        assertNotEquals(id, CampaignMeta.assignNewId(root));
    }
}
