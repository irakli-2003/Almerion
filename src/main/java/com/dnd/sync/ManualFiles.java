package com.dnd.sync;

import com.dnd.data.JsonMappers;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Large campaign files stay on disk; only their verified transfer manifest travels through Git. */
public final class ManualFiles {
    public static final long MAX_GIT_BYTES = 100L * 1024 * 1024;
    public static final String MANIFEST = ".almerion-manual-files.json";
    private static final String BEGIN = "# Almerion manual-transfer files (managed)\n";
    private static final String END = "# End Almerion manual-transfer files\n";
    private static final Map<Path, CachedHash> HASHES = new java.util.concurrent.ConcurrentHashMap<>();

    public record Entry(String path, long size, String sha256) { }
    public record Manifest(int version, List<Entry> files) { }
    public record Report(List<Entry> available, List<Entry> missing) {
        public boolean isEmpty() { return available.isEmpty() && missing.isEmpty(); }
    }
    private record CachedHash(long size, java.nio.file.attribute.FileTime modified, Object key, String hash) { }

    private ManualFiles() { }

    public static List<Entry> read(Path root) throws IOException {
        Path file = resolve(root, MANIFEST);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4 * 1024 * 1024) {
            throw new IOException("Invalid manual-transfer manifest.");
        }
        return parse(root, Files.readAllBytes(file));
    }

    private static List<Entry> parse(Path root, byte[] bytes) throws IOException {
        Manifest manifest = JsonMappers.create().readValue(bytes, Manifest.class);
        if (manifest == null || manifest.version() != 1 || manifest.files() == null
                || manifest.files().size() > 10000) throw new IOException("Invalid manual-transfer manifest.");
        Set<String> paths = new HashSet<>();
        for (Entry entry : manifest.files()) {
            if (entry == null || entry.size() <= MAX_GIT_BYTES || entry.sha256() == null
                    || !entry.sha256().matches("[a-f0-9]{64}")) throw new IOException("Invalid manual-transfer file entry.");
            resolve(root, entry.path());
            if (MANIFEST.equalsIgnoreCase(entry.path()) || !paths.add(entry.path().toLowerCase(Locale.ROOT))) {
                throw new IOException("Duplicate or reserved manual-transfer path.");
            }
        }
        return List.copyOf(manifest.files());
    }

    /** Three-way merge by path, not JSON lines. A GM's choice applies only to actual per-path conflicts. */
    static byte[] merge(Path root, byte[] base, byte[] mine, byte[] remote, CampaignRepo.Choice choice) throws IOException {
        Map<String, Entry> before = byPath(root, base), ours = byPath(root, mine), theirs = byPath(root, remote);
        Set<String> paths = new TreeSet<>(ours.keySet());
        paths.addAll(theirs.keySet());
        List<Entry> merged = new ArrayList<>();
        for (String path : paths) {
            Entry a = before.get(path), b = ours.get(path), c = theirs.get(path), next;
            if (Objects.equals(b, c)) next = b;
            else if (Objects.equals(a, b)) next = c;
            else if (Objects.equals(a, c)) next = b;
            else if (choice == null) return null;
            else next = choice == CampaignRepo.Choice.GITHUB ? c : b;
            if (next != null) merged.add(next);
        }
        return JsonMappers.create().writeValueAsBytes(new Manifest(1, merged));
    }

    private static Map<String, Entry> byPath(Path root, byte[] bytes) throws IOException {
        Map<String, Entry> entries = new TreeMap<>();
        if (bytes != null) for (Entry entry : parse(root, bytes)) entries.put(entry.path(), entry);
        return entries;
    }

    /** Must run before staging; never put the large file contents into the index. */
    static void prepare(Path root, Git git) throws IOException, GitAPIException {
        Map<String, Entry> entries = new TreeMap<>();
        for (Entry entry : read(root)) entries.put(entry.path(), entry);
        Path localState = git.getRepository().getDirectory().toPath().resolve("almerion-manual-local.json");
        Map<String, String> previous = Files.exists(localState)
            ? JsonMappers.create().readValue(localState.toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { })
            : Map.of();
        if (previous == null) throw new IOException("Invalid local manual-transfer state.");
        Map<String, String> seen = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && dir.getFileName().toString().equals(".git")
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                String path = root.relativize(file).toString().replace('\\', '/');
                if (path.endsWith(".tmp") || path.endsWith(".part")) return FileVisitResult.CONTINUE;
                if (attrs.size() > MAX_GIT_BYTES) {
                    if (path.equals(MANIFEST)) throw new IOException("Manual-transfer manifest is too large.");
                    String hash = hash(file);
                    seen.put(path, hash);
                    Entry expected = entries.get(path);
                    if (expected == null || expected.sha256().equals(hash)
                            || (previous.containsKey(path) && !hash.equals(previous.get(path)))) {
                        entries.put(path, new Entry(path, attrs.size(), hash));
                    }
                } else if (entries.containsKey(path)) {
                    String hash = hash(file);
                    seen.put(path, hash);
                    if (previous.containsKey(path) && !hash.equals(previous.get(path))) entries.remove(path);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        List<Entry> files = List.copyOf(entries.values());
        if (!files.isEmpty() || Files.exists(root.resolve(MANIFEST))) write(root, files);
        exclude(root, files);
        var index = git.getRepository().readDirCache();
        for (Entry entry : files) {
            if (index.findEntry(entry.path()) >= 0) git.rm().setCached(true).addFilepattern(entry.path()).call();
        }
        Path stateTemp = Files.createTempFile(localState.getParent(), "almerion-manual-", ".tmp");
        try {
            JsonMappers.create().writeValue(stateTemp.toFile(), seen);
            replace(stateTemp, localState);
        } finally {
            Files.deleteIfExists(stateTemp);
        }
    }

    private static void write(Path root, List<Entry> entries) throws IOException {
        Path target = resolve(root, MANIFEST);
        byte[] content = JsonMappers.create().writeValueAsBytes(new Manifest(1, entries));
        if (Files.exists(target) && Arrays.equals(Files.readAllBytes(target), content)) return;
        Path temp = Files.createTempFile(root, ".almerion-transfer-", ".tmp");
        try {
            Files.write(temp, content);
            replace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void exclude(Path root, List<Entry> entries) throws IOException {
        Path file = root.resolve(".git").resolve("info").resolve("exclude");
        Files.createDirectories(file.getParent());
        String old = Files.exists(file) ? Files.readString(file) : "";
        int start = old.indexOf(BEGIN);
        if (start >= 0) {
            int end = old.indexOf(END, start);
            if (end < 0) throw new IOException("Incomplete Almerion block in Git excludes; no exclusions were changed.");
            old = old.substring(0, start) + old.substring(end + END.length());
        }
        StringBuilder next = new StringBuilder(old);
        if (!old.isEmpty() && !old.endsWith("\n")) next.append('\n');
        next.append(BEGIN);
        next.append("*.tmp\n*.part\n");
        for (Entry entry : entries) {
            next.append('/');
            for (char c : entry.path().toCharArray()) {
                if ("\\!#*?[] ".indexOf(c) >= 0) next.append('\\');
                next.append(c);
            }
            next.append('\n');
        }
        next.append(END);
        if (!next.toString().equals(Files.exists(file) ? Files.readString(file) : "")) {
            Files.writeString(file, next);
        }
    }

    public static Report inspect(Path root) throws IOException {
        List<Entry> available = new ArrayList<>(), missing = new ArrayList<>();
        for (Entry entry : read(root)) {
            (matches(resolve(root, entry.path()), entry) ? available : missing).add(entry);
        }
        return new Report(List.copyOf(available), List.copyOf(missing));
    }

    /** Creates one unique folder, keeping campaign-relative paths and a portable manifest. */
    public static Path exportFiles(Path root, Path destination) throws IOException {
        Files.createDirectories(destination);
        Path base = destination.toRealPath();
        Path campaign = root.toRealPath();
        if (base.startsWith(campaign)) throw new IOException("Choose a destination outside the campaign folder.");
        Files.createDirectories(base);
        List<Entry> entries = inspect(root).available();
        if (entries.isEmpty()) throw new IOException("No verified manual-transfer files are available on this device.");
        Path bundle = Files.createTempDirectory(base, "Almerion-files-");
        write(bundle, entries);
        for (Entry entry : entries) {
            Path source = resolve(root, entry.path()), target = resolve(bundle, entry.path());
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            if (!matches(target, entry)) throw new IOException("File changed during export: " + entry.path()
                + ". The incomplete transfer folder is " + bundle);
        }
        return bundle;
    }

    /** Verifies the whole selected bundle before importing. Existing different files are never overwritten. */
    public static int importFiles(Path root, Path bundle) throws IOException {
        Map<String, Entry> expected = new HashMap<>();
        for (Entry entry : read(root)) expected.put(entry.path(), entry);
        List<Entry> selected = new ArrayList<>();
        for (Entry entry : read(bundle)) {
            if (!entry.equals(expected.get(entry.path()))) continue;
            Path source = resolve(bundle, entry.path()), target = resolve(root, entry.path());
            if (!matches(source, entry)) throw new IOException("Transfer file is missing or damaged: " + entry.path());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!matches(target, entry)) throw new IOException("A different local file already exists: " + entry.path()
                    + ". Move it to a safe location before importing.");
            } else selected.add(entry);
        }
        if (selected.isEmpty()) throw new IOException("This folder contains no missing files matching this campaign.");
        for (Entry entry : selected) {
            Path target = resolve(root, entry.path());
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".almerion-import-", ".tmp");
            try {
                Files.copy(resolve(bundle, entry.path()), temp, StandardCopyOption.REPLACE_EXISTING);
                if (!matches(temp, entry)) throw new IOException("File changed during import: " + entry.path());
                // Do not replace a file another operation created while the copy was in progress.
                Files.move(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        return selected.size();
    }

    public static Path resolve(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.contains("\\") || relative.contains(":")
                || relative.startsWith("/") || Arrays.stream(relative.split("/", -1))
                    .anyMatch(p -> p.isEmpty() || p.endsWith(".") || p.endsWith(" ") || p.equalsIgnoreCase(".git")
                        || p.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))
                || relative.matches(".*[<>\"|?*].*")
                || relative.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IOException("Unsafe manual-transfer path: " + relative);
        }
        Path base = root.toAbsolutePath().normalize(), file = base.resolve(relative).normalize();
        if (!file.startsWith(base)) throw new IOException("Manual-transfer path leaves the campaign.");
        Path realBase = Files.exists(base) ? base.toRealPath() : base;
        for (Path part = file; part != null && part.startsWith(base); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Manual transfer cannot follow symbolic links: " + relative);
            if (Files.exists(part) && !part.toRealPath().startsWith(realBase)) {
                throw new IOException("Manual-transfer path leaves the campaign: " + relative);
            }
        }
        return file;
    }

    private static boolean matches(Path file, Entry entry) throws IOException {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == entry.size()
            && hash(file).equals(entry.sha256());
    }

    public static String hash(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Path key = file.toAbsolutePath().normalize();
        CachedHash cached = HASHES.get(key);
        if (cached != null && cached.size() == attrs.size() && cached.modified().equals(attrs.lastModifiedTime())
                && Objects.equals(cached.key(), attrs.fileKey())) return cached.hash();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[128 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (after.size() != attrs.size() || !after.lastModifiedTime().equals(attrs.lastModifiedTime())
                    || !Objects.equals(after.fileKey(), attrs.fileKey())) throw new IOException("File changed while reading: " + file);
            String hash = HexFormat.of().formatHex(digest.digest());
            if (HASHES.size() > 10000) HASHES.clear();
            HASHES.put(key, new CachedHash(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey(), hash));
            return hash;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void replace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
