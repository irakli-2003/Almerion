package com.dnd.cloud;

import com.dnd.cloud.CampaignAccess.LocalCampaign;
import com.dnd.cloud.CampaignDirectory.MembershipException;
import com.dnd.cloud.CampaignRecord.Role;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class CampaignAccessTest {

    @Test
    public void onlyOwnAndUnclaimedCampaignsAreVisible() throws Exception {
        CampaignDirectory dir = new CampaignDirectory(new LocalCloudStore());
        Path base = Files.createTempDirectory("campaigns");
        Path mine = Files.createDirectories(base.resolve("mine"));
        Path theirs = Files.createDirectories(base.resolve("theirs"));
        Path fresh = Files.createDirectories(base.resolve("fresh"));

        CampaignAccess.claim(LocalCampaign.of("mine", mine), "irakli", dir);
        CampaignAccess.claim(LocalCampaign.of("theirs", theirs), "someoneElse", dir);
        CampaignRecord remote = dir.register("remote-only", "Remote", "irakli");

        List<LocalCampaign> local = List.of(LocalCampaign.of("mine", mine),
            LocalCampaign.of("theirs", theirs), LocalCampaign.of("fresh", fresh));
        CampaignAccess.Listing listing = CampaignAccess.classify(local, "Irakli", dir);

        assertEquals(1, listing.mine().size());
        assertEquals("mine", listing.mine().get(0).local().folder());
        assertEquals(1, listing.unclaimed().size());
        assertEquals("fresh", listing.unclaimed().get(0).folder());
        assertEquals(1, listing.elsewhere().size());
        assertEquals(remote.id, listing.elsewhere().get(0).id);

        assertTrue(CampaignAccess.canRunAsDm(mine, "irakli", dir));
        assertFalse(CampaignAccess.canRunAsDm(theirs, "irakli", dir));
    }

    @Test
    public void claimingSomeoneElsesCampaignFailsButCoDmSeesIt() throws Exception {
        CampaignDirectory dir = new CampaignDirectory(new LocalCloudStore());
        Path folder = Files.createTempDirectory("zurabaach");
        CampaignRecord record = CampaignAccess.claim(LocalCampaign.of("Zurabaach", folder), "irakli", dir);

        assertThrows(MembershipException.class,
            () -> CampaignAccess.claim(LocalCampaign.of("Zurabaach", folder), "shako", dir));

        dir.apply(record.joinCode, "shako");
        dir.accept(record.id, "irakli", "shako", Role.DM);
        assertEquals(1, CampaignAccess.classify(List.of(LocalCampaign.of("Zurabaach", folder)), "shako", dir).mine().size());
    }
}
