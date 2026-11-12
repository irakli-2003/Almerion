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
import java.util.Map;
import java.util.LinkedHashMap;

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
            throw new CloudStore.CloudException("The campaign membership record is malformed.", false, e);
        }
    }

    /** A claimed ID is visible without exposing its owner, members, invite, or live token. */
    public boolean isRegistered(String campaignId) {
        return campaignId != null && store.get("campaigns/" + campaignId + "/id") != null;
    }

    public Optional<CampaignRecord> findForMember(String campaignId, String username) {
        if (campaignId == null || username == null || username.isBlank()) return Optional.empty();
        return store.get("campaigns/" + campaignId + "/members/" + CloudKeys.of(username)) == null
            ? Optional.empty() : find(campaignId);
    }

    /** Registers a campaign with {@code author} as its first DM. */
    public CampaignRecord register(String campaignId, String name, String author) {
        if (isRegistered(campaignId)) {
            throw new MembershipException("A campaign with this id is already registered.");
        }
        CampaignRecord record = new CampaignRecord();
        record.id = campaignId;
        record.name = name;
        record.author = author;
        record.authorKey = CloudKeys.of(author);
        record.members.put(CloudKeys.of(author), new CampaignRecord.Member(author, Role.DM));
        record.joinCode = newJoinCode();
        store.update(Map.of("campaigns/" + campaignId, record,
            "joinCodes/" + record.joinCode, invitation(record),
            "userCampaigns/" + record.authorKey + "/" + campaignId, Role.DM.name()));
        return record;
    }

    /** Campaigns {@code username} is a member of, in any role. */
    public List<CampaignRecord> campaignsOf(String username) {
        return recordsIndexedUnder("userCampaigns/" + CloudKeys.of(username), username);
    }

    /** Campaigns {@code username} has applied to and is still waiting on. */
    public List<CampaignRecord> pendingApplicationsOf(String username) {
        String key = CloudKeys.of(username);
        JsonNode index = store.get("userApplications/" + key);
        List<CampaignRecord> result = new ArrayList<>();
        if (index == null) return result;
        index.fields().forEachRemaining(entry -> {
            if (store.get("campaigns/" + entry.getKey() + "/applications/" + key) != null) {
                CampaignRecord summary = summary(entry.getValue());
                summary.id = entry.getKey();
                result.add(summary);
            }
        });
        result.sort((a, b) -> String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
        return result;
    }

    private List<CampaignRecord> recordsIndexedUnder(String path, String username) {
        List<CampaignRecord> result = new ArrayList<>();
        JsonNode index = store.get(path);
        if (index == null || !index.isObject()) return result;
        for (Iterator<String> it = index.fieldNames(); it.hasNext(); ) {
            findForMember(it.next(), username).filter(r -> r.isMember(username)).ifPresent(result::add);
        }
        result.sort((a, b) -> String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
        return result;
    }

    /** Files an application to the campaign whose join code is {@code joinCode}. */
    public CampaignRecord apply(String joinCode, String username) {
        String code = joinCode == null ? "" : joinCode.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
        JsonNode pointer = code.isEmpty() ? null : store.get("joinCodes/" + code);
        CampaignRecord record = pointer == null ? null : summary(pointer);
        if (record == null) {
            throw new MembershipException("No campaign uses that join code. Check it with your GM.");
        }
        String key = CloudKeys.of(username);
        if (store.get("campaigns/" + record.id + "/members/" + key) != null) {
            throw new MembershipException("You're already part of \"" + record.name + "\".");
        }
        if (store.get("campaigns/" + record.id + "/applications/" + key) != null) {
            throw new MembershipException("You've already applied to \"" + record.name + "\". Wait for a GM to accept you.");
        }
        record.joinCode = code;
        CampaignRecord.Application application = new CampaignRecord.Application(username);
        application.joinCode = code;
        record.applications.put(key, application);
        store.update(Map.of("campaigns/" + record.id + "/applications/" + key, application,
            "userApplications/" + key + "/" + record.id, invitation(record)));
        return record;
    }

    /** Accepts an application. Any DM may accept a player; only the author may accept a DM. */
    public CampaignRecord accept(String campaignId, String actor, String applicant, Role role) {
        CampaignRecord record = require(campaignId);
        requireDm(record, actor);
        if (role == Role.DM) requireAuthor(record, actor, "Only the campaign's author can add Game Masters.");
        String key = CloudKeys.of(applicant);
        CampaignRecord.Application application = record.applications.remove(key);
        if (application == null) throw new MembershipException(applicant + " has no pending application.");
        CampaignRecord.Member member = new CampaignRecord.Member(application.username, role);
        record.members.put(key, member);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("campaigns/" + record.id + "/members/" + key, member);
        changes.put("campaigns/" + record.id + "/applications/" + key, null);
        changes.put("userApplications/" + key + "/" + record.id, null);
        changes.put("userCampaigns/" + key + "/" + record.id, role.name());
        store.update(changes);
        return record;
    }

    public CampaignRecord reject(String campaignId, String actor, String applicant) {
        CampaignRecord record = require(campaignId);
        requireDm(record, actor);
        String key = CloudKeys.of(applicant);
        if (record.applications.remove(key) == null) {
            throw new MembershipException(applicant + " has no pending application.");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("campaigns/" + record.id + "/applications/" + key, null);
        changes.put("userApplications/" + key + "/" + record.id, null);
        store.update(changes);
        return record;
    }

    /** Promotes or demotes a member. Only the author may do this, and never to themselves. */
    public CampaignRecord setRole(String campaignId, String actor, String member, Role role) {
        CampaignRecord record = require(campaignId);
        requireAuthor(record, actor, "Only the campaign's author can change who is a Game Master.");
        if (record.isAuthor(member)) throw new MembershipException("The author is always a Game Master.");
        String key = CloudKeys.of(member);
        CampaignRecord.Member m = record.members.get(key);
        if (m == null) throw new MembershipException(member + " is not part of this campaign.");
        m.role = role;
        store.update(Map.of("campaigns/" + record.id + "/members/" + key, m,
            "userCampaigns/" + key + "/" + record.id, role.name()));
        return record;
    }

    /** Removes a member. The author can remove anyone else; other DMs can remove players. */
    public CampaignRecord remove(String campaignId, String actor, String member) {
        CampaignRecord record = require(campaignId);
        requireDm(record, actor);
        if (record.isAuthor(member)) throw new MembershipException("The campaign's author can't be removed.");
        Role role = record.roleOf(member);
        if (role == null) throw new MembershipException(member + " is not part of this campaign.");
        if (role == Role.DM) requireAuthor(record, actor, "Only the campaign's author can remove a Game Master.");
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
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("campaigns/" + record.id + "/members/" + key, null);
        changes.put("userCampaigns/" + key + "/" + record.id, null);
        store.update(changes);
    }

    public CampaignRecord regenerateJoinCode(String campaignId, String actor) {
        CampaignRecord record = require(campaignId);
        requireDm(record, actor);
        String oldCode = record.joinCode;
        record.joinCode = newJoinCode();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("joinCodes/" + oldCode, null);
        changes.put("joinCodes/" + record.joinCode, invitation(record));
        changes.put("campaigns/" + campaignId + "/joinCode", record.joinCode);
        store.update(changes);
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
        record.name = newName;
        store.update(Map.of("campaigns/" + campaignId + "/name", newName,
            "joinCodes/" + record.joinCode, invitation(record)));
    }

    /** Deletes the campaign's record and every index pointing at it. Author only. */
    public void delete(String campaignId, String actor) {
        CampaignRecord record = require(campaignId);
        requireAuthor(record, actor, "Only the campaign's author can delete it.");
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : record.members.keySet()) changes.put("userCampaigns/" + key + "/" + campaignId, null);
        for (String key : record.applications.keySet()) changes.put("userApplications/" + key + "/" + campaignId, null);
        if (record.joinCode != null) changes.put("joinCodes/" + record.joinCode, null);
        changes.put("campaigns/" + campaignId, null);
        store.update(changes);
    }

    private CampaignRecord require(String campaignId) {
        return find(campaignId).orElseThrow(() -> new MembershipException("That campaign no longer exists."));
    }

    private static void requireDm(CampaignRecord record, String actor) {
        if (!record.isDm(actor)) throw new MembershipException("Only a Game Master of this campaign can do that.");
    }

    private static void requireAuthor(CampaignRecord record, String actor, String message) {
        if (!record.isAuthor(actor)) throw new MembershipException(message);
    }

    private static Map<String, String> invitation(CampaignRecord record) {
        return Map.of("id", record.id, "name", record.name, "author", record.author);
    }

    private static CampaignRecord summary(JsonNode node) {
        if (!node.isObject() || !node.path("id").isTextual() || !node.path("name").isTextual()
            || !node.path("author").isTextual()) {
            throw new CloudStore.CloudException("The campaign invitation is malformed.", false);
        }
        CampaignRecord record = new CampaignRecord();
        record.id = node.path("id").asText();
        record.name = node.path("name").asText();
        record.author = node.path("author").asText();
        record.authorKey = CloudKeys.of(record.author);
        return record;
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
