package com.dnd.sync;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps one campaign folder in step with its GitHub repository: commit local changes, fetch,
 * merge, push.
 *
 * <p>This follows Git: when only GitHub changed, its version is taken (fast-forward); changes
 * to different files are merged automatically. Supported catalogues merge by entity ID and
 * object field, retaining independent edits. Competing values are never decided for the GM:
 * the merge is undone, nothing is pushed, and {@link Outcome#CONFLICT} describes the fields
 * or unsupported whole files needing review. Field choices are bound to the reviewed revisions.
 * Whole-file choices can also keep both versions, saving GitHub's next to the original as
 * {@code <file>.remote-<yyyyMMdd-HHmmss>}.</p>
 *
 * <p>Sync settings live in the campaign's own {@code .git/config} (never committed): the
 * {@code origin} remote URL, and {@code almerion.sync} to switch syncing off. The token is
 * never written there; it's handed to each fetch/push through a {@link CredentialsProvider}.</p>
 */
public final class CampaignRepo {
    public static final String REMOTE = "origin";
    public static final String DEFAULT_BRANCH = "main";
    public static final String GITIGNORE = "# Written by Almerion: operating-system clutter that shouldn't be synced\n"
        + "Thumbs.db\ndesktop.ini\n.DS_Store\n*.tmp\n";
    private static final int TIMEOUT_SECONDS = 30;
    private static final String COMMIT_PREFIX = "Campaign changes from ";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern GITHUB_URL = Pattern.compile("github\\.com[:/]([^/]+)/([^/]+?)(\\.git)?/?$");

    public record Identity(String name, String email) {
    }

    /**
     * A version of {@code original} that a sync put aside as {@code copy}
     * ({@code <file>.remote-<yyyyMMdd-HHmmss>}): GitHub's version of a file both computers had
     * changed. Up to version 1.2.1 this happened automatically on every such conflict.
     */
    public record SetAside(String original, String copy, java.time.LocalDateTime putAside) {
    }

    private static final Pattern SET_ASIDE = Pattern.compile("^(.+)\\.remote-(\\d{8}-\\d{6})$");

    /** Every put-aside copy in the campaign folder, oldest first (paths use '/'). */
    public static List<SetAside> setAsideCopies(Path folder) throws IOException {
        List<SetAside> found = new ArrayList<>();
        if (!Files.isDirectory(folder)) return found;
        try (java.util.stream.Stream<Path> files = Files.walk(folder)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String relative = folder.relativize(file).toString().replace('\\', '/');
                if (relative.equals(".git") || relative.startsWith(".git/") || !Files.isRegularFile(file)) continue;
                java.util.regex.Matcher m = SET_ASIDE.matcher(relative);
                if (!m.matches()) continue;
                java.time.LocalDateTime when;
                try {
                    when = java.time.LocalDateTime.parse(m.group(2), STAMP);
                } catch (java.time.format.DateTimeParseException e) {
                    when = null;
                }
                found.add(new SetAside(m.group(1), relative, when));
            }
        }
        found.sort(java.util.Comparator.comparing(SetAside::putAside,
            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())).thenComparing(SetAside::copy));
        return found;
    }

    /** Puts the set-aside version back in place of the current file (the copy is removed). */
    public static void restoreSetAside(Path folder, SetAside copy) throws IOException {
        Path source = folder.resolve(copy.copy()).normalize();
        Path target = folder.resolve(copy.original()).normalize();
        if (!source.startsWith(folder.normalize()) || !target.startsWith(folder.normalize())) {
            throw new IOException("Not a file of this campaign: " + copy.copy());
        }
        Files.createDirectories(target.getParent());
        Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Keeps the current file and deletes the set-aside copy. */
    public static void discardSetAside(Path folder, SetAside copy) throws IOException {
        Path source = folder.resolve(copy.copy()).normalize();
        if (!source.startsWith(folder.normalize())) throw new IOException("Not a file of this campaign: " + copy.copy());
        Files.deleteIfExists(source);
    }

    public enum Outcome { SYNCED, CONFLICT, REMOTE_AHEAD, OFFLINE, AUTH_FAILED, HISTORY_REPAIR_REQUIRED, FAILED }

    /** What to keep for a file both computers changed. */
    public enum Choice { MINE, GITHUB, BOTH }

    /**
     * One side's last change to a conflicting file. {@code computer} is null when unknown;
     * {@code deleted} means that side removed the file.
     */
    public record Version(String author, String computer, Instant when, boolean deleted) {
    }

    /** A competing file or field, with a revision-bound choice key for catalogue values. */
    public record FieldConflict(String entity, String pointer, String before, String mine, String github) { }

    public record Conflict(String path, Version mine, Version github, String choiceKey, FieldConflict field) {
        public Conflict(String path, Version mine, Version github) { this(path, mine, github, path, null); }
        /** The version changed most recently: the default suggestion. */
        public Choice newer() {
            if (mine.when() == null || github.when() == null) return Choice.MINE;
            return github.when().isAfter(mine.when()) ? Choice.GITHUB : Choice.MINE;
        }
    }

    /**
     * {@code conflictCopies}: GitHub's versions saved next to this computer's ones (choice
     * {@link Choice#BOTH}), relative to the campaign. {@code conflicts}: files waiting for the
     * GM's decision when the outcome is {@link Outcome#CONFLICT}.
     */
    public record Result(Outcome outcome, List<String> conflictCopies, List<Conflict> conflicts, String message) {
        static Result of(Outcome outcome, String message) {
            return new Result(outcome, List.of(), List.of(), message);
        }
    }

    private final Path folder;
    private final CredentialsProvider credentials;
    private final Identity identity;
    private final Clock clock;

    public CampaignRepo(Path folder, CredentialsProvider credentials, Identity identity) {
        this(folder, credentials, identity, Clock.systemDefaultZone());
    }

    public CampaignRepo(Path folder, CredentialsProvider credentials, Identity identity, Clock clock) {
        this.folder = folder;
        this.credentials = credentials;
        this.identity = identity;
        this.clock = clock;
    }

    // ---- settings -----------------------------------------------------------------------------

    public static boolean isRepository(Path folder) {
        return folder != null && Files.isDirectory(folder.resolve(".git"));
    }

    public static Optional<String> remoteUrl(Path folder) {
        if (!isRepository(folder)) return Optional.empty();
        try (Git git = Git.open(folder.toFile())) {
            String url = git.getRepository().getConfig().getString("remote", REMOTE, "url");
            return url == null || url.isBlank() ? Optional.empty() : Optional.of(url);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** True when the folder is linked to a remote and syncing hasn't been switched off. */
    public static boolean isSynced(Path folder) {
        if (remoteUrl(folder).isEmpty()) return false;
        try (Git git = Git.open(folder.toFile())) {
            return git.getRepository().getConfig().getBoolean("almerion", null, "sync", true);
        } catch (IOException e) {
            return false;
        }
    }

    public static void setSyncEnabled(Path folder, boolean enabled) throws IOException {
        try (Git git = Git.open(folder.toFile())) {
            StoredConfig config = git.getRepository().getConfig();
            config.setBoolean("almerion", null, "sync", enabled);
            config.save();
        }
    }

    /** {@code owner/name} for a GitHub URL, otherwise the URL itself. */
    public static String displayName(String url) {
        if (url == null) return "";
        Matcher m = GITHUB_URL.matcher(url.trim());
        return m.find() ? m.group(1) + "/" + m.group(2) : url;
    }

    // ---- operations ---------------------------------------------------------------------------

    /**
     * Turns the campaign folder into a working copy of {@code remoteUrl} and syncs it. Works for an
     * empty repository (everything is pushed) and for one that already has the campaign (merged).
     */
    public Result connect(String remoteUrl) {
        try {
            Files.createDirectories(folder);
            try (Git git = isRepository(folder) ? Git.open(folder.toFile())
                    : Git.init().setDirectory(folder.toFile()).setInitialBranch(DEFAULT_BRANCH).call()) {
                StoredConfig config = git.getRepository().getConfig();
                config.setString("remote", REMOTE, "url", remoteUrl);
                config.setString("remote", REMOTE, "fetch", "+refs/heads/*:refs/remotes/" + REMOTE + "/*");
                config.setBoolean("almerion", null, "sync", true);
                config.save();
                configure(git);
                ensureGitignore();
                commitLocal(git);
                fetch(git);
                adoptRemoteBranchName(git);
                return mergeAndPush(git, Map.of());
            }
        } catch (Exception e) {
            return failure(e);
        }
    }

    /** Clones {@code remoteUrl} into {@code target}, which must not exist yet. */
    public static Result cloneInto(String remoteUrl, Path target, CredentialsProvider credentials, Identity identity) {
        if (Files.exists(target)) return Result.of(Outcome.FAILED, "A campaign folder named \""
            + target.getFileName() + "\" already exists.");
        try (Git git = Git.cloneRepository().setURI(remoteUrl).setDirectory(target.toFile())
                .setCredentialsProvider(credentials).setTimeout(TIMEOUT_SECONDS).call()) {
            CampaignRepo repo = new CampaignRepo(target, credentials, identity);
            repo.configure(git);
            git.getRepository().getConfig().setBoolean("almerion", null, "sync", true);
            git.getRepository().getConfig().save();
            return Result.of(Outcome.SYNCED, "Downloaded.");
        } catch (Exception e) {
            deleteRecursively(target);
            return failure(e);
        }
    }

    /** Commits local changes, merges GitHub's changes, and pushes. Used when a campaign is opened or closed. */
    public Result sync() {
        return resolve(Map.of());
    }

    public static boolean hasIndexLock(Path folder) {
        return isRepository(folder) && Files.exists(folder.resolve(".git/index.lock"),
            java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Only after the user confirms that other Almerion and Git processes are stopped.
     * A lock's age cannot tell us whether another process is still using it.
     */
    public Result recoverIndexLock() {
        Path lock = folder.resolve(".git/index.lock");
        try {
            if (hasIndexLock(folder)) {
                if (!Files.isRegularFile(lock, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    return Result.of(Outcome.FAILED, "The index lock is not a regular file. It was not changed.");
                }
                Path backups = folder.resolve(".git/almerion-lock-backups");
                Files.createDirectories(backups);
                Path backup = backups.resolve("index.lock-" + java.util.UUID.randomUUID());
                Files.move(lock, backup);
            }
        } catch (IOException e) {
            return Result.of(Outcome.FAILED, "Could not back up the index lock. Close other Almerion and Git"
                + " programs and check the campaign folder permissions, then try again.\n\n" + rootMessage(e));
        }
        return sync();
    }

    /** User-confirmed repair of earlier rejected uploads; published commits and working files stay intact. */
    public Result repairOversizedHistory() {
        try (Git git = Git.open(folder.toFile())) {
            if (hasIndexLock(folder)) return Result.of(Outcome.FAILED,
                "Git is locked. Close other Git operations before repairing campaign history.");
            configure(git);
            fetch(git); // Establish published history before deciding which commits may be rewritten.
            ensureGitignore();
            commitLocal(git);
            String backup = LargeFileHistory.repair(git.getRepository());
            Result result = mergeAndPush(git, Map.of());
            if (backup == null) return result;
            return new Result(result.outcome(), result.conflictCopies(), result.conflicts(),
                "Original local history is backed up at " + backup + ". Campaign files were not deleted.\n\n"
                    + result.message());
        } catch (Exception e) {
            return failure(e);
        }
    }

    /**
     * As {@link #sync()}, settling conflicts by path or revision-bound field choice key.
     * Missing or stale field choices return reviewable conflicts without applying the merge.
     */
    public Result resolve(Map<String, Choice> choices) {
        try (Git git = Git.open(folder.toFile())) {
            configure(git);
            ensureGitignore();
            commitLocal(git);
            fetch(git);
            return mergeAndPush(git, choices);
        } catch (Exception e) {
            return failure(e);
        }
    }

    /** Commits and pushes without touching the working files - safe while the campaign is open. */
    public Result commitAndPush() {
        try (Git git = Git.open(folder.toFile())) {
            configure(git);
            commitLocal(git);
            return push(git);
        } catch (Exception e) {
            return failure(e);
        }
    }

    // ---- steps --------------------------------------------------------------------------------

    private void configure(Git git) throws IOException {
        StoredConfig config = git.getRepository().getConfig();
        // Store files byte for byte, whatever the user's global Git settings say.
        config.setBoolean("core", null, "autocrlf", false);
        config.setBoolean("commit", null, "gpgsign", false);
        config.setString("user", null, "name", identity.name());
        config.setString("user", null, "email", identity.email());
        String branch = git.getRepository().getBranch();
        config.setString("branch", branch, "remote", REMOTE);
        config.setString("branch", branch, "merge", Constants.R_HEADS + branch);
        config.save();
    }

    private void ensureGitignore() throws IOException {
        Path ignore = folder.resolve(".gitignore");
        if (!Files.exists(ignore)) Files.writeString(ignore, GITIGNORE, StandardCharsets.UTF_8);
    }

    private boolean commitLocal(Git git) throws GitAPIException, IOException {
        ManualFiles.prepare(folder, git);
        org.eclipse.jgit.api.Status status = git.status().call();
        if (status.isClean()) return false;
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        // Dated when the files were last edited, not when they happened to be synced, so a
        // conflict shows which computer really has the newer version.
        Set<String> changed = new TreeSet<>(status.getModified());
        changed.addAll(status.getUntracked());
        changed.addAll(status.getAdded());
        changed.addAll(status.getChanged());
        Instant edited = null;
        for (String path : changed) {
            if (path.equals(".gitignore")) continue;
            try {
                Instant modified = Files.getLastModifiedTime(folder.resolve(path)).toInstant();
                if (edited == null || modified.isAfter(edited)) edited = modified;
            } catch (IOException ignored) {
                // Deleted or unreadable: the commit time is used.
            }
        }
        Instant now = clock.instant();
        commit(git, COMMIT_PREFIX + computerName(), edited == null || edited.isAfter(now) ? now : edited);
        return true;
    }

    private RevCommit commit(Git git, String message) throws GitAPIException {
        return commit(git, message, clock.instant());
    }

    private RevCommit commit(Git git, String message, Instant authored) throws GitAPIException {
        PersonIdent author = new PersonIdent(identity.name(), identity.email(), authored, ZoneId.systemDefault());
        PersonIdent committer = new PersonIdent(identity.name(), identity.email(), clock.instant(), ZoneId.systemDefault());
        return git.commit().setMessage(message).setAuthor(author).setCommitter(committer).setSign(false).call();
    }

    private void fetch(Git git) throws GitAPIException {
        git.fetch().setRemote(REMOTE).setCredentialsProvider(credentials).setTimeout(TIMEOUT_SECONDS).call();
    }

    /** A repository created elsewhere may use another default branch (e.g. master); follow it. */
    private void adoptRemoteBranchName(Git git) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        String branch = repo.getBranch();
        List<Ref> remote = repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + REMOTE + "/");
        if (remote.isEmpty() || repo.exactRef(remoteRef(branch)) != null) return;
        String other = remote.get(0).getName().substring((Constants.R_REMOTES + REMOTE + "/").length());
        if (repo.resolve(Constants.HEAD) == null) {
            RefUpdate head = repo.getRefDatabase().newUpdate(Constants.HEAD, false);
            head.link(Constants.R_HEADS + other);
        } else {
            git.branchRename().setOldName(branch).setNewName(other).call();
        }
        configure(git);
    }

    private Result mergeAndPush(Git git, Map<String, Choice> choices) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        Ref remote = repo.exactRef(remoteRef(repo.getBranch()));
        Result merged = null;
        if (remote != null) {
            ObjectId head = repo.resolve(Constants.HEAD);
            try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
                if (head == null || !walk.isMergedInto(walk.parseCommit(remote.getObjectId()), walk.parseCommit(head))) {
                    com.dnd.data.CampaignRestorePoints.beforeIdentifiedCampaign(folder, "Before GitHub update");
                }
            }
            if (head == null) {
                RefUpdate update = repo.updateRef(Constants.R_HEADS + repo.getBranch());
                update.setNewObjectId(remote.getObjectId());
                update.update();
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call();
            } else {
                boolean hasManualManifest = Files.exists(folder.resolve(ManualFiles.MANIFEST))
                    || read(repo, remote.getObjectId(), ManualFiles.MANIFEST) != null;
                byte[] manualMerge = hasManualManifest
                    ? mergeManualManifest(repo, head, remote.getObjectId(), choices.get(ManualFiles.MANIFEST)) : null;
                Map<String, CatalogueMerge.Result> catalogues = mergeCatalogues(repo, head, remote.getObjectId(), choices);
                List<Conflict> fieldConflicts = describeFields(repo, head, remote.getObjectId(), catalogues);
                MergeResult result = git.merge().include(remote).setStrategy(MergeStrategy.RECURSIVE)
                    .setCommit(true).setMessage("Merge campaign changes from GitHub").call();
                switch (result.getMergeStatus()) {
                    case CONFLICTING -> {
                        Set<String> conflicts = new TreeSet<>(result.getConflicts().keySet());
                        Map<String, Choice> effective = new java.util.HashMap<>(choices);
                        if (conflicts.contains(ManualFiles.MANIFEST) && manualMerge != null) {
                            effective.putIfAbsent(ManualFiles.MANIFEST, Choice.MINE);
                        }
                        Set<String> undecided = new TreeSet<>(conflicts);
                        undecided.removeAll(effective.keySet());
                        undecided.removeAll(catalogues.keySet());
                        if (!undecided.isEmpty() || !fieldConflicts.isEmpty()) {
                            List<Conflict> described = new ArrayList<>(describe(repo, head, remote.getObjectId(), undecided));
                            described.addAll(fieldConflicts);
                            // Undo the half-done merge: this computer's files and commits stay as they were.
                            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                                .setRef(head.name()).call();
                            return new Result(Outcome.CONFLICT, List.of(), described, conflictMessage(described.size()));
                        }
                        merged = settle(git, head, remote.getObjectId(), conflicts, effective, catalogues);
                    }
                    case FAILED, ABORTED, NOT_SUPPORTED, CHECKOUT_CONFLICT -> {
                        return Result.of(Outcome.FAILED, "Couldn't merge the changes from GitHub ("
                            + result.getMergeStatus() + "). Your files on this computer are unchanged.");
                    }
                    default -> { }
                }
                if (!fieldConflicts.isEmpty()) {
                    git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(head.name()).call();
                    return new Result(Outcome.CONFLICT, List.of(), fieldConflicts, conflictMessage(fieldConflicts.size()));
                }
                boolean catalogueChanged = false;
                for (var entry : catalogues.entrySet()) {
                    Path file = folder.resolve(entry.getKey());
                    if (!Files.exists(file) || !Arrays.equals(Files.readAllBytes(file), entry.getValue().bytes())) {
                        Files.createDirectories(file.getParent());
                        Files.write(file, entry.getValue().bytes());
                        git.add().addFilepattern(entry.getKey()).call();
                        catalogueChanged = true;
                    }
                }
                if (catalogueChanged) commit(git, "Merge catalogue entities and fields");
                if (hasManualManifest) {
                    if (manualMerge == null) {
                        List<Conflict> described = describe(repo, head, remote.getObjectId(), Set.of(ManualFiles.MANIFEST));
                        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(head.name()).call();
                        return new Result(Outcome.CONFLICT, List.of(), described, conflictMessage(described.size()));
                    }

                    Path manifest = folder.resolve(ManualFiles.MANIFEST);
                    if (!Files.exists(manifest) || !Arrays.equals(Files.readAllBytes(manifest), manualMerge)) {
                        Files.write(manifest, manualMerge);
                        git.add().addFilepattern(ManualFiles.MANIFEST).call();
                        commit(git, "Merge manual-transfer file metadata");
                    }
                }
            }
        }
        Result pushed = push(git);
        return merged != null && pushed.outcome() == Outcome.SYNCED ? merged : pushed;
    }

    private static Map<String, CatalogueMerge.Result> mergeCatalogues(Repository repo, ObjectId ours, ObjectId theirs,
                                                                     Map<String, Choice> choices) throws IOException {
        ObjectId base = mergeBase(repo, ours, theirs);
        Map<String, CatalogueMerge.Result> results = new TreeMap<>();
        for (String path : CatalogueMerge.CATALOGUES.keySet()) {
            byte[] before = base == null ? null : read(repo, base, path);
            byte[] mine = read(repo, ours, path), remote = read(repo, theirs, path);
            if (Arrays.equals(mine, remote) || Arrays.equals(before, mine) || Arrays.equals(before, remote)) continue;
            CatalogueMerge.merge(path, before, mine, remote, choices).ifPresent(result -> results.put(path, result));
        }
        return results;
    }

    private static List<Conflict> describeFields(Repository repo, ObjectId ours, ObjectId theirs,
                                                Map<String, CatalogueMerge.Result> catalogues) throws IOException {
        List<Conflict> conflicts = new ArrayList<>();
        ObjectId base = mergeBase(repo, ours, theirs);
        for (var entry : catalogues.entrySet()) {
            if (entry.getValue().conflicts().isEmpty()) continue;
            Version mine = lastChange(repo, ours, base, entry.getKey()), remote = lastChange(repo, theirs, base, entry.getKey());
            for (var field : entry.getValue().conflicts()) {
                conflicts.add(new Conflict(entry.getKey(), mine, remote, field.key(),
                    new FieldConflict(field.entity(), field.pointer(), field.before(), field.mine(), field.github())));
            }
        }
        return conflicts;
    }

    static String conflictMessage(int conflicts) {
        return "Conflicting files or catalogue fields: " + conflicts
            + ". Nothing was overwritten: choose which version to keep.";
    }

    private byte[] mergeManualManifest(Repository repo, ObjectId ours, ObjectId theirs, Choice choice) throws IOException {
        ObjectId base = mergeBase(repo, ours, theirs);
        return ManualFiles.merge(folder, base == null ? null : read(repo, base, ManualFiles.MANIFEST),
            read(repo, ours, ManualFiles.MANIFEST), read(repo, theirs, ManualFiles.MANIFEST), choice);
    }

    /** Writes the chosen version of each conflicting file and commits the merge. */
    private Result settle(Git git, ObjectId ours, ObjectId theirs, Set<String> conflicts, Map<String, Choice> choices,
                          Map<String, CatalogueMerge.Result> catalogues)
            throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        String stamp = STAMP.format(LocalDateTime.now(clock));
        List<String> copies = new ArrayList<>();
        for (String path : conflicts) {
            byte[] mine = read(repo, ours, path);
            byte[] remote = read(repo, theirs, path);
            Choice choice = choices.get(path);
            byte[] keep = catalogues.containsKey(path) ? catalogues.get(path).bytes() : choice == Choice.GITHUB ? remote : mine;
            if (path.equals(ManualFiles.MANIFEST)) keep = mergeManualManifest(repo, ours, theirs, choice);
            Path file = folder.resolve(path);
            if (keep != null) {
                Files.createDirectories(file.getParent());
                Files.write(file, keep);
                git.add().addFilepattern(path).call();
            } else {
                Files.deleteIfExists(file);
                git.rm().addFilepattern(path).call();
            }
            if (choice == Choice.BOTH && !catalogues.containsKey(path) && !path.equals(ManualFiles.MANIFEST)
                && remote != null && !Arrays.equals(mine, remote)) {
                String copy = path + ".remote-" + stamp;
                Path copyFile = folder.resolve(copy);
                Files.createDirectories(copyFile.getParent());
                Files.write(copyFile, remote);
                git.add().addFilepattern(copy).call();
                copies.add(copy);
            }
        }
        commit(git, "Merge campaign changes from GitHub (chose versions of " + conflicts.size() + " file"
            + (conflicts.size() == 1 ? "" : "s") + ")");
        return new Result(Outcome.SYNCED, copies, List.of(), copies.isEmpty() ? "Synced."
            : "Synced. GitHub's version was saved next to yours as: " + String.join(", ", copies));
    }

    /** Who last changed each file on either side since the two were last in step, and when. */
    private static List<Conflict> describe(Repository repo, ObjectId ours, ObjectId theirs, Set<String> paths)
            throws IOException {
        ObjectId base = mergeBase(repo, ours, theirs);
        List<Conflict> out = new ArrayList<>();
        for (String path : paths) {
            out.add(new Conflict(path, lastChange(repo, ours, base, path), lastChange(repo, theirs, base, path)));
        }
        return out;
    }

    private static ObjectId mergeBase(Repository repo, ObjectId a, ObjectId b) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(a));
            walk.markStart(walk.parseCommit(b));
            RevCommit base = walk.next();
            return base == null ? null : base.copy();
        }
    }

    private static Version lastChange(Repository repo, ObjectId tip, ObjectId base, String path) throws IOException {
        boolean deleted = read(repo, tip, path) == null;
        try (Git git = new Git(repo)) {
            var log = git.log().add(tip).addPath(path).setMaxCount(1);
            if (base != null) log.not(base);
            for (RevCommit commit : log.call()) {
                PersonIdent author = commit.getAuthorIdent();
                return new Version(author.getName(), computerOf(commit.getFullMessage()), author.getWhenAsInstant(),
                    deleted);
            }
        } catch (GitAPIException e) {
            throw new IOException(e);
        }
        RevCommit commit;
        try (RevWalk walk = new RevWalk(repo)) {
            commit = walk.parseCommit(tip);
        }
        PersonIdent author = commit.getAuthorIdent();
        return new Version(author.getName(), computerOf(commit.getFullMessage()), author.getWhenAsInstant(), deleted);
    }

    static String computerOf(String message) {
        if (message == null || !message.startsWith(COMMIT_PREFIX)) return null;
        String rest = message.substring(COMMIT_PREFIX.length()).strip();
        int line = rest.indexOf('\n');
        return line < 0 ? rest : rest.substring(0, line).strip();
    }

    private static byte[] read(Repository repo, ObjectId commit, String path) throws IOException {
        try (RevWalk walk = new RevWalk(repo); ObjectReader reader = repo.newObjectReader()) {
            RevCommit rev = walk.parseCommit(commit);
            try (TreeWalk tree = TreeWalk.forPath(reader, path, rev.getTree())) {
                return tree == null ? null : reader.open(tree.getObjectId(0)).getBytes();
            }
        }
    }

    private Result push(Git git) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        if (repo.resolve(Constants.HEAD) == null) return Result.of(Outcome.SYNCED, "Nothing to sync yet.");
        List<String> oversized = LargeFileHistory.oversized(repo);
        if (!oversized.isEmpty()) {
            String message = "Upload stopped before sending file data to GitHub: oversized files exist in"
                + " unpublished campaign commits. Use Repair oversized history in the sync settings."
                + " Your files are unchanged.\n\n" + String.join("\n", oversized);
            return Result.of(Outcome.HISTORY_REPAIR_REQUIRED, message);
        }
        String ref = Constants.R_HEADS + repo.getBranch();
        Iterable<PushResult> results = git.push().setRemote(REMOTE).setRefSpecs(new RefSpec(ref + ":" + ref))
            .setCredentialsProvider(credentials).setTimeout(TIMEOUT_SECONDS).call();
        for (PushResult result : results) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                switch (update.getStatus()) {
                    case OK, UP_TO_DATE -> { }
                    case REJECTED_NONFASTFORWARD, REJECTED_REMOTE_CHANGED -> {
                        return Result.of(Outcome.REMOTE_AHEAD, "GitHub has newer changes. They'll be merged the"
                            + " next time this campaign is opened or closed.");
                    }
                    default -> {
                        return pushFailure(update.getStatus(), update.getMessage(), result.getMessages());
                    }
                }
            }
        }
        return Result.of(Outcome.SYNCED, "Synced.");
    }

    static Result pushFailure(RemoteRefUpdate.Status status, String reason, String serverMessages) {
        String message = "GitHub refused the upload (" + status
            + (reason == null || reason.isBlank() ? "" : ": " + reason) + ")."
            + "\n\nYour campaign files and local commits are kept on this device. GitHub has not accepted"
            + " this upload. Do not delete or re-clone the campaign to fix this.";
        if (serverMessages != null && !serverMessages.isBlank()) {
            message += "\n\nGitHub's explanation:\n" + serverMessages.strip();
        } else {
            message += "\n\nGitHub did not provide a detailed explanation. Check this repository's rules"
                + " and file-size limits; the rejection alone does not identify which restriction applied.";
        }
        return Result.of(Outcome.FAILED, message);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static String remoteRef(String branch) {
        return Constants.R_REMOTES + REMOTE + "/" + branch;
    }

    static Result failure(Exception e) {
        String text = messages(e).toLowerCase(Locale.ROOT);
        if (text.contains("not authorized") || text.contains("authentication is required")
                || text.contains("authentication not supported") || text.contains("403")) {
            return Result.of(Outcome.AUTH_FAILED, "GitHub didn't accept the sign-in. Link your GitHub account again"
                + " (and make sure it can access this repository).");
        }
        if (text.contains("repository not found") || (e instanceof TransportException && text.contains("not found"))) {
            return Result.of(Outcome.FAILED, "The GitHub repository wasn't found, or your account can't access it.");
        }
        if (e instanceof TransportException || hasNetworkCause(e)) {
            return Result.of(Outcome.OFFLINE, "Can't reach GitHub right now. Your changes are kept and will be"
                + " synced later.");
        }
        if (text.contains("cannot lock") || text.contains("index.lock")) {
            return Result.of(Outcome.FAILED, "Git could not lock this campaign's index. Close other Almerion"
                + " windows and Git programs, then retry. If a previous process crashed, its index.lock may"
                + " need to be removed after confirming no Git process is running.\n\n" + rootMessage(e));
        }
        return Result.of(Outcome.FAILED, rootMessage(e));
    }

    /** The most specific message in the cause chain ("add command failed" alone tells the user nothing). */
    static String rootMessage(Throwable e) {
        String message = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) message = t.getMessage();
        }
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private static String messages(Throwable e) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) all.append(' ').append(t.getMessage());
        return all.toString();
    }

    private static boolean hasNetworkCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof ConnectException
                || t instanceof SocketTimeoutException || t instanceof NoRouteToHostException) return true;
        }
        return false;
    }

    private static String computerName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) name = System.getenv("HOSTNAME");
        return name == null || name.isBlank() ? "another computer" : name;
    }

    static void deleteRecursively(Path root) {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort: a half-cloned folder is only clutter.
                }
            });
        } catch (IOException ignored) {
            // Nothing more to do.
        }
    }
}
