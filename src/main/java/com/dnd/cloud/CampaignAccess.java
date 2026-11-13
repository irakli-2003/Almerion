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

    public record LocalListing(List<Entry> mine, List<LocalCampaign> unclaimed) {
    }

    /** Campaigns registered to other people are left out entirely. */
    public static LocalListing classifyLocal(List<LocalCampaign> local, String user, CampaignDirectory directory) {
        List<Entry> mine = new ArrayList<>();
        List<LocalCampaign> unclaimed = new ArrayList<>();
        for (LocalCampaign campaign : local) {
            if (campaign.id() == null || !directory.isRegistered(campaign.id())) {
                unclaimed.add(campaign);
            } else {
                directory.findForMember(campaign.id(), user).filter(r -> r.isDm(user))
                    .ifPresent(record -> mine.add(new Entry(campaign, record)));
            }
        }
        return new LocalListing(mine, unclaimed);
    }

    public static Listing classify(List<LocalCampaign> local, String user, CampaignDirectory directory) {
        LocalListing localListing = classifyLocal(local, user, directory);
        Set<String> localIds = new HashSet<>();
        for (LocalCampaign campaign : local) if (campaign.id() != null) localIds.add(campaign.id());
        List<CampaignRecord> elsewhere = new ArrayList<>();
        for (CampaignRecord record : directory.campaignsOf(user)) {
            if (record.isDm(user) && !localIds.contains(record.id)) elsewhere.add(record);
        }
        return new Listing(localListing.mine(), localListing.unclaimed(), elsewhere);
    }

    /** Registers an unclaimed local campaign with {@code user} as its author DM. */
    public static CampaignRecord claim(LocalCampaign campaign, String user, CampaignDirectory directory) {
        String id = CampaignMeta.ensureId(campaign.path());
        if (directory.isRegistered(id)) {
            CampaignRecord existing = directory.findForMember(id, user).orElseThrow(() ->
                new MembershipException("Only a Game Master of this campaign can do that."));
            if (existing.isDm(user)) return existing;
            throw new MembershipException("\"" + campaign.folder() + "\" is already registered by "
                + existing.author + ". Ask them to add you as a Game Master.");
        }
        return directory.register(id, campaign.folder(), user);
    }

    /** Whether {@code user} may open the campaign at {@code path} as a DM. */
    public static boolean canRunAsDm(Path path, String user, CampaignDirectory directory) {
        return CampaignMeta.idOf(path).flatMap(id -> directory.findForMember(id, user)).map(r -> r.isDm(user)).orElse(false);
    }

    /** A name match suggests a duplicate registration, never proof that two identities may be merged. */
    public static List<Entry> similarlyNamed(List<Entry> local, CampaignRecord online) {
        return local.stream().filter(entry -> sameName(entry.record().name, online.name)
            || sameName(entry.local().folder(), online.name)).toList();
    }

    private static boolean sameName(String a, String b) {
        return a != null && b != null && !a.isBlank() && a.strip().equalsIgnoreCase(b.strip());
    }

    /** Removes directory metadata only. Re-read disk identities to protect a copy downloaded since review. */
    public static void removeUnavailableRegistration(String id, List<LocalCampaign> local, String user,
                                                     CampaignDirectory directory) {
        for (LocalCampaign campaign : local) {
            if (CampaignMeta.idOf(campaign.path()).filter(id::equals).isPresent()) {
                throw new MembershipException("This registration belongs to a campaign on this device. It was not removed.");
            }
        }
        CampaignRecord record = directory.find(id).orElseThrow(() ->
            new MembershipException("This online registration no longer exists."));
        if (record.liveToken != null && !record.liveToken.isBlank()) {
            throw new MembershipException("End this campaign's online session before removing its registration.");
        }
        directory.delete(id, user);
    }
}
