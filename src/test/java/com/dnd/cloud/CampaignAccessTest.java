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
    public void sameNameWithDifferentIdIsOnlyADuplicateCandidate() throws Exception {
        CampaignDirectory dir = new CampaignDirectory(new LocalCloudStore());
        Path folder = Files.createTempDirectory("local-zurabaach");
        CampaignRecord active = CampaignAccess.claim(LocalCampaign.of("zurabaach", folder), "gm", dir);
        CampaignRecord old = dir.register("old-id", " Zurabaach ", "gm");
        CampaignAccess.Listing listing = CampaignAccess.classify(List.of(LocalCampaign.of("zurabaach", folder)), "gm", dir);
        assertEquals(1, listing.mine().size());
        assertEquals(1, listing.elsewhere().size());
        assertEquals(active.id, listing.mine().getFirst().record().id);
        assertEquals(old.id, listing.elsewhere().getFirst().id);
        assertEquals(listing.mine(), CampaignAccess.similarlyNamed(listing.mine(), old));
        assertTrue(dir.find(old.id).isPresent());
        assertEquals(active.id, CampaignMeta.idOf(folder).orElseThrow());
        CampaignRecord unrelated = dir.register("another-id", "Different campaign", "gm");
        assertTrue(CampaignAccess.similarlyNamed(listing.mine(), unrelated).isEmpty());
    }

    @Test
    public void removingObsoleteRegistrationKeepsPlayableCampaignAndFiles() throws Exception {
        CampaignDirectory dir = new CampaignDirectory(new LocalCloudStore());
        Path folder = Files.createTempDirectory("local-campaign");
        CampaignRecord active = CampaignAccess.claim(LocalCampaign.of("zurabaach", folder), "gm", dir);
        CampaignRecord obsolete = dir.register("obsolete", "zurabaach", "gm");
        Files.writeString(folder.resolve("story.txt"), "Keep my campaign");
        CampaignAccess.removeUnavailableRegistration(obsolete.id, List.of(LocalCampaign.of("zurabaach", folder)), "gm", dir);
        assertTrue(dir.find(obsolete.id).isEmpty());
        assertTrue(dir.find(active.id).isPresent());
        assertEquals(active.id, CampaignMeta.idOf(folder).orElseThrow());
        assertEquals("Keep my campaign", Files.readString(folder.resolve("story.txt")));
        assertTrue(CampaignAccess.classify(List.of(LocalCampaign.of("zurabaach", folder)), "gm", dir).elsewhere().isEmpty());
    }

    @Test
    public void removalRefusesARegistrationFoundOnDiskEvenIfListingIsOutdated() throws Exception {
        CampaignDirectory dir = new CampaignDirectory(new LocalCloudStore());
        Path folder = Files.createTempDirectory("local-campaign");
        LocalCampaign stale = LocalCampaign.of("campaign", folder);
        CampaignRecord active = CampaignAccess.claim(stale, "gm", dir);
        assertThrows(MembershipException.class, () ->
            CampaignAccess.removeUnavailableRegistration(active.id, List.of(stale), "gm", dir));
        assertTrue(dir.find(active.id).isPresent());
    }

    @Test
    public void removalRequiresAuthorAndRefusesLiveRegistrations() {
        CampaignDirectory dir = new CampaignDirectory(new LocalCloudStore());
        CampaignRecord record = dir.register("remote", "Campaign", "author");
        dir.apply(record.joinCode, "co-gm");
        dir.accept(record.id, "author", "co-gm", Role.DM);
        assertThrows(MembershipException.class, () ->
            CampaignAccess.removeUnavailableRegistration(record.id, List.of(), "co-gm", dir));
        dir.setLiveToken(record.id, "author", "active-session");
        assertThrows(MembershipException.class, () ->
            CampaignAccess.removeUnavailableRegistration(record.id, List.of(), "author", dir));
        assertTrue(dir.find(record.id).isPresent());
    }

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
