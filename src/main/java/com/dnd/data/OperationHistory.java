package com.dnd.data;

import com.dnd.cloud.CampaignMeta;
import com.dnd.model.session.TrackedCreature;
import com.dnd.security.PrivateAccount;
import com.dnd.security.PrivateSessionFiles;
import com.dnd.sync.ManualFiles;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Device-local mechanical corrections, never storyline text, account credentials or chat. */
public final class OperationHistory {
    public enum Kind { PLAYERS, MAPS, ROSTER, PRIVATE_ROSTER }
    public record Delta(String map, String subject, String field, boolean beforePresent, JsonNode before,
                        boolean afterPresent, JsonNode after) { }
    public record Action(int version, String id, long at, String path, Kind kind, List<Delta> changes,
                         String encryptedChanges, String beforeHash, String afterHash, String undoOf) {
        @Override public String toString() { return java.time.Instant.ofEpochMilli(at) + " - " + kind; }
    }
    private record Subject(String map, String id) { }
    private record Source(Path file, JsonNode json, String privateRaw, String revision) { }
    private static final Object LOCK = new Object();
    private static final int LIMIT = 500;
    private static final Set<String> VITALS = Set.of("maxHitPoints", "currentHitPoints", "maxMana",
        "currentMana", "activeEffects");
    private static final Set<String> PLAYER_FIELDS = Set.of("maxHitPoints", "currentHitPoints", "maxMana",
        "currentMana", "activeEffects", "classId", "raceId", "level", "xp", "stats", "skills", "items",
        "spells", "spellSlots", "castingResource", "featureUses", "cooldowns", "knownBlueprints", "furnaceFuel",
        "upgrades", "sheet/armorClass", "sheet/initiative", "sheet/speed", "sheet/proficiencyBonus",
        "sheet/passivePerception", "sheet/savingThrowProficiencies", "sheet/hitDice", "sheet/temporaryHitPoints",
        "sheet/deathSaveSuccesses", "sheet/deathSaveFailures", "sheet/inspiration", "sheet/spellcastingAbility",
        "sheet/spellSaveDc", "sheet/spellAttackBonus", "sheet/copper", "sheet/silver", "sheet/electrum",
        "sheet/gold", "sheet/platinum");
    private static final Set<String> COMBAT_FIELDS = Set.of("maxHitPoints", "currentHitPoints", "maxMana",
        "currentMana", "gold", "initiative", "deathSaveFailures", "deathSaveSuccesses", "downed", "dead",
        "conditions", "inInitiative", "walkSpeed", "climbSpeed", "swimSpeed", "speedSeeded", "movementUsed", "activeEffects",
        "cooldowns", "featureUses", "resistances", "enduranceFeature", "featureSpeedBonus", "spellSlots", "castingResource");
    private final Path campaign;
    private final Path directory;
    private final String campaignId;

    public OperationHistory(Path campaign) {
        this.campaign = campaign.toAbsolutePath().normalize();
        campaignId = CampaignMeta.idOf(this.campaign).orElseThrow(
            () -> new IllegalStateException("Register this campaign before using action history."));
        directory = CampaignRestorePoints.deviceRoot(this.campaign).resolveSibling("action-history")
            .resolve(StorylineDrafts.hash(campaignId));
        checkLinks(directory);
    }

    public static void writeCatalogue(Path file, byte[] bytes) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        String name = absolute.getFileName().toString();
        String parent = absolute.getParent().getFileName() == null ? "" : absolute.getParent().getFileName().toString();
        Kind kind = name.equals("players.json") && parent.equals("players")
            ? Kind.PLAYERS : name.equals("maps.json") && parent.equals("world") ? Kind.MAPS : null;
        Path root = absolute.getParent().getParent();
        if (kind == null || !Files.exists(absolute) || CampaignMeta.idOf(root).isEmpty()) {
            atomicWrite(absolute, bytes);
            return;
        }

        synchronized (LOCK) {
            OperationHistory history = new OperationHistory(root);
            byte[] previous = Files.readAllBytes(absolute);
            var before = JsonMappers.create().readTree(previous);
            var after = JsonMappers.create().readTree(bytes);
            history.save(root.relativize(absolute).toString().replace('\\', '/'), kind,
                differences(kind, before, after), null, absolute, bytes, null, null, hash(previous));
        }
    }

    static void ensureMapIds(Path file) throws IOException {
        synchronized (LOCK) {
            if (!Files.isRegularFile(file)) return;
            checkLinks(file);
            JsonNode root = JsonMappers.create().readTree(file.toFile());
            boolean changed = false;
            for (JsonNode map : root.path("maps")) {
                Set<String> seen = new HashSet<>();
                for (JsonNode row : map.path("grid")) for (JsonNode cell : row) for (JsonNode token : cell.path("occupants")) {
                    if (!(token instanceof ObjectNode object)
                        || !Set.of("player", "npc", "monster", "beast").contains(token.path("kind").asText())) continue;
                    String id = token.path("instanceId").asText();
                    if (id.isBlank()) {
                        id = UUID.randomUUID().toString();
                        object.put("instanceId", id);
                        changed = true;
                    }
                    if (!seen.add(id)) throw new IOException("Duplicate battle-token identity.");
                }
            }
            if (changed) writeCatalogue(file, JsonMappers.create().writeValueAsBytes(root));
        }
    }

    public static void writeRoster(Path sessionFile, List<TrackedCreature> roster, PrivateAccount account) throws IOException {
        synchronized (LOCK) {
            Path file = sessionFile.toAbsolutePath().normalize();
            Path root = file.getParent();
            while (root != null && (root.getFileName() == null || !root.getFileName().toString().equals("storyline"))) root = root.getParent();
            root = root == null ? null : root.getParent();
            String raw = Files.exists(file) ? Files.readString(file) : "";
            boolean encrypted = PrivateSessionFiles.isProtected(raw);
            Path target = encrypted ? file : SessionRosterStore.rosterFile(file);
            byte[] previous = Files.exists(target) ? Files.readAllBytes(target) : null;
            JsonNode before = encrypted ? JsonMappers.create().valueToTree(PrivateSessionFiles.read(raw, account).roster())
                : previous != null ? JsonMappers.create().readTree(previous) : JsonMappers.create().createArrayNode();
            JsonNode after = JsonMappers.create().valueToTree(roster == null ? List.of() : roster);
            byte[] next = rosterBytes(raw, after, account);
            if (root == null || CampaignMeta.idOf(root).isEmpty()) { atomicWrite(target, next); return; }
            Kind kind = encrypted ? Kind.PRIVATE_ROSTER : Kind.ROSTER;
            new OperationHistory(root).save(root.relativize(file).toString().replace('\\', '/'), kind,
                differences(kind, before, after), encrypted ? raw : null, target, next, account, null,
                encrypted ? hash(raw.getBytes(StandardCharsets.UTF_8)) : previous == null ? "missing" : hash(previous));
        }
    }

    public List<Action> list() throws IOException {
        synchronized (LOCK) {
            checkCampaign();
            List<Path> committed = recover();
            if (!Files.isDirectory(directory)) return List.of();
            List<Action> result = new ArrayList<>();
            for (Path file : committed) {
                if (Files.size(file) > 16L * 1024 * 1024) throw new IOException("Action record is too large.");
                Action action = JsonMappers.create().readValue(file.toFile(), Action.class);
                validate(action);
                if (!file.equals(entry(action.id(), ".action"))) throw new IOException("Invalid action record.");
                result.add(action);
            }
            return result.stream().sorted(Comparator.comparingLong(Action::at).reversed()).toList();
        }
    }

    public List<Action> interrupted() throws IOException {
        synchronized (LOCK) {
            checkCampaign();
            List<Action> result = new ArrayList<>();
            for (Path pending : entries(".pending")) {
                Action action = readPending(pending);
                String current = fingerprint(target(action));
                if (!current.equals(action.beforeHash()) && !current.equals(action.afterHash())) result.add(action);
            }
            return List.copyOf(result);
        }
    }

    /** Explicitly abandons uncertain journal entries without modifying any campaign files. */
    public void discardInterrupted(List<Action> reviewed) throws IOException {
        synchronized (LOCK) {
            checkCampaign();
            for (Action action : reviewed) {
                validate(action);
                if (!action.equals(readPending(entry(action.id(), ".pending")))) throw new IOException("Interrupted action changed.");
                String current = fingerprint(target(action));
                if (current.equals(action.beforeHash()) || current.equals(action.afterHash())) {
                    throw new IOException("Interrupted action changed. Refresh action history before continuing.");
                }
            }
            for (Action action : reviewed) Files.delete(entry(action.id(), ".pending"));
        }
    }

    public List<Delta> changes(Action action, PrivateAccount account) throws IOException {
        synchronized (LOCK) {
            validate(action);
            return readChanges(action, source(action, account), account);
        }
    }

    private List<Delta> readChanges(Action action, Source source, PrivateAccount account) throws IOException {
        List<Delta> changes;
        if (action.kind() != Kind.PRIVATE_ROSTER) changes = action.changes();
        else {
            if (source.privateRaw() == null) throw new SecurityException("The private encounter identity changed.");
            var current = PrivateSessionFiles.verified(source.privateRaw(), account);
            var saved = PrivateSessionFiles.verified(action.encryptedChanges(), account);
            if (!current.header().fileId().equals(saved.header().fileId())
                || !current.header().ownerUid().equals(saved.header().ownerUid())) {
                throw new SecurityException("The private encounter identity changed.");
            }
            changes = JsonMappers.create().readValue(PrivateSessionFiles.read(action.encryptedChanges(), account).text(),
                new TypeReference<List<Delta>>() { });
        }
        if (changes == null || changes.stream().anyMatch(d -> d == null || d.subject() == null
            || !fields(action.kind()).contains(d.field()))) throw new IOException("Invalid action changes.");
        return List.copyOf(changes);
    }

    public void undo(Action action, PrivateAccount account) throws IOException {
        synchronized (LOCK) {
            var actions = list();
            if (!actions.contains(action) || actions.stream().anyMatch(a -> action.id().equals(a.undoOf()))) {
                throw new IOException("This action is unavailable or has already been undone.");
            }
            Source source = source(action, account);
            List<Delta> changes = readChanges(action, source, account);
            Map<Subject, ObjectNode> current = subjects(action.kind(), source.json());
            for (Delta delta : changes) {
                ObjectNode entity = current.get(new Subject(delta.map(), delta.subject()));
                if (entity == null || !fields(action.kind()).contains(delta.field())
                    || !matches(value(entity, delta.field()), delta.afterPresent(), delta.after())) {
                    throw new IOException("These values changed since this action. Nothing was undone.");
                }
            }
            for (Delta delta : changes) put(current.get(new Subject(delta.map(), delta.subject())), delta.field(),
                delta.beforePresent() ? delta.before() : null);
            byte[] bytes = action.kind() == Kind.PLAYERS || action.kind() == Kind.MAPS
                ? JsonMappers.create().writeValueAsBytes(source.json())
                : rosterBytes(source.privateRaw() == null ? "" : source.privateRaw(), source.json(), account);
            Kind kind = source.privateRaw() != null ? Kind.PRIVATE_ROSTER : action.kind();
            List<Delta> reversed = changes.stream().map(d -> new Delta(d.map(), d.subject(), d.field(),
                d.afterPresent(), d.after(), d.beforePresent(), d.before())).toList();
            save(action.path(), kind, reversed, source.privateRaw(), source.file(), bytes, account, action.id(), source.revision());
        }
    }

    private void save(String path, Kind kind, List<Delta> changes, String privateRaw, Path target,
                      byte[] bytes, PrivateAccount account, String undoOf, String expected) throws IOException {
        checkCampaign();
        List<Path> committed = recover();
        if (!fingerprint(target).equals(expected)) throw new IOException("Campaign changed before saving the action.");
        if (changes.isEmpty()) { atomicWrite(target, bytes); return; }
        Files.createDirectories(directory);
        long latest = committed.stream().mapToLong(p -> Long.parseLong(p.getFileName().toString().split("-")[0])).max().orElse(0);
        long now = Math.max(System.currentTimeMillis(), latest + 1);
        String id = now + "-" + UUID.randomUUID();
        String sealed = kind == Kind.PRIVATE_ROSTER
            ? PrivateSessionFiles.write(privateRaw, new PrivateSessionFiles.Content(
                JsonMappers.create().writeValueAsString(changes), List.of()), account) : null;
        Action action = new Action(1, id, now, path, kind, sealed == null ? List.copyOf(changes) : null,
            sealed, expected, bytes == null ? "missing" : hash(bytes), undoOf);
        Path pending = entry(id, ".pending");
        byte[] record = JsonMappers.create().writeValueAsBytes(action);
        if (record.length > 16L * 1024 * 1024) throw new IOException("Action record is too large.");
        atomicWrite(pending, record);
        if (!fingerprint(target).equals(action.beforeHash())) {
            Files.delete(pending);
            throw new IOException("Campaign changed before saving the action.");
        }
        atomicWrite(target, bytes);
        try {
            Path completed = entry(id, ".action");
            Files.move(pending, completed, StandardCopyOption.ATOMIC_MOVE);
            List<Path> updated = new ArrayList<>(committed);
            updated.add(completed);
            prune(updated);
        } catch (IOException e) {
            throw new IOException("Change saved, but action history could not be finalized. Reopen action history before retrying.", e);
        }
    }

    private Source source(Action action, PrivateAccount account) throws IOException {
        checkCampaign();
        Path file = ManualFiles.resolve(campaign, action.path());
        if (action.kind() == Kind.PLAYERS || action.kind() == Kind.MAPS) {
            byte[] raw = Files.readAllBytes(file);
            return new Source(file, JsonMappers.create().readTree(raw), null, hash(raw));
        }
        String raw = Files.readString(file);
        if (PrivateSessionFiles.isProtected(raw)) {
            return new Source(file, JsonMappers.create().valueToTree(PrivateSessionFiles.read(raw, account).roster()),
                raw, hash(raw.getBytes(StandardCharsets.UTF_8)));
        }
        Path roster = SessionRosterStore.rosterFile(file);
        byte[] bytes = Files.exists(roster) ? Files.readAllBytes(roster) : null;
        return new Source(roster, bytes != null ? JsonMappers.create().readTree(bytes)
            : JsonMappers.create().createArrayNode(), null, bytes == null ? "missing" : hash(bytes));
    }

    private List<Path> recover() throws IOException {
        checkLinks(directory);
        for (Path pending : entries(".pending")) {
            Action action = readPending(pending);
            String current = fingerprint(target(action));
            if (current.equals(action.afterHash())) Files.move(pending, entry(action.id(), ".action"), StandardCopyOption.ATOMIC_MOVE);
            else if (current.equals(action.beforeHash())) Files.delete(pending);
            else throw new IOException("An interrupted action has conflicting file changes. Open Action history and choose Resolve interrupted actions.");
        }
        return prune(entries(".action"));
    }

    private Path target(Action action) throws IOException {
        Path file = ManualFiles.resolve(campaign, action.path());
        return action.kind() == Kind.ROSTER ? SessionRosterStore.rosterFile(file) : file;
    }

    private Action readPending(Path pending) throws IOException {
        if (Files.size(pending) > 16L * 1024 * 1024) throw new IOException("Action record is too large.");
        Action action = JsonMappers.create().readValue(pending.toFile(), Action.class);
        validate(action);
        if (!pending.equals(entry(action.id(), ".pending"))) throw new IOException("Invalid interrupted action record.");
        return action;
    }

    private List<Path> prune(List<Path> entries) throws IOException {
        List<Path> points = entries.stream().sorted(Comparator.reverseOrder()).toList();
        int keep = Math.min(LIMIT, points.size());
        for (Path expired : points.subList(keep, points.size())) Files.delete(expired);
        return points.subList(0, keep);
    }

    private List<Path> entries(String suffix) throws IOException {
        checkLinks(directory);
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix)).peek(p -> {
                if (Files.isSymbolicLink(p)) throw new SecurityException("Action history links are not supported.");
            }).toList();
        }
    }

    private Path entry(String id, String suffix) throws IOException {
        if (id == null || !id.matches("[0-9]{1,19}-[a-f0-9-]{36}")) throw new IOException("Invalid action identifier.");
        return directory.resolve(id + suffix);
    }

    private void validate(Action action) throws IOException {
        if (action == null || action.version() != 1 || action.kind() == null || action.path() == null
            || action.beforeHash() == null || action.afterHash() == null
            || (action.kind() == Kind.PRIVATE_ROSTER ? action.encryptedChanges() == null : action.changes() == null)) {
            throw new IOException("Invalid action record.");
        }
        entry(action.id(), ".action");
        if (action.at() < 0 || !action.id().startsWith(action.at() + "-")
            || !action.beforeHash().matches("missing|[a-f0-9]{64}") || !action.afterHash().matches("missing|[a-f0-9]{64}")) {
            throw new IOException("Invalid action record.");
        }
        if (action.kind() == Kind.PLAYERS && !action.path().equals("players/players.json")
            || action.kind() == Kind.MAPS && !action.path().equals("world/maps.json")
            || (action.kind() == Kind.ROSTER || action.kind() == Kind.PRIVATE_ROSTER) && !action.path().startsWith("storyline/")) {
            throw new IOException("Invalid action target.");
        }
    }

    private void checkCampaign() throws IOException {
        if (!CampaignMeta.idOf(campaign).filter(campaignId::equals).isPresent()) throw new IOException("Campaign identity changed.");
    }

    private static List<Delta> differences(Kind kind, JsonNode before, JsonNode after) throws IOException {
        Map<Subject, ObjectNode> a = subjects(kind, before), b = subjects(kind, after);
        List<Delta> changes = new ArrayList<>();
        for (var entry : a.entrySet()) {
            ObjectNode next = b.get(entry.getKey());
            if (next == null) continue;
            for (String field : new TreeSet<>(fields(kind))) {
                JsonNode oldValue = value(entry.getValue(), field), newValue = value(next, field);
                if (!Objects.equals(oldValue, newValue)) changes.add(new Delta(entry.getKey().map(), entry.getKey().id(),
                    field, oldValue != null, oldValue, newValue != null, newValue));
            }
        }
        return changes;
    }

    private static Set<String> fields(Kind kind) {
        if (kind == Kind.PLAYERS) return PLAYER_FIELDS;
        if (kind == Kind.MAPS) return COMBAT_FIELDS;
        Set<String> roster = new HashSet<>(VITALS);
        roster.add("armorClass");
        return roster;
    }

    private static Map<Subject, ObjectNode> subjects(Kind kind, JsonNode root) throws IOException {
        Map<Subject, ObjectNode> result = new LinkedHashMap<>();
        if (kind == Kind.MAPS) {
            for (JsonNode map : root.path("maps")) {
                for (JsonNode row : map.path("grid")) for (JsonNode cell : row) for (JsonNode token : cell.path("occupants")) {
                    String id = token.path("instanceId").asText();
                    if (!id.isBlank() && Set.of("npc", "monster", "beast").contains(token.path("kind").asText())
                        && token.path("combat").isObject()) {
                        add(result, new Subject(map.path("id").asText(), id), token.get("combat"));
                    }
                }
            }
        } else {
            JsonNode list = kind == Kind.PLAYERS ? root.path("players") : root;
            for (JsonNode entity : list) {
                String id = entity.path("id").asText();
                if (!id.isBlank()) add(result, new Subject(null, id), entity);
            }
        }
        return result;
    }

    private static void add(Map<Subject, ObjectNode> map, Subject subject, JsonNode node) throws IOException {
        if (!(node instanceof ObjectNode object) || map.putIfAbsent(subject, object) != null) {
            throw new IOException("Ambiguous action subject.");
        }
    }

    private static JsonNode value(ObjectNode node, String field) {
        String[] parts = field.split("/");
        return parts.length == 1 ? node.get(field) : node.path(parts[0]).get(parts[1]);
    }

    private static void put(ObjectNode node, String field, JsonNode value) {
        String[] parts = field.split("/");
        ObjectNode target = parts.length == 1 ? node : node.withObject("/" + parts[0]);
        if (value == null) target.remove(parts[parts.length - 1]);
        else target.set(parts[parts.length - 1], value);
    }

    private static boolean matches(JsonNode value, boolean present, JsonNode expected) {
        return (value != null) == present && (!present || Objects.equals(value, expected));
    }

    private static byte[] rosterBytes(String raw, JsonNode roster, PrivateAccount account) throws IOException {
        if (!PrivateSessionFiles.isProtected(raw)) return roster.isEmpty() ? null : JsonMappers.create().writeValueAsBytes(roster);
        var current = PrivateSessionFiles.read(raw, account);
        List<TrackedCreature> creatures = JsonMappers.create().convertValue(roster, new TypeReference<List<TrackedCreature>>() { });
        return PrivateSessionFiles.write(raw, new PrivateSessionFiles.Content(current.text(), creatures), account)
            .getBytes(StandardCharsets.UTF_8);
    }

    private static String fingerprint(Path file) throws IOException { return Files.exists(file) ? ManualFiles.hash(file) : "missing"; }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void checkLinks(Path file) {
        for (Path p = file; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) {
            throw new SecurityException("Action history links are not supported.");
        }
    }

    private static void atomicWrite(Path file, byte[] bytes) throws IOException {
        checkLinks(file);
        if (bytes == null) { Files.deleteIfExists(file); return; }
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".almerion-action-", ".tmp");
        try {
            Files.write(temp, bytes);
            try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
