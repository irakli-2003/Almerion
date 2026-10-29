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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps one campaign folder in step with its GitHub repository: commit local changes, fetch,
 * merge, push.
 *
 * <p>This follows Git: when only GitHub changed, its version is taken (fast-forward); changes
 * to different files are merged automatically. <b>When both computers changed the same file,
 * nothing is decided for the GM</b>: the merge is undone (this computer's files and commits are
 * kept, nothing is pushed) and {@link Outcome#CONFLICT} lists each file with who changed it on
 * each side and when. The GM then picks a version per file and {@link #resolve} finishes the
 * merge - keeping this computer's version, GitHub's, or both (GitHub's saved next to it as
 * {@code <file>.remote-<yyyyMMdd-HHmmss>}).</p>
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

    public enum Outcome { SYNCED, CONFLICT, REMOTE_AHEAD, OFFLINE, AUTH_FAILED, FAILED }

    /** What to keep for a file both computers changed. */
    public enum Choice { MINE, GITHUB, BOTH }

    /**
     * One side's last change to a conflicting file. {@code computer} is null when unknown;
     * {@code deleted} means that side removed the file.
     */
    public record Version(String author, String computer, Instant when, boolean deleted) {
    }

    /** A file both computers changed, relative to the campaign folder. */
    public record Conflict(String path, Version mine, Version github) {
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

    /**
     * As {@link #sync()}, settling files both computers changed as {@code choices} says (path to
     * choice). If a conflicting file has no choice, nothing is merged and the conflicts are returned.
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

    private boolean commitLocal(Git git) throws GitAPIException {
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
            if (head == null) {
                RefUpdate update = repo.updateRef(Constants.R_HEADS + repo.getBranch());
                update.setNewObjectId(remote.getObjectId());
                update.update();
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call();
            } else {
                MergeResult result = git.merge().include(remote).setStrategy(MergeStrategy.RECURSIVE)
                    .setCommit(true).setMessage("Merge campaign changes from GitHub").call();
                switch (result.getMergeStatus()) {
                    case CONFLICTING -> {
                        Set<String> conflicts = new TreeSet<>(result.getConflicts().keySet());
                        if (!choices.keySet().containsAll(conflicts)) {
                            List<Conflict> described = describe(repo, head, remote.getObjectId(), conflicts);
                            // Undo the half-done merge: this computer's files and commits stay as they were.
                            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                                .setRef(head.name()).call();
                            return new Result(Outcome.CONFLICT, List.of(), described, conflictMessage(described.size()));
                        }
                        merged = settle(git, head, remote.getObjectId(), conflicts, choices);
                    }
                    case FAILED, ABORTED, NOT_SUPPORTED, CHECKOUT_CONFLICT -> {
                        return Result.of(Outcome.FAILED, "Couldn't merge the changes from GitHub ("
                            + result.getMergeStatus() + "). Your files on this computer are unchanged.");
                    }
                    default -> { }
                }
            }
        }
        Result pushed = push(git);
        return merged != null && pushed.outcome() == Outcome.SYNCED ? merged : pushed;
    }

    static String conflictMessage(int files) {
        return "Files changed both on this computer and on GitHub since they were last in step: " + files
            + ". Nothing was overwritten: choose which version to keep.";
    }

    /** Writes the chosen version of each conflicting file and commits the merge. */
    private Result settle(Git git, ObjectId ours, ObjectId theirs, Set<String> conflicts, Map<String, Choice> choices)
            throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        String stamp = STAMP.format(LocalDateTime.now(clock));
        List<String> copies = new ArrayList<>();
        for (String path : conflicts) {
            byte[] mine = read(repo, ours, path);
            byte[] remote = read(repo, theirs, path);
            Choice choice = choices.get(path);
            byte[] keep = choice == Choice.GITHUB ? remote : mine;
            Path file = folder.resolve(path);
            if (keep != null) {
                Files.createDirectories(file.getParent());
                Files.write(file, keep);
                git.add().addFilepattern(path).call();
            } else {
                Files.deleteIfExists(file);
                git.rm().addFilepattern(path).call();
            }
            if (choice == Choice.BOTH && remote != null && !Arrays.equals(mine, remote)) {
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
                        return Result.of(Outcome.FAILED, "GitHub refused the upload (" + update.getStatus()
                            + (update.getMessage() == null ? "" : ": " + update.getMessage()) + ").");
                    }
                }
            }
        }
        return Result.of(Outcome.SYNCED, "Synced.");
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
        if (text.contains("not found")) {
            return Result.of(Outcome.FAILED, "The GitHub repository wasn't found, or your account can't access it.");
        }
        if (e instanceof TransportException || hasNetworkCause(e)) {
            return Result.of(Outcome.OFFLINE, "Can't reach GitHub right now. Your changes are kept and will be"
                + " synced later.");
        }
        return Result.of(Outcome.FAILED, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
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
