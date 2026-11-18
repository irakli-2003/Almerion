package com.dnd.data;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Live, player-safe turn hints. GM map data never travels with these. */
public final class BattleTurns {
    public record Hint(String turn, int remaining) { }
    public record View(String turn, int round, Map<String, Integer> distances) {
        public View { distances = Map.copyOf(distances); }
        public Hint forPlayer(String player) {
            Integer count = player == null ? null : distances.get(player);
            return count == null ? null : new Hint(turn, count);
        }
    }
    private record Entry(Object source, View view) { }
    private static final Map<Path, Entry> ACTIVE = new ConcurrentHashMap<>();

    private BattleTurns() { }

    public static View view(Path campaign) {
        Entry entry = campaign == null ? null : ACTIVE.get(key(campaign));
        return entry == null ? null : entry.view();
    }

    public static boolean publish(Path campaign, Object source, View view) {
        Entry next = new Entry(source, view);
        return !next.equals(ACTIVE.put(key(campaign), next));
    }

    public static boolean clear(Path campaign, Object source) {
        Path key = key(campaign);
        Entry current = ACTIVE.get(key);
        return current != null && current.source() == source && ACTIVE.remove(key, current);
    }

    public static String newTurn() { return UUID.randomUUID().toString(); }
    private static Path key(Path campaign) { return campaign.toAbsolutePath().normalize(); }
}
