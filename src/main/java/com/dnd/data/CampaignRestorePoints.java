package com.dnd.data;

import com.dnd.cloud.CampaignMeta;
import com.dnd.model.session.TrackedCreature;
import com.dnd.security.PrivateAccount;
import com.dnd.security.PrivateSessionFiles;
import com.dnd.sync.ManualFiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Local, bounded snapshots; restoring files never restores an old private-file grant. */
public final class CampaignRestorePoints {
    public record Point(int format, String id, long createdAt, String reason, String campaignPath,
                        String campaignId, String name, String creatorUid) {
        @Override public String toString() { return java.time.Instant.ofEpochMilli(createdAt) + " - " + reason; }
    }
    public record Plan(Point point, List<CampaignArchive.FileEntry> files, Map<String, String> baselines) {
        public Plan {
            files = List.copyOf(files);
            baselines = Map.copyOf(baselines);
        }
    }
    private record Change(String path, Path payload, Path original, String expected) { }
    private static final Object LOCK = new Object();
    private static final int LIMIT = 20;
    private static final long TEXT_LIMIT = 16L * 1024 * 1024;
    private static final String MISSING = "missing";
    private final Path campaign;
    private final Path directory;
    private final String campaignId;

    public CampaignRestorePoints(Path deviceRoot, Path campaign) {
        this(deviceRoot, campaign, null);
    }

    private CampaignRestorePoints(Path deviceRoot, Path campaign, String identity) {
        this.campaign = campaign.toAbsolutePath().normalize();
        Path root = deviceRoot.toAbsolutePath().normalize();
        if (root.startsWith(this.campaign)) throw new IllegalArgumentException("Restore points must stay outside the campaign.");
        checkLinks(this.campaign);
        if (identity == null && !Files.isDirectory(this.campaign)) {
            throw new IllegalArgumentException("A campaign identity is required to locate its restore points.");
        }
        campaignId = identity == null ? CampaignMeta.ensureId(this.campaign) : identity;
        directory = root.resolve(StorylineDrafts.hash(campaignId));
        checkLinks(directory);
    }

    public static CampaignRestorePoints forCampaign(Path campaign) {
        return new CampaignRestorePoints(deviceRoot(campaign), campaign);
    }

    public static CampaignRestorePoints forPoint(Path deviceRoot, Path destination, Point point) {
        return new CampaignRestorePoints(deviceRoot, destination, point.campaignId());
    }

    public static List<Point> discover(Path deviceRoot) throws IOException {
        Path root = deviceRoot.toAbsolutePath().normalize();
        checkLinks(root);
        if (!Files.isDirectory(root)) return List.of();
        List<Point> result = new ArrayList<>();
        try (var directories = Files.list(root)) {
            for (Path dir : directories.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}")).toList()) {
                checkLinks(dir);
                try (var entries = Files.list(dir)) {
                    Path first = entries.filter(p -> p.getFileName().toString().endsWith(".point")).findFirst().orElse(null);
                    if (first == null) continue;
                    Path metadata = first.resolve("point.json");
                    checkLinks(metadata);
                    if (!Files.isRegularFile(metadata) || Files.size(metadata) > 1024 * 1024) {
                        throw new IOException("Unreadable restore-point metadata.");
                    }
                    Point point = JsonMappers.create().readValue(metadata.toFile(), Point.class);
                    if (point == null || point.campaignPath() == null || point.campaignId() == null) {
                        throw new IOException("Unreadable restore-point metadata.");
                    }
                    var store = forPoint(root, Path.of(point.campaignPath()), point);
                    if (!store.directory.equals(dir)) throw new IOException("Restore-point identity mismatch.");
                    result.addAll(store.list());
                }
            }
        }
        return result.stream().sorted(Comparator.comparingLong(Point::createdAt).reversed()).toList();
    }

    public static Path deviceRoot(Path campaign) {
        Path root = campaign.toAbsolutePath().normalize();
        if (root.getParent().getFileName() != null
            && root.getParent().getFileName().toString().equals("custom-campaigns")) {
            Path data = root.getParent().getParent();
            if (!data.equals(AppHome.resolve("src", "main", "resources", "data").toAbsolutePath().normalize())) {
                return data.resolve("auth").resolve("restore-points");
            }
        }
        return AppHome.resolve("data", "auth", "restore-points");
    }

    public static void beforeIdentifiedCampaign(Path campaign, String reason) {
        if (CampaignMeta.idOf(campaign).isEmpty()) return;
        try {
            forCampaign(campaign).capture(reason);
        } catch (IOException e) {
            throw new DataAccessException("Could not create a restore point. No campaign files were changed.", e);
        }
    }

    public static void beforeEntityDeletion(Path file) {
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent.getFileName() != null && Set.of("world", "players").contains(parent.getFileName().toString())) {
            beforeIdentifiedCampaign(parent.getParent(), "Before deleting a catalogue entry");
        }
    }

    public Point capture(String reason) throws IOException {
        return capture(reason, null);
    }

    public Point capture(String reason, String creatorUid) throws IOException {
        synchronized (LOCK) {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A restore-point reason is required.");
            checkLinks(campaign);
            checkLinks(directory);
            if (!Files.isDirectory(campaign)) throw new IOException("The campaign folder is missing.");
            checkIdentity();
            Files.createDirectories(directory);
            List<Point> previous = list();
            long now = Math.max(System.currentTimeMillis(), previous.stream().mapToLong(Point::createdAt).max().orElse(0) + 1);
            String id = now + "-" + UUID.randomUUID();
            Path staging = Files.createTempDirectory(directory, ".capture-");
            try {
                CampaignArchive.captureAvailableFiles(campaign, staging.resolve("campaign.zip"));
                var snapshot = CampaignArchive.inspect(staging.resolve("campaign.zip"));
                Point point = new Point(1, id, now, reason, campaign.toString(), snapshot.campaignId(), snapshot.name(), creatorUid);
                Files.writeString(staging.resolve("point.json"), JsonMappers.create().writeValueAsString(point), StandardCharsets.UTF_8);
                force(staging.resolve("campaign.zip"));
                force(staging.resolve("point.json"));
                Files.move(staging, pointPath(id), StandardCopyOption.ATOMIC_MOVE);
                List<Point> retained = list();
                for (Point expired : retained.subList(Math.min(LIMIT, retained.size()), retained.size())) {
                    deleteOwnedDirectory(pointPath(expired.id()));
                }
                return point;
            } finally {
                if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) deleteOwnedDirectory(staging);
            }
        }
    }

    public List<Point> list() throws IOException {
        synchronized (LOCK) {
            checkLinks(directory);
            if (!Files.isDirectory(directory)) return List.of();
            List<Point> points = new ArrayList<>();
            try (var paths = Files.list(directory)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".point")).toList()) {
                    checkLinks(path);
                    Path metadata = path.resolve("point.json");
                    if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.size(metadata) > 1024 * 1024) {
                        throw new IOException("Unreadable restore-point metadata.");
                    }
                    checkLinks(metadata);
                    Point point = JsonMappers.create().readValue(metadata.toFile(), Point.class);
                    if (point == null || point.format() != 1 || point.createdAt() <= 0 || point.reason() == null
                        || point.campaignId() == null || point.name() == null
                        || point.campaignPath() == null || !campaignId.equals(point.campaignId()) || !pointPath(point.id()).equals(path)) {
                        throw new IOException("Restore point does not match this campaign.");
                    }
                    points.add(point);
                }
            }
            return points.stream().sorted(Comparator.comparingLong(Point::createdAt).reversed()).toList();
        }
    }

    public Plan plan(String id) throws IOException {
        synchronized (LOCK) {
            Point point = find(id);
            var snapshot = CampaignArchive.inspect(archive(point));
            if (!point.campaignId().equals(snapshot.campaignId())) throw new IOException("Restore-point identity mismatch.");
            checkIdentity();
            Map<String, String> baselines = new TreeMap<>();
            for (var entry : snapshot.files()) {
                baselines.put(entry.path(), fingerprint(resolve(entry.path())));
                if (session(entry.path())) {
                    String roster = rosterPath(entry.path());
                    baselines.put(roster, fingerprint(resolve(roster)));
                }
            }
            return new Plan(point, snapshot.files().stream().filter(entry -> !SessionRosterStore.isRosterFile(Path.of(entry.path()))).toList(), baselines);
        }
    }

    public String preview(Plan plan, String path, PrivateAccount account) throws IOException {
        synchronized (LOCK) {
            requirePlan(plan, path);
            try (ZipFile zip = new ZipFile(archive(plan.point()).toFile())) {
                var entry = entry(zip, path);
                if (entry.size() > TEXT_LIMIT) throw new IOException("This file is too large for a text preview.");
                Path temporary = Files.createTempFile(directory, ".preview-", ".tmp");
                try {
                    extract(zip, entry, temporary);
                    String raw = Files.readString(temporary, StandardCharsets.UTF_8);
                    if (session(path)) {
                        String current = currentText(path);
                        return sessionContent(raw, current, account).text();
                    }
                    return raw;
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }

    public String currentPreview(Plan plan, String path, PrivateAccount account) throws IOException {
        synchronized (LOCK) {
            requirePlan(plan, path);
            Path file = resolve(path);
            if (!Files.exists(file)) return "";
            if (Files.size(file) > TEXT_LIMIT) throw new IOException("This file is too large for a text preview.");
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            return session(path) ? sessionContent(raw, raw, account).text() : raw;
        }
    }

    /** Applies only selected files. Extra current files survive; an automatic pre-restore snapshot permits reversal. */
    public Point restore(Plan plan, Collection<String> selected, PrivateAccount account) throws IOException {
        synchronized (LOCK) {
            if (selected == null || selected.isEmpty()) throw new IllegalArgumentException("Select files to restore.");
            Point stored = find(plan.point().id());
            if (!stored.equals(plan.point())) throw new IOException("Restore point changed. Refresh the list.");
            Path staging = Files.createTempDirectory(directory, ".restore-");
            List<Change> changes = new ArrayList<>();
            Point safety = null;
            List<Change> applied = new ArrayList<>();
            try {
                Map<String, CampaignArchive.FileEntry> index = new HashMap<>();
                for (var entry : CampaignArchive.inspect(archive(stored)).files()) index.put(entry.path(), entry);
                try (ZipFile zip = new ZipFile(archive(stored).toFile())) {
                    Set<String> paths = new TreeSet<>(selected);
                    if (!Files.exists(campaign)) paths.add(CampaignMeta.FILE_NAME);
                    for (String path : paths) {
                        requirePlan(plan, path);
                        Path payload = staging.resolve("new-" + changes.size());
                        extract(zip, requiredEntry(index, path), payload);
                        if (path.equals(CampaignMeta.FILE_NAME)) {
                            String identity = JsonMappers.create().readTree(payload.toFile()).path("id").asText();
                            if (!identity.equals(stored.campaignId())) throw new IOException("Restore-point identity mismatch.");
                        }
                        if (session(path)) {
                            String current = currentText(path);
                            boolean privateSource;
                            try (var input = Files.newInputStream(payload)) {
                                privateSource = PrivateSessionFiles.isProtected(
                                    new String(input.readNBytes(PrivateSessionFiles.PREFIX.length() + 16), StandardCharsets.UTF_8));
                            }
                            if (privateSource || PrivateSessionFiles.isProtected(current)) {
                                if (Files.size(payload) > TEXT_LIMIT) throw new IOException("Encrypted session file is too large to restore.");
                                String raw = Files.readString(payload, StandardCharsets.UTF_8);
                                var content = sessionContent(raw, current, account);
                                if (!privateSource) content = new PrivateSessionFiles.Content(content.text(), snapshotRoster(zip, path, index));
                                String encrypted = PrivateSessionFiles.isProtected(current)
                                    ? PrivateSessionFiles.write(current, content, account) : PrivateSessionFiles.create(content, account);
                                Files.writeString(payload, encrypted, StandardCharsets.UTF_8);
                                changes.add(change(plan, rosterPath(path), null, staging, changes.size()));
                            } else {
                                String roster = rosterPath(path);
                                Path savedRoster = null;
                                if (zip.getEntry("campaign/" + roster) != null) {
                                    savedRoster = staging.resolve("new-roster-" + changes.size());
                                    extract(zip, requiredEntry(index, roster), savedRoster);
                                }
                                changes.add(change(plan, roster, savedRoster, staging, changes.size()));
                            }
                        }
                        changes.add(change(plan, path, payload, staging, changes.size()));
                    }
                }
                // Materialize the selected bytes before retention can expire the selected old point.
                if (Files.isDirectory(campaign)) safety = capture("Before restoring a restore point");
                else Files.createDirectories(campaign);
                for (Change change : changes) checkBaseline(change.path(), change.expected());
                for (Change change : changes) {
                    checkBaseline(change.path(), change.expected());
                    apply(resolve(change.path()), change.payload());
                    applied.add(change);
                }
                return safety;
            } catch (IOException | RuntimeException failure) {
                Collections.reverse(applied);
                for (Change change : applied) {
                    try {
                        String written = change.payload() == null ? MISSING : ManualFiles.hash(change.payload());
                        checkBaseline(change.path(), written);
                        apply(resolve(change.path()), change.original());
                    } catch (IOException | RuntimeException rollback) {
                        failure.addSuppressed(rollback);
                    }
                }
                throw new IOException("Restore failed. Previous files are preserved in the pre-restore point: "
                    + (safety == null ? "not created; no existing files were replaced" : safety.id()), failure);
            } finally {
                deleteOwnedDirectory(staging);
            }
        }
    }

    private Change change(Plan plan, String path, Path payload, Path staging, int index) throws IOException {
        String expected = plan.baselines().get(path);
        if (expected == null) throw new IOException("Refresh the restore preview before continuing.");
        checkBaseline(path, expected);
        Path original = null;
        if (!MISSING.equals(expected)) {
            original = staging.resolve("old-" + index);
            Files.copy(resolve(path), original);
            if (!ManualFiles.hash(original).equals(expected)) throw new IOException("Campaign changed during restore preparation.");
        }
        return new Change(path, payload, original, expected);
    }

    private PrivateSessionFiles.Content sessionContent(String raw, String current, PrivateAccount account) {
        if (PrivateSessionFiles.isProtected(current)) {
            var currentEnvelope = PrivateSessionFiles.verified(current, account);
            PrivateSessionFiles.read(current, account);
            if (PrivateSessionFiles.isProtected(raw)) {
                var old = PrivateSessionFiles.verified(raw, account);
                if (!old.header().fileId().equals(currentEnvelope.header().fileId())
                    || !old.header().ownerUid().equals(currentEnvelope.header().ownerUid())) {
                    throw new SecurityException("The private session-file identity changed. It cannot be restored over this file.");
                }
            }
        } else if (PrivateSessionFiles.isProtected(raw)) {
            PrivateSessionFiles.requireOwner(PrivateSessionFiles.verified(raw, account), account);
        }
        return PrivateSessionFiles.isProtected(raw) ? PrivateSessionFiles.read(raw, account)
            : new PrivateSessionFiles.Content(raw, List.of());
    }

    private List<TrackedCreature> snapshotRoster(ZipFile zip, String path, Map<String, CampaignArchive.FileEntry> index) throws IOException {
        String roster = rosterPath(path);
        if (zip.getEntry("campaign/" + roster) == null) return List.of();
        var entry = requiredEntry(index, roster);
        if (entry.size() > TEXT_LIMIT) throw new IOException("Encounter roster is too large to restore safely.");
        Path temporary = Files.createTempFile(directory, ".roster-", ".tmp");
        try {
            extract(zip, entry, temporary);
            return JsonMappers.create().readValue(temporary.toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<List<TrackedCreature>>() { });
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void requirePlan(Plan plan, String path) throws IOException {
        checkIdentity();
        if (!campaignId.equals(plan.point().campaignId())
            || plan.files().stream().noneMatch(entry -> entry.path().equals(path))) {
            throw new IOException("Select a file from this campaign's restore preview.");
        }
        checkBaseline(path, plan.baselines().get(path));
    }

    private CampaignArchive.FileEntry entry(ZipFile zip, String path) throws IOException {
        // inspect validates archive paths, collisions, sizes and the campaign identity entry.
        return CampaignArchive.inspect(Path.of(zip.getName())).files().stream().filter(e -> e.path().equals(path))
            .findFirst().orElseThrow(() -> new IOException("File is absent from the restore point."));
    }

    private static CampaignArchive.FileEntry requiredEntry(Map<String, CampaignArchive.FileEntry> index, String path) throws IOException {
        var entry = index.get(path);
        if (entry == null) throw new IOException("File is absent from the restore point.");
        return entry;
    }

    private static void extract(ZipFile zip, CampaignArchive.FileEntry entry, Path target) throws IOException {
        try (InputStream input = zip.getInputStream(zip.getEntry("campaign/" + entry.path()));
             OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            long size = 0;
            for (int count; (count = input.read(buffer)) != -1;) {
                size += count;
                if (size > entry.size()) throw new IOException("Restore-point file exceeds its declared size.");
                output.write(buffer, 0, count);
            }
            if (size != entry.size()) throw new IOException("Truncated restore-point file.");
        }
        if (!ManualFiles.hash(target).equals(entry.sha256())) throw new IOException("Restore-point checksum failed.");
    }

    private static void apply(Path target, Path payload) throws IOException {
        if (payload == null) {
            Files.deleteIfExists(target);
            return;
        }
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".almerion-restore-", ".tmp");
        try {
            Files.copy(payload, temp, StandardCopyOption.REPLACE_EXISTING);
            force(temp);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void checkBaseline(String path, String expected) throws IOException {
        if (expected == null || !expected.equals(fingerprint(resolve(path)))) {
            throw new IOException("Campaign files changed. Refresh the restore preview before continuing.");
        }
    }

    private String currentText(String path) throws IOException {
        Path current = resolve(path);
        if (!Files.exists(current)) return "";
        if (Files.size(current) > TEXT_LIMIT) throw new IOException("Session file is too large to restore safely.");
        return Files.readString(current, StandardCharsets.UTF_8);
    }

    private static String fingerprint(Path file) throws IOException {
        checkLinks(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return MISSING;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("A directory blocks a restored file.");
        return ManualFiles.hash(file);
    }

    private Point find(String id) throws IOException {
        pointPath(id);
        return list().stream().filter(p -> p.id().equals(id)).findFirst()
            .orElseThrow(() -> new IOException("Restore point is no longer available. Refresh the list."));
    }

    private Path pointPath(String id) {
        if (id == null || !id.matches("[0-9]{1,19}-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("Invalid restore-point identifier.");
        }
        Path path = directory.resolve(id + ".point");
        checkLinks(path);
        return path;
    }

    private Path archive(Point point) throws IOException {
        Path path = pointPath(point.id()).resolve("campaign.zip");
        checkLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Restore-point archive is missing.");
        return path;
    }

    private Path resolve(String path) throws IOException {
        checkLinks(campaign);
        return ManualFiles.resolve(campaign, path);
    }

    private void checkIdentity() throws IOException {
        if (Files.exists(campaign) && CampaignMeta.idOf(campaign).filter(campaignId::equals).isEmpty()) {
            throw new IOException("This folder now belongs to a different campaign.");
        }
    }

    private static boolean session(String path) {
        return path.startsWith("storyline/") && !Path.of(path).getFileName().toString().equals(StorylineService.ORDER_FILE)
            && !SessionRosterStore.isRosterFile(Path.of(path));
    }

    private static String rosterPath(String path) {
        return SessionRosterStore.rosterFile(Path.of(path)).toString().replace('\\', '/');
    }

    private static void checkLinks(Path path) {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new SecurityException("Restore-point links are not supported.");
        }
    }

    private static void force(Path path) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private void deleteOwnedDirectory(Path path) throws IOException {
        if (!path.getParent().equals(directory)) throw new IOException("Invalid restore-point cleanup path.");
        checkLinks(path);
        try (var files = Files.walk(path)) {
            for (Path child : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(child);
        }
    }
}
