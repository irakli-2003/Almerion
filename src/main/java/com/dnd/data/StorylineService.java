package com.dnd.data;

import com.dnd.model.session.TrackedCreature;
import com.dnd.security.PrivateAccount;
import com.dnd.security.PrivateSessionFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Filesystem-backed service for the Storyline feature: a per-campaign folder/file tree
 * (rooted at {@code <campaignRoot>/storyline/}) representing the campaign's story arc.
 * Folders group "sessions" (leaf text files); the {@link com.dnd.ui.scenes.StorylineScene}
 * renders this tree both as a plain file tree and as a nested-arc timeline.
 */
public class StorylineService {

    /**
     * Per-folder file recording the DM's manual ordering of that folder's children,
     * one name per line. Kept as a hidden sibling file so ordering survives restarts
     * without needing a database, and so the arc timeline and the file tree can share
     * one source of truth for sequence.
     */
    static final String ORDER_FILE = ".storyline-order";
    // Permission dialogs and separate editor instances must not race read-modify-write operations.
    private static final Object SESSION_WRITES = new Object();

    private final Path root;
    private final Supplier<PrivateAccount> accounts;
    private final Map<Path, String> readVersions = new java.util.concurrent.ConcurrentHashMap<>();

    public StorylineService(Path campaignRoot) {
        this(campaignRoot, null);
    }

    public StorylineService(Path campaignRoot, Supplier<PrivateAccount> accounts) {
        this.root = campaignRoot.resolve("storyline").toAbsolutePath().normalize();
        this.accounts = accounts;
    }

    /** Each editor holds its own version baseline, including permission changes made elsewhere. */
    public StorylineService forEditor() {
        return new StorylineService(root.getParent(), accounts);
    }

    /** Absolute path of the storyline root folder. */
    public Path getRoot() {
        return root;
    }

    /** Creates the storyline root folder if it does not already exist (self-healing for older campaigns). */
    public void ensureRoot() {
        checkPath(root);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Lists the direct children of {@code folder} in the DM's manual order (see
     * {@link #ORDER_FILE}). Children with no recorded position - newly created or
     * externally added ones - are appended after the ordered ones, folders first,
     * then files, both alphabetically.
     */
    public List<Path> listChildren(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) return List.of();
        checkPath(folder);
        try (Stream<Path> stream = Files.list(folder)) {
            List<Path> children = new ArrayList<>(stream
                .filter(p -> !p.getFileName().toString().equals(ORDER_FILE))
                .filter(p -> !SessionRosterStore.isRosterFile(p))
                .filter(p -> !p.getFileName().toString().startsWith(".almerion-write-"))
                .toList());
            List<String> order = readOrder(folder);
            children.sort(Comparator
                .comparingInt((Path p) -> {
                    int idx = order.indexOf(p.getFileName().toString());
                    return idx >= 0 ? idx : Integer.MAX_VALUE;
                })
                .thenComparing(p -> Files.isDirectory(p) ? 0 : 1)
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            return children;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads the recorded child ordering for {@code folder}; empty when none has been set. */
    public List<String> readOrder(Path folder) {
        checkPath(folder);
        Path orderFile = folder.resolve(ORDER_FILE);
        if (!Files.isRegularFile(orderFile)) return List.of();
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(orderFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) lines.add(trimmed);
            }
            return lines;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Records {@code names} as the manual ordering of {@code folder}'s children. */
    public void writeOrder(Path folder, List<String> names) {
        if (folder == null || !Files.isDirectory(folder)) return;
        checkPath(folder);
        try {
            Files.write(folder.resolve(ORDER_FILE), names, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Moves {@code child} to index {@code newIndex} among its siblings, persisting the
     * result so both the file tree and the arc timeline reflect the new sequence.
     */
    public void reorder(Path child, int newIndex) {
        if (child == null) return;
        checkPath(child);
        requireManage(child);
        Path parent = child.getParent();
        if (parent == null || !Files.isDirectory(parent)) return;
        List<String> names = new ArrayList<>();
        for (Path sibling : listChildren(parent)) {
            names.add(sibling.getFileName().toString());
        }
        String moving = child.getFileName().toString();
        int currentIndex = names.indexOf(moving);
        if (currentIndex < 0) return;
        names.remove(currentIndex);
        int clamped = Math.max(0, Math.min(newIndex, names.size()));
        names.add(clamped, moving);
        writeOrder(parent, names);
    }

    /** Shifts {@code child} one position earlier among its siblings. */
    public void moveUp(Path child) {
        int idx = indexOf(child);
        if (idx > 0) reorder(child, idx - 1);
    }

    /** Shifts {@code child} one position later among its siblings. */
    public void moveDown(Path child) {
        int idx = indexOf(child);
        if (idx >= 0) reorder(child, idx + 1);
    }

    /** @return {@code child}'s position among its siblings, or -1 if it has no parent folder. */
    public int indexOf(Path child) {
        if (child == null || child.getParent() == null) return -1;
        List<Path> siblings = listChildren(child.getParent());
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getFileName().equals(child.getFileName())) return i;
        }
        return -1;
    }

    /** Creates a new empty subfolder named {@code name} under {@code parent}. */
    public Path createFolder(Path parent, String name) {
        Path target = resolveNewChild(parent, name);
        try {
            Files.createDirectory(target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Creates a new empty text file named {@code name} under {@code parent}. */
    public Path createFile(Path parent, String name) {
        Path target = resolveNewChild(parent, name);
        try {
            if (accounts == null) {
                Files.createFile(target);
            } else {
                String encrypted = PrivateSessionFiles.create(new PrivateSessionFiles.Content("", List.of()), account());
                Files.writeString(target, encrypted, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolveNewChild(Path parent, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required.");
        }
        String safe = name.trim();
        if (safe.equals(".") || safe.equals("..") || safe.startsWith(".almerion-write-")
            || SessionRosterStore.isRosterFile(Path.of(safe))) {
            throw new IllegalArgumentException("That name is reserved.");
        }
        if (safe.indexOf('/') >= 0 || safe.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Name cannot contain path separators.");
        }
        if (safe.equals(ORDER_FILE)) {
            throw new IllegalArgumentException("That name is reserved.");
        }
        Path effectiveParent = (parent == null) ? root : parent;
        checkPath(effectiveParent);
        if (!Files.isDirectory(effectiveParent)) {
            throw new IllegalArgumentException("Selected item is not a folder.");
        }
        Path target = effectiveParent.resolve(safe);
        if (Files.exists(target)) {
            throw new IllegalArgumentException("An item named '" + safe + "' already exists here.");
        }
        return target;
    }

    /** Recursively deletes {@code target} (file or folder). */
    public void delete(Path target) {
        if (target == null || !Files.exists(target)) return;
        checkPath(target);
        if (target.equals(root)) throw new IllegalArgumentException("Cannot delete the storyline root.");
        requireManage(target);
        CampaignRestorePoints.beforeIdentifiedCampaign(root.getParent(), "Before deleting storyline files");
        checkPath(target);
        requireManage(target);
        try {
            if (Files.isDirectory(target)) {
                try (Stream<Path> walk = Files.walk(target)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
            } else {
                Files.delete(target);
                Files.deleteIfExists(SessionRosterStore.rosterFile(target));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Moves {@code source} (file or folder, with all descendants if a folder) under
     * {@code destinationFolder}. Rejects moving a folder into itself or one of its own
     * descendants, and rejects moving an item into its current parent (no-op) folder.
     */
    public Path move(Path source, Path destinationFolder) {
        if (source == null || destinationFolder == null) {
            throw new IllegalArgumentException("Source and destination are required.");
        }
        if (!Files.isDirectory(destinationFolder)) {
            throw new IllegalArgumentException("Destination must be a folder.");
        }
        checkPath(source);
        checkPath(destinationFolder);
        if (source.equals(root)) throw new IllegalArgumentException("Cannot move the storyline root.");
        requireManage(source);
        Path normalizedSource = source.normalize();
        Path normalizedDest = destinationFolder.normalize();
        if (normalizedDest.equals(normalizedSource) || normalizedDest.startsWith(normalizedSource)) {
            throw new IllegalArgumentException("Cannot move a folder into itself or one of its own subfolders.");
        }
        Path target = destinationFolder.resolve(source.getFileName());
        if (Files.exists(target)) {
            throw new IllegalArgumentException("An item named '" + source.getFileName() + "' already exists in the destination.");
        }
        try {
            Files.move(source, target);
            Path roster = SessionRosterStore.rosterFile(source);
            if (Files.isRegularFile(roster)) {
                Files.move(roster, SessionRosterStore.rosterFile(target));
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads the full text content of a session file. */
    public String readText(Path file) {
        String raw = raw(file);
        if (!PrivateSessionFiles.isProtected(raw)) {
            readVersions.putIfAbsent(file.toAbsolutePath().normalize(), raw);
            return raw;
        }
        PrivateSessionFiles.Content content = PrivateSessionFiles.read(raw, account());
        readVersions.putIfAbsent(file.toAbsolutePath().normalize(), raw);
        return content.text();
    }

    /** Fingerprint of the version this editor actually loaded, not a later filesystem read. */
    public String editorRevision(Path file) {
        checkPath(file);
        String version = readVersions.get(file.toAbsolutePath().normalize());
        if (version == null) throw new IllegalStateException("Read the session file before creating a recovery draft.");
        return StorylineDrafts.hash(version);
    }

    String editorPrivateFileId(Path file) {
        editorRevision(file);
        String version = readVersions.get(file.toAbsolutePath().normalize());
        return PrivateSessionFiles.isProtected(version) ? PrivateSessionFiles.parse(version).header().fileId() : null;
    }

    /** Overwrites the full text content of a session file. */
    public void writeText(Path file, String content) {
        synchronized (SESSION_WRITES) {
            String raw = raw(file);
            checkVersion(file, raw);
            if (PrivateSessionFiles.isProtected(raw)) {
                PrivateSessionFiles.Content previous = PrivateSessionFiles.read(raw, account());
                String encrypted = PrivateSessionFiles.write(raw, new PrivateSessionFiles.Content(content, previous.roster()), account());
                atomicWrite(file, encrypted);
                readVersions.put(file.toAbsolutePath().normalize(), encrypted);
            } else {
                String text = content == null ? "" : content;
                atomicWrite(file, text);
                readVersions.put(file.toAbsolutePath().normalize(), text);
            }
        }
    }

    public List<TrackedCreature> readRoster(Path file) {
        String raw = raw(file);
        if (!PrivateSessionFiles.isProtected(raw)) return SessionRosterStore.load(file);
        List<TrackedCreature> roster = PrivateSessionFiles.read(raw, account()).roster();
        readVersions.putIfAbsent(file.toAbsolutePath().normalize(), raw);
        return new ArrayList<>(roster);
    }

    public void writeRoster(Path file, List<TrackedCreature> roster) {
        synchronized (SESSION_WRITES) {
            String raw = raw(file);
            checkVersion(file, raw);
            if (!PrivateSessionFiles.isProtected(raw)) {
                SessionRosterStore.save(file, roster);
                return;
            }
            try {
                OperationHistory.writeRoster(file, roster, account());
                readVersions.put(file.toAbsolutePath().normalize(), raw(file));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not save encounter action history.", e);
            }
        }
    }

    public boolean isPrivate(Path file) {
        if (!isSessionFile(file)) return false;
        checkPath(file);
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return PrivateSessionFiles.isProtected(reader.readLine());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public PrivateSessionFiles.Header permissions(Path file) {
        return PrivateSessionFiles.parse(raw(file)).header();
    }

    /** Protecting a legacy shared file is an explicit ownership decision, never automatic migration. */
    public void protect(Path file) {
        synchronized (SESSION_WRITES) {
            String raw = raw(file);
            if (PrivateSessionFiles.isProtected(raw)) throw new IllegalArgumentException("This session file is already private.");
            List<TrackedCreature> roster = SessionRosterStore.load(file);
            String encrypted = PrivateSessionFiles.create(new PrivateSessionFiles.Content(raw, roster), account());
            atomicWrite(file, encrypted);
            try {
                Files.deleteIfExists(SessionRosterStore.rosterFile(file));
            } catch (IOException e) {
                throw new UncheckedIOException("File encrypted, but its old plaintext roster could not be removed. Remove it before syncing.", e);
            }
        }
    }

    public void setAccess(Path file, List<PrivateAccount.Identity> recipients) {
        setAccess(file, recipients, permissions(file));
    }

    public void setAccess(Path file, List<PrivateAccount.Identity> recipients, PrivateSessionFiles.Header expected) {
        synchronized (SESSION_WRITES) {
            String current = raw(file);
            if (!PrivateSessionFiles.parse(current).header().equals(expected)) {
                throw new IllegalStateException("Session-file permissions changed elsewhere. Review access again.");
            }
            String encrypted = PrivateSessionFiles.grant(current, account(), recipients);
            atomicWrite(file, encrypted);
        }
    }

    public Path findPrivateFile(String fileId) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(this::isSessionFile).filter(this::isPrivate)
                .filter(path -> permissions(path).fileId().equals(fileId)).findFirst().orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private PrivateAccount account() {
        PrivateAccount account = accounts == null ? null : accounts.get();
        if (account == null || !account.isUnlocked()) {
            throw new SecurityException("Unlock your private-file account first.");
        }
        return account;
    }

    private void checkVersion(Path file, String current) {
        String previous = readVersions.get(file.toAbsolutePath().normalize());
        if (previous != null && !previous.equals(current)) {
            throw new IllegalStateException("The session file or its permissions changed elsewhere. Reopen it before saving.");
        }
    }

    private String raw(Path file) {
        checkPath(file);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void atomicWrite(Path file, String content) {
        checkPath(file);
        Path temp = null;
        try {
            temp = Files.createTempFile(file.getParent(), ".almerion-write-", ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not atomically save the session file.", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    System.err.println("Could not remove temporary session write: " + temp);
                }
            }
        }
    }

    private void requireManage(Path target) {
        if (Files.isDirectory(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                walk.filter(this::isSessionFile).filter(this::isPrivate).forEach(file ->
                    PrivateSessionFiles.requireOwner(PrivateSessionFiles.verified(raw(file), account()), account()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else if (isPrivate(target)) {
            PrivateSessionFiles.requireOwner(PrivateSessionFiles.verified(raw(target), account()), account());
        }
    }

    private void checkPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IllegalArgumentException("Path is outside this campaign's storyline.");
        Path component = root;
        if (Files.isSymbolicLink(component)) throw new IllegalArgumentException("Storyline links are not supported.");
        for (Path part : root.relativize(normalized)) {
            component = component.resolve(part);
            if (Files.isSymbolicLink(component)) throw new IllegalArgumentException("Storyline links are not supported.");
        }
    }

    /** True if {@code path} is a leaf "session" file (not the storyline root, not a folder). */
    public boolean isSessionFile(Path path) {
        return path != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && !SessionRosterStore.isRosterFile(path) && !path.getFileName().toString().equals(ORDER_FILE)
            && !path.getFileName().toString().startsWith(".almerion-write-");
    }

    /** True if {@code path} is a folder under (or equal to) the storyline root. */
    public boolean isFolder(Path path) {
        return path != null && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }
}
