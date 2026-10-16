package com.dnd.cloud;

import com.dnd.cloud.CampaignRecord.Role;
import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Who belongs to which campaign, kept in the shared {@link CloudStore}.
 *
 * <p>Rules: every campaign has an author DM. Only the author can make someone a DM, demote a
 * DM, or delete the campaign. Any DM can accept players, reject applications, remove players
 * and change the join code. Accounts join by applying with the campaign's join code and wait
 * for a DM to accept them.</p>
 *
 * <p>Layout: {@code campaigns/<id>} is the {@link CampaignRecord}; {@code joinCodes/<code>}
 * points at the id; {@code userCampaigns/<user>/<id>} and {@code userApplications/<user>/<id>}
 * index a user's memberships and pending applications so they can be listed quickly.</p>
 */
public class CampaignDirectory {
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CloudStore store;
    private final ObjectMapper mapper = JsonMappers.create()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CampaignDirectory(CloudStore store) {
        this.store = store;
    }

    public static CampaignDirectory shared() {
        return new CampaignDirectory(Cloud.store());
    }

    /** A rule violation or unknown campaign; the message is shown to the user as-is. */
    public static class MembershipException extends RuntimeException {
        public MembershipException(String message) {
            super(message);
        }
    }

    public Optional<CampaignRecord> find(String campaignId) {
        if (campaignId == null || campaignId.isBlank()) return Optional.empty();
        JsonNode node = store.get("campaigns/" + campaignId);
        if (node == null || !node.isObject()) return Optional.empty();
        try {
            CampaignRecord record = mapper.treeToValue(node, CampaignRecord.class);
            if (record.members == null) record.members = new java.util.LinkedHashMap<>();
            if (record.applications == null) record.applications = new java.util.LinkedHashMap<>();
            return Optional.of(record);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Registers a campaign with {@code author} as its first DM. */
    public CampaignRecord register(String campaignId, String name, String author) {
        if (find(campaignId).isPresent()) {
            throw new MembershipException("A campaign with this id is already registered.");
        }
        CampaignRecord record = new CampaignRecord();
        record.id = campaignId;
        record.name = name;
        record.author = author;
        record.members.put(CloudKeys.of(author), new CampaignRecord.Member(author, Role.DM));
        record.joinCode = newJoinCode();
        store.put("joinCodes/" + record.joinCode, campaignId);
        save(record);
        return record;
    }

    /** Campaigns {@code username} is a member of, in any role. */
    public List<CampaignRecord> campaignsOf(String username) {
        return recordsIndexedUnder("userCampaigns/" + CloudKeys.of(username), r -> r.isMember(username));
    }

    /** Campaigns {@code username} has applied to and is still waiting on. */
    public List<CampaignRecord> pendingApplicationsOf(String username) {
        return recordsIndexedUnder("userApplications/" + CloudKeys.of(username),
            r -> r.applications.containsKey(CloudKeys.of(username)));
    }

    private List<CampaignRecord> recordsIndexedUnder(String path, java.util.function.Predicate<CampaignRecord> stillValid) {
        List<CampaignRecord> result = new ArrayList<>();
        JsonNode index = store.get(path);
        if (index == null || !index.isObject()) return result;
        for (Iterator<String> it = index.fieldNames(); it.hasNext(); ) {
            find(it.next()).filter(stillValid).ifPresent(result::add);
        }
        result.sort((a, b) -> String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
        return result;
    }

    /** Files an application to the campaign whose join code is {@code joinCode}. */
    public CampaignRecord apply(String joinCode, String username) {
        String code = joinCode == null ? "" : joinCode.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
        JsonNode pointer = code.isEmpty() ? null : store.get("joinCodes/" + code);
        CampaignRecord record = pointer == null ? null : find(pointer.asText()).orElse(null);
        if (record == null || !code.equals(record.joinCode)) {
            throw new MembershipException("No campaign uses that join code. Check it with your DM.");
        }
        if (record.isMember(username)) {
            throw new MembershipException("You're already part of \"" + record.name + "\".");
        }
        String key = CloudKeys.of(username);
        if (record.applications.containsKey(key)) {
            throw new MembershipException("You've already applied to \"" + record.name + "\". Wait for a DM to accept you.");
        }
        record.applications.put(key, new CampaignRecord.Application(username));
        save(record);
        store.put("userApplications/" + key + "/" + record.id, true);
        return record;
    }

    /** Accepts an application. Any DM may accept a player; only the author may accept a DM. */
    public CampaignRecord accept(String campaignId, String actor, String applicant, Role role) {
        CampaignRecord record = require(campaignId);
        requireDm(record, actor);
        if (role == Role.DM) requireAuthor(record, actor, "Only the campaign's author can add Dungeon Masters.");
        String key = CloudKeys.of(applicant);
        CampaignRecord.Application application = record.applications.remove(key);
        if (application == null) throw new MembershipException(applicant + " has no pending application.");
        record.members.put(key, new CampaignRecord.Member(application.username, role));
        save(record);
        store.delete("userApplications/" + key + "/" + record.id);
        store.put("userCampaigns/" + key + "/" + record.id, role.name());
        return record;
    }

    public CampaignRecord reject(String campaignId, String actor, String applicant) {
        CampaignRecord record = require(campaignId);
        requireDm(record, actor);
        String key = CloudKeys.of(applicant);
        if (record.applications.remove(key) == null) {
            throw new MembershipException(applicant + " has no pending application.");
        }
        save(record);
        store.delete("userApplications/" + key + "/" + record.id);
        return record;
    }

    /** Promotes or demotes a member. Only the author may do this, and never to themselves. */
    public CampaignRecord setRole(String campaignId, String actor, String member, Role role) {
        CampaignRecord record = require(campaignId);
        requireAuthor(record, actor, "Only the campaign's author can change who is a Dungeon Master.");
        if (record.isAuthor(member)) throw new MembershipException("The author is always a Dungeon Master.");
        String key = CloudKeys.of(member);
        CampaignRecord.Member m = record.members.get(key);
        if (m == null) throw new MembershipException(member + " is not part of this campaign.");
        m.role = role;
        save(record);
        store.put("userCampaigns/" + key + "/" + record.id, role.name());
        return record;
    }

    /** Removes a member. The author can remove anyone else; other DMs can remove players. */
    public CampaignRecord remove(String campaignId, String actor, String member) {
        CampaignRecord record = require(campaignId);
        requireDm(record, actor);
        if (record.isAuthor(member)) throw new MembershipException("The campaign's author can't be removed.");
        Role role = record.roleOf(member);
        if (role == null) throw new MembershipException(member + " is not part of this campaign.");
        if (role == Role.DM) requireAuthor(record, actor, "Only the campaign's author can remove a Dungeon Master.");
        dropMember(record, member);
        return record;
    }

    /** A member leaves on their own. The author can't leave (delete the campaign instead). */
    public void leave(String campaignId, String member) {
        CampaignRecord record = require(campaignId);
        if (record.isAuthor(member)) throw new MembershipException("The author can't leave their own campaign.");
        if (!record.isMember(member)) throw new MembershipException("You're not part of this campaign.");
        dropMember(record, member);
    }

    private void dropMember(CampaignRecord record, String member) {
        String key = CloudKeys.of(member);
        record.members.remove(key);
        save(record);
        store.delete("userCampaigns/" + key + "/" + record.id);
    }

    public CampaignRecord regenerateJoinCode(String campaignId, String actor) {
        CampaignRecord record = require(campaignId);
        requireDm(record, actor);
        if (record.joinCode != null) store.delete("joinCodes/" + record.joinCode);
        record.joinCode = newJoinCode();
        store.put("joinCodes/" + record.joinCode, record.id);
        save(record);
        return record;
    }

    /** Publishes (or clears, with {@code null}) the live session token for members. */
    public void setLiveToken(String campaignId, String actor, String token) {
        CampaignRecord record = require(campaignId);
        requireDm(record, actor);
        if (token == null) {
            store.delete("campaigns/" + campaignId + "/liveToken");
        } else {
            store.put("campaigns/" + campaignId + "/liveToken", token);
        }
    }

    public void rename(String campaignId, String actor, String newName) {
        CampaignRecord record = require(campaignId);
        requireDm(record, actor);
        store.put("campaigns/" + campaignId + "/name", newName);
    }

    /** Deletes the campaign's record and every index pointing at it. Author only. */
    public void delete(String campaignId, String actor) {
        CampaignRecord record = require(campaignId);
        requireAuthor(record, actor, "Only the campaign's author can delete it.");
        for (String key : record.members.keySet()) store.delete("userCampaigns/" + key + "/" + campaignId);
        for (String key : record.applications.keySet()) store.delete("userApplications/" + key + "/" + campaignId);
        if (record.joinCode != null) store.delete("joinCodes/" + record.joinCode);
        store.delete("campaigns/" + campaignId);
    }

    private CampaignRecord require(String campaignId) {
        return find(campaignId).orElseThrow(() -> new MembershipException("That campaign no longer exists."));
    }

    private static void requireDm(CampaignRecord record, String actor) {
        if (!record.isDm(actor)) throw new MembershipException("Only a Dungeon Master of this campaign can do that.");
    }

    private static void requireAuthor(CampaignRecord record, String actor, String message) {
        if (!record.isAuthor(actor)) throw new MembershipException(message);
    }

    private void save(CampaignRecord record) {
        store.put("campaigns/" + record.id, record);
        for (var entry : record.members.entrySet()) {
            store.put("userCampaigns/" + entry.getKey() + "/" + record.id, entry.getValue().role.name());
        }
    }

    private String newJoinCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            String code = sb.toString();
            if (store.get("joinCodes/" + code) == null) return code;
        }
        throw new MembershipException("Could not generate a unique join code; try again.");
    }
}
