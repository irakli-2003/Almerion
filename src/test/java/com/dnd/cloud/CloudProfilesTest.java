package com.dnd.cloud;

import com.dnd.auth.User;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class CloudProfilesTest {
    @Test
    public void enrollmentUsesReviewedClaimAndNeverUploadsLocalCredentials() {
        LocalCloudStore store = new LocalCloudStore();
        store.put("claimsByEmail/" + CloudKeys.of("owner@example.test"),
            Map.of("username", "Original GM", "email", "owner@example.test"));
        User local = new User();
        local.setUsername("Untrusted cached alias");
        local.setEmail("owner@example.test");
        local.setPasswordHash("sensitive-password-hash");
        local.setPasswordSalt("sensitive-salt");
        User profile = new CloudProfiles(store).enroll("provider-uid", "owner@example.test", local);
        assertEquals("Original GM", profile.getUsername());
        assertEquals("provider-uid", profile.getId());
        assertEquals("provider-uid", profile.getProviderUid());
        assertNull(profile.getPasswordHash());
        assertEquals(3, store.get("profiles/" + CloudKeys.of("Original GM")).size());
        assertFalse(store.get("").toString().contains("sensitive"));
        assertEquals(CloudKeys.of("Original GM"), store.get("accountKeys/provider-uid").asText());
    }

    @Test
    public void existingProfileMustMatchBothProviderUidAndEmail() {
        LocalCloudStore store = new LocalCloudStore();
        store.put("accountKeys/provider-uid", "gm");
        store.put("profiles/gm", Map.of("uid", "somebody-else", "username", "GM", "email", "owner@example.test"));
        assertThrows(SecurityException.class,
            () -> new CloudProfiles(store).enroll("provider-uid", "owner@example.test", null));
        store.put("profiles/gm", Map.of("uid", "provider-uid", "username", "GM", "email", "other@example.test"));
        assertThrows(SecurityException.class,
            () -> new CloudProfiles(store).enroll("provider-uid", "owner@example.test", null));
    }
}
