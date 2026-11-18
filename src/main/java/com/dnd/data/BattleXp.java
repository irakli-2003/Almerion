package com.dnd.data;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BattleXp {
    private BattleXp() { }

    public record Award(boolean applied, int share, int remainder, Map<String, Integer> totals) { }

    public static Award award(CampaignRepositories repos, String rewardId, int kill, int shared,
                              String finisher, Collection<String> present) {
        if (rewardId == null || rewardId.isBlank() || kill < 0 || shared < 0) {
            throw new IllegalArgumentException("Invalid battle XP reward.");
        }
        var totals = new LinkedHashMap<String, Integer>();
        var share = new int[2];
        boolean applied = repos.players().changeCatalogue(roster -> {
            if (roster.getBattleXpReceipts().contains(rewardId)) return false;
            var party = roster.getPlayers().stream().filter(pc -> present.contains(pc.getId())).toList();
            if (party.isEmpty()) throw new IllegalStateException("No campaign player characters are on this map.");
            if (kill > 0 && party.stream().noneMatch(pc -> pc.getId().equals(finisher))) {
                throw new IllegalArgumentException("Choose a finishing player character on this map.");
            }
            share[0] = shared / party.size();
            share[1] = shared % party.size();
            for (var pc : party) {
                long gained = (long) share[0] + (pc.getId().equals(finisher) ? kill : 0);
                if (gained == 0) continue;
                long total = pc.getXp() + gained;
                if (total > Integer.MAX_VALUE) throw new IllegalStateException(pc.getName() + " would exceed the XP limit.");
                pc.setXp((int) total);
                totals.put(pc.getId(), pc.getXp());
            }
            roster.getBattleXpReceipts().add(rewardId);
            return true;
        });
        return new Award(applied, share[0], share[1], Map.copyOf(totals));
    }
}
