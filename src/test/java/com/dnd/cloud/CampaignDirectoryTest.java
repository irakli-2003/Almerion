package com.dnd.cloud;

import com.dnd.cloud.CampaignDirectory.MembershipException;
import com.dnd.cloud.CampaignRecord.Role;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class CampaignDirectoryTest {
    private CampaignDirectory dir;
    private CampaignRecord campaign;

    @Before
    public void setUp() {
        dir = new CampaignDirectory(new LocalCloudStore());
        campaign = dir.register("zurabaach", "Zurabaach", "Irakli");
    }

    private void join(String user, Role role) {
        dir.apply(campaign.joinCode, user);
        dir.accept("zurabaach", "Irakli", user, role);
    }

    @Test
    public void authorIsDmAndCampaignIsListedForThem() {
        assertTrue(dir.find("zurabaach").orElseThrow().isDm("irakli"));
        assertEquals(1, dir.campaignsOf("Irakli").size());
        assertTrue(dir.campaignsOf("stranger").isEmpty());
        assertEquals(6, campaign.joinCode.length());
    }

    @Test
    public void applicationNeedsValidCodeAndAcceptance() {
        assertThrows(MembershipException.class, () -> dir.apply("WRONG1", "darius"));
        dir.apply(campaign.joinCode.toLowerCase(), "darius");
        assertTrue(dir.campaignsOf("darius").isEmpty());
        assertEquals(1, dir.pendingApplicationsOf("darius").size());
        assertThrows(MembershipException.class, () -> dir.apply(campaign.joinCode, "darius"));

        dir.accept("zurabaach", "Irakli", "darius", Role.PLAYER);
        assertEquals(1, dir.campaignsOf("darius").size());
        assertTrue(dir.pendingApplicationsOf("darius").isEmpty());
        assertEquals(Role.PLAYER, dir.find("zurabaach").orElseThrow().roleOf("Darius"));
    }

    @Test
    public void onlyAuthorCanAddDms() {
        join("coDm", Role.DM);
        dir.apply(campaign.joinCode, "shako");
        assertThrows(MembershipException.class, () -> dir.accept("zurabaach", "coDm", "shako", Role.DM));
        dir.accept("zurabaach", "coDm", "shako", Role.PLAYER);
        assertThrows(MembershipException.class, () -> dir.setRole("zurabaach", "coDm", "shako", Role.DM));
        dir.setRole("zurabaach", "Irakli", "shako", Role.DM);
        assertTrue(dir.find("zurabaach").orElseThrow().isDm("shako"));
    }

    @Test
    public void playersCannotManageMembers() {
        join("darius", Role.PLAYER);
        dir.apply(campaign.joinCode, "shako");
        assertThrows(MembershipException.class, () -> dir.accept("zurabaach", "darius", "shako", Role.PLAYER));
        assertThrows(MembershipException.class, () -> dir.regenerateJoinCode("zurabaach", "darius"));
        assertThrows(MembershipException.class, () -> dir.setLiveToken("zurabaach", "darius", "x"));
    }

    @Test
    public void removalRules() {
        join("coDm", Role.DM);
        join("darius", Role.PLAYER);
        assertThrows(MembershipException.class, () -> dir.remove("zurabaach", "coDm", "Irakli"));
        assertThrows(MembershipException.class, () -> dir.remove("zurabaach", "Irakli", "Irakli"));
        assertThrows(MembershipException.class, () -> dir.leave("zurabaach", "Irakli"));
        dir.remove("zurabaach", "coDm", "darius");
        assertTrue(dir.campaignsOf("darius").isEmpty());
        dir.remove("zurabaach", "Irakli", "coDm");
        assertFalse(dir.find("zurabaach").orElseThrow().isMember("coDm"));
    }

    @Test
    public void regeneratedCodeInvalidatesOldOne() {
        String old = campaign.joinCode;
        CampaignRecord updated = dir.regenerateJoinCode("zurabaach", "Irakli");
        assertNotEquals(old, updated.joinCode);
        assertThrows(MembershipException.class, () -> dir.apply(old, "darius"));
        dir.apply(updated.joinCode, "darius");
    }

    @Test
    public void liveTokenAndDelete() {
        join("darius", Role.PLAYER);
        dir.setLiveToken("zurabaach", "Irakli", "token-123");
        assertEquals("token-123", dir.campaignsOf("darius").get(0).liveToken);
        dir.setLiveToken("zurabaach", "Irakli", null);
        assertNull(dir.find("zurabaach").orElseThrow().liveToken);

        assertThrows(MembershipException.class, () -> dir.delete("zurabaach", "darius"));
        dir.delete("zurabaach", "Irakli");
        assertTrue(dir.find("zurabaach").isEmpty());
        assertTrue(dir.campaignsOf("darius").isEmpty());
        assertTrue(dir.campaignsOf("Irakli").isEmpty());
    }

    @Test
    public void duplicateRegistrationRejected() {
        assertThrows(MembershipException.class, () -> dir.register("zurabaach", "Other", "someone"));
    }
}
