package com.dnd.data;

import com.dnd.cli.storage.CampaignStorage;
import com.dnd.cloud.CampaignMeta;
import com.dnd.sync.ManualFiles;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Portable, checksummed campaign snapshots. Git internals and machine credentials never travel. */
public final class CampaignArchive {
    static final String METADATA = "almerion-export.json";
    public record FileEntry(String path, long size, long modified, String sha256) { }
    public record Snapshot(int format, String campaignId, String name, List<FileEntry> files) { }
    public record Imported(Path campaign, int added, int updated, int kept, Path backup) { }

    private CampaignArchive() { }

    public static void exportCampaign(Path campaign, Path archive) throws IOException {
        exportCampaign(campaign, archive, true);
    }

    static void captureAvailableFiles(Path campaign, Path archive) throws IOException {
        exportCampaign(campaign, archive, false);
    }

    private static void exportCampaign(Path campaign, Path archive, boolean requireComplete) throws IOException {
        Path source = campaign.toRealPath();
        Files.createDirectories(archive.toAbsolutePath().getParent());
        Path destination = archive.toAbsolutePath().getParent().toRealPath().resolve(archive.getFileName());
        if (destination.startsWith(source)) throw new IOException("Save the export outside the campaign folder.");
        var missing = requireComplete ? ManualFiles.inspect(source).missing() : List.<ManualFiles.Entry>of();
        if (requireComplete && !missing.isEmpty()) {
            throw new IOException("Import missing or different manual-transfer files before exporting a complete campaign:\n"
                + String.join("\n", missing.stream().map(ManualFiles.Entry::path).toList()));
        }
        String id = CampaignMeta.ensureId(source);
        List<FileEntry> files = new ArrayList<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(source) && dir.getFileName().toString().equalsIgnoreCase(".git")
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) throw new IOException("Cannot export a symbolic link or special file: " + file);
                String path = source.relativize(file).toString().replace('\\', '/');
                ManualFiles.resolve(source, path);
                files.add(new FileEntry(path, attrs.size(), attrs.lastModifiedTime().toMillis(), ManualFiles.hash(file)));
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(FileEntry::path));
        Path temp = Files.createTempFile(destination.getParent(), ".almerion-export-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                zip.putNextEntry(new ZipEntry(METADATA));
                zip.write(JsonMappers.create().writeValueAsBytes(new Snapshot(1, id, source.getFileName().toString(), files)));
                zip.closeEntry();
                for (FileEntry entry : files) {
                    Path file = ManualFiles.resolve(source, entry.path());
                    zip.putNextEntry(new ZipEntry("campaign/" + entry.path()));
                    Files.copy(file, zip);
                    zip.closeEntry();
                    if (Files.size(file) != entry.size() || !ManualFiles.hash(file).equals(entry.sha256())) {
                        throw new IOException("Campaign changed during export. Try again after saving: " + entry.path());
                    }
                }
            }
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static Snapshot inspect(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) { return inspect(zip); }
    }

    private static Snapshot inspect(ZipFile zip) throws IOException {
        ZipEntry metadata = zip.getEntry(METADATA);
        if (metadata == null || metadata.getSize() < 0 || metadata.getSize() > 16 * 1024 * 1024) {
            throw new IOException("Not an Almerion campaign export.");
        }
        Snapshot snapshot;
        try (InputStream input = zip.getInputStream(metadata)) {
            byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) throw new IOException("Campaign export metadata is too large.");
            snapshot = JsonMappers.create().readValue(bytes, Snapshot.class);
        }
        if (snapshot == null || snapshot.format() != 1 || snapshot.campaignId() == null
                || snapshot.campaignId().isBlank() || snapshot.name() == null || snapshot.files() == null
                || snapshot.files().size() > 100000) throw new IOException("Invalid campaign export metadata.");
        Set<String> names = new HashSet<>();
        for (FileEntry file : snapshot.files()) {
            if (file == null || file.size() < 0 || file.modified() < 0 || file.sha256() == null
                    || !file.sha256().matches("[a-f0-9]{64}")) throw new IOException("Invalid campaign export file entry.");
            // Validate independently of extraction and reject Windows case-collisions on every platform.
            ManualFiles.resolve(Path.of("."), file.path());
            if (!names.add(("campaign/" + file.path()).toLowerCase(Locale.ROOT))) {
                throw new IOException("Duplicate path in campaign export: " + file.path());
            }
            ZipEntry entry = zip.getEntry("campaign/" + file.path());
            if (entry == null || entry.isDirectory() || entry.getSize() != file.size()) {
                throw new IOException("Missing or damaged campaign export file: " + file.path());
            }
        }
        Set<String> actual = new HashSet<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!actual.add(name.toLowerCase(Locale.ROOT))
                    || (!name.equals(METADATA) && !names.contains(name.toLowerCase(Locale.ROOT)))) {
                throw new IOException("Unexpected or duplicate entry in campaign export: " + name);
            }
        }
        if (!names.contains(("campaign/" + CampaignMeta.FILE_NAME).toLowerCase(Locale.ROOT))) {
            throw new IOException("Campaign export has no campaign identity.");
        }
        return snapshot;
    }

    public static Optional<Path> existingCampaign(CampaignStorage storage, Snapshot snapshot) throws IOException {
        Path found = null;
        for (String name : storage.listCustomCampaigns()) {
            Path folder = storage.resolveCustomCampaignPath(name);
            if (CampaignMeta.idOf(folder).filter(snapshot.campaignId()::equals).isPresent()) {
                if (found != null) throw new IOException("More than one local folder has this campaign ID. Resolve the duplicate before importing.");
                found = folder;
            }
        }
        return Optional.ofNullable(found);
    }

    /** Newer imported files replace older local files; absent local-only files and newer local edits survive. */
    public static Imported importCampaign(CampaignStorage storage, Path archive) throws IOException {
        storage.ensureInitialized();
        Path staging = Files.createTempDirectory(storage.getCustomCampaignRoot().getParent(), ".almerion-import-");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Snapshot snapshot = inspect(zip);
            long total = 0;
            for (FileEntry entry : snapshot.files()) {
                try { total = Math.addExact(total, entry.size()); }
                catch (ArithmeticException tooLarge) { throw new IOException("Campaign export is too large.", tooLarge); }
            }
            if (total > Files.getFileStore(staging).getUsableSpace()) throw new IOException("Not enough disk space to import this campaign.");
            // Verify every byte before changing any existing campaign.
            for (FileEntry entry : snapshot.files()) {
                Path target = ManualFiles.resolve(staging, entry.path());
                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(zip.getEntry("campaign/" + entry.path()));
                     OutputStream output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[128 * 1024];
                    long written = 0;
                    for (int count; (count = input.read(buffer)) != -1;) {
                        written += count;
                        if (written > entry.size()) throw new IOException("Expanded file exceeds declared size: " + entry.path());
                        output.write(buffer, 0, count);
                    }
                    if (written != entry.size()) throw new IOException("Truncated campaign export: " + entry.path());
                }
                if (!ManualFiles.hash(target).equals(entry.sha256())) throw new IOException("Campaign export checksum failed: " + entry.path());
                Files.setLastModifiedTime(target, FileTime.fromMillis(entry.modified()));
            }
            if (!CampaignMeta.idOf(staging).filter(snapshot.campaignId()::equals).isPresent()) {
                throw new IOException("Campaign identity does not match the export.");
            }
            Optional<Path> existing = existingCampaign(storage, snapshot);
            if (existing.isEmpty()) {
                String base = storage.normalizeCampaignName(snapshot.name());
                if (base.isBlank()) base = "campaign";
                Path target = storage.resolveCustomCampaignPath(base);
                for (int i = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS); i++) {
                    target = storage.resolveCustomCampaignPath(base + "-" + i);
                }
                Files.move(staging, target);
                return new Imported(target, snapshot.files().size(), 0, 0, null);
            }
            Path target = existing.get();
            List<FileEntry> add = new ArrayList<>(), replace = new ArrayList<>();
            int kept = 0;
            for (FileEntry entry : snapshot.files()) {
                Path file = ManualFiles.resolve(target, entry.path());
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) add.add(entry);
                else {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("A local directory blocks an imported file: " + entry.path());
                    }
                    if (Files.size(file) == entry.size() && ManualFiles.hash(file).equals(entry.sha256())) continue;
                    if (entry.modified() > Files.getLastModifiedTime(file).toMillis()) replace.add(entry);
                    else kept++;
                }
            }
            Path backup = null;
            if (!add.isEmpty() || !replace.isEmpty()) {
                CampaignRestorePoints.forCampaign(target).capture("Before campaign import");
            }
            if (!replace.isEmpty()) {
                Path backups = storage.getCustomCampaignRoot().getParent().resolve("campaign-import-backups");
                Files.createDirectories(backups);
                backup = Files.createTempDirectory(backups, target.getFileName() + "-");
                for (FileEntry entry : replace) {
                    Path saved = ManualFiles.resolve(backup, entry.path());
                    Files.createDirectories(saved.getParent());
                    Files.copy(ManualFiles.resolve(target, entry.path()), saved, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
            List<FileEntry> changed = new ArrayList<>();
            try {
                for (FileEntry entry : concat(add, replace)) {
                    Path file = ManualFiles.resolve(target, entry.path());
                    Files.createDirectories(file.getParent());
                    Files.move(ManualFiles.resolve(staging, entry.path()), file, StandardCopyOption.REPLACE_EXISTING);
                    changed.add(entry);
                }
            } catch (IOException failure) {
                for (FileEntry entry : changed) {
                    try {
                        Path file = ManualFiles.resolve(target, entry.path());
                        if (replace.contains(entry)) Files.copy(ManualFiles.resolve(backup, entry.path()), file,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        else Files.delete(file);
                    } catch (IOException rollback) { failure.addSuppressed(rollback); }
                }
                throw new IOException("Campaign import could not finish; original replaced files are backed up at " + backup, failure);
            }
            return new Imported(target, add.size(), replace.size(), kept, backup);
        } finally {
            if (Files.exists(staging)) {
                try (var files = Files.walk(staging)) {
                    for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private static List<FileEntry> concat(List<FileEntry> first, List<FileEntry> second) {
        List<FileEntry> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }
}
