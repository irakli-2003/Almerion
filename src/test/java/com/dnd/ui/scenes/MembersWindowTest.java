package com.dnd.ui.scenes;

import com.dnd.cloud.CampaignRecord;
import com.dnd.cloud.CampaignRecord.Role;
import com.dnd.cloud.CloudKeys;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MembersWindowTest {
    @Test
    public void gmCanLinkCharactersToAnyCurrentMemberIncludingTheOwner() {
        var campaign = new CampaignRecord();
        campaign.author = "Owner";
        addMember(campaign, "Owner", Role.DM);
        addMember(campaign, "CoGM", Role.DM);
        addMember(campaign, "Player", Role.PLAYER);

        assertTrue(MembersWindow.canAssignCharacter(campaign, "Owner", "Owner"));
        assertTrue(MembersWindow.canAssignCharacter(campaign, "Owner", "CoGM"));
        assertTrue(MembersWindow.canAssignCharacter(campaign, "CoGM", "Player"));
        assertFalse(MembersWindow.canAssignCharacter(campaign, "Owner", "NonMember"));
        assertFalse(MembersWindow.canAssignCharacter(campaign, "Player", "Player"));
        campaign.members.remove(CloudKeys.of("CoGM"));
        assertFalse(MembersWindow.canAssignCharacter(campaign, "Owner", "CoGM"));
    }

    private static void addMember(CampaignRecord campaign, String username, Role role) {
        campaign.members.put(CloudKeys.of(username), new CampaignRecord.Member(username, role));
    }
}
