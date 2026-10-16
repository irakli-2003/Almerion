package com.dnd.cloud;

import com.dnd.cloud.CampaignDirectory.MembershipException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Decides which of the campaign folders on this device an account may see and open as DM. */
public final class CampaignAccess {
    private CampaignAccess() {
    }

    /** A campaign folder on this device; {@code id} is null when it has never been registered. */
    public record LocalCampaign(String folder, Path path, String id) {
        public static LocalCampaign of(String folder, Path path) {
            return new LocalCampaign(folder, path, CampaignMeta.idOf(path).orElse(null));
        }
    }

    public record Entry(LocalCampaign local, CampaignRecord record) {
    }

    /**
     * @param mine      campaigns here that {@code user} is a DM of
     * @param unclaimed campaigns here that nobody has registered yet, so they can be claimed
     * @param elsewhere campaigns {@code user} is a DM of whose files aren't on this device
     */
    public record Listing(List<Entry> mine, List<LocalCampaign> unclaimed, List<CampaignRecord> elsewhere) {
    }

    /** Campaigns registered to other people are left out entirely. */
    public static Listing classify(List<LocalCampaign> local, String user, CampaignDirectory directory) {
        List<Entry> mine = new ArrayList<>();
        List<LocalCampaign> unclaimed = new ArrayList<>();
        Set<String> localIds = new HashSet<>();
        for (LocalCampaign campaign : local) {
            CampaignRecord record = campaign.id() == null ? null : directory.find(campaign.id()).orElse(null);
            if (campaign.id() != null) localIds.add(campaign.id());
            if (record == null) {
                unclaimed.add(campaign);
            } else if (record.isDm(user)) {
                mine.add(new Entry(campaign, record));
            }
        }
        List<CampaignRecord> elsewhere = new ArrayList<>();
        for (CampaignRecord record : directory.campaignsOf(user)) {
            if (record.isDm(user) && !localIds.contains(record.id)) elsewhere.add(record);
        }
        return new Listing(mine, unclaimed, elsewhere);
    }

    /** Registers an unclaimed local campaign with {@code user} as its author DM. */
    public static CampaignRecord claim(LocalCampaign campaign, String user, CampaignDirectory directory) {
        String id = CampaignMeta.ensureId(campaign.path());
        CampaignRecord existing = directory.find(id).orElse(null);
        if (existing != null) {
            if (existing.isDm(user)) return existing;
            throw new MembershipException("\"" + campaign.folder() + "\" is already registered by "
                + existing.author + ". Ask them to add you as a Dungeon Master.");
        }
        return directory.register(id, campaign.folder(), user);
    }

    /** Whether {@code user} may open the campaign at {@code path} as a DM. */
    public static boolean canRunAsDm(Path path, String user, CampaignDirectory directory) {
        return CampaignMeta.idOf(path).flatMap(directory::find).map(r -> r.isDm(user)).orElse(false);
    }
}
