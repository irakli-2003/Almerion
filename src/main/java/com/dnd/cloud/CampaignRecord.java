package com.dnd.cloud;

import java.util.LinkedHashMap;
import java.util.Map;

/** A campaign's membership record in the shared directory. */
public class CampaignRecord {
    public enum Role { DM, PLAYER }

    public static class Member {
        public String username;
        public Role role;
        public long joinedAtMillis;

        public Member() {
        }

        public Member(String username, Role role) {
            this.username = username;
            this.role = role;
            this.joinedAtMillis = System.currentTimeMillis();
        }
    }

    public static class Application {
        public String username;
        public long requestedAtMillis;

        public Application() {
        }

        public Application(String username) {
            this.username = username;
            this.requestedAtMillis = System.currentTimeMillis();
        }
    }

    public String id;
    public String name;
    /** Username of the DM who registered the campaign; the only one who can add or remove DMs. */
    public String author;
    public String joinCode;
    /** Keyed by {@link CloudKeys#of(String) username key}. */
    public Map<String, Member> members = new LinkedHashMap<>();
    public Map<String, Application> applications = new LinkedHashMap<>();
    /** Session token of the DM's live online session, so members can join without pasting it. */
    public String liveToken;

    public Role roleOf(String username) {
        if (username == null || members == null) return null;
        Member m = members.get(CloudKeys.of(username));
        return m == null ? null : m.role;
    }

    public boolean isMember(String username) {
        return roleOf(username) != null;
    }

    public boolean isDm(String username) {
        return roleOf(username) == Role.DM;
    }

    public boolean isAuthor(String username) {
        return username != null && author != null && CloudKeys.of(author).equals(CloudKeys.of(username));
    }
}
