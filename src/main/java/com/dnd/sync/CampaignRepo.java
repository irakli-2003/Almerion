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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps one campaign folder in step with its GitHub repository: commit local changes, fetch,
 * merge, push.
 *
 * <p><b>Conflicts never lose data.</b> When both computers changed the same file, the merge keeps
 * this computer's version as the working file and saves GitHub's version next to it as
 * {@code <file>.remote-<yyyyMMdd-HHmmss>}. Changes to different files are merged normally. The
 * merge is then committed and pushed, so both computers end up with both versions and the GM
 * can copy over whatever they need and delete the {@code .remote-} copy.</p>
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
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern GITHUB_URL = Pattern.compile("github\\.com[:/]([^/]+)/([^/]+?)(\\.git)?/?$");

    public record Identity(String name, String email) {
    }

    public enum Outcome { SYNCED, CONFLICT, REMOTE_AHEAD, OFFLINE, AUTH_FAILED, FAILED }

    /** {@code conflictCopies}: GitHub's versions saved next to this computer's ones, relative to the campaign. */
    public record Result(Outcome outcome, List<String> conflictCopies, String message) {
        static Result of(Outcome outcome, String message) {
            return new Result(outcome, List.of(), message);
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
                return mergeAndPush(git);
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
        try (Git git = Git.open(folder.toFile())) {
            configure(git);
            ensureGitignore();
            commitLocal(git);
            fetch(git);
            return mergeAndPush(git);
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
        if (git.status().call().isClean()) return false;
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        commit(git, "Campaign changes from " + computerName());
        return true;
    }

    private RevCommit commit(Git git, String message) throws GitAPIException {
        PersonIdent who = new PersonIdent(identity.name(), identity.email());
        return git.commit().setMessage(message).setAuthor(who).setCommitter(who).setSign(false).call();
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

    private Result mergeAndPush(Git git) throws IOException, GitAPIException {
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
                    case CONFLICTING -> merged = keepBoth(git, head, remote.getObjectId(),
                        result.getConflicts().keySet());
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

    private Result keepBoth(Git git, ObjectId ours, ObjectId theirs, Set<String> conflicts)
            throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        String stamp = STAMP.format(LocalDateTime.now(clock));
        List<String> copies = new ArrayList<>();
        for (String path : new TreeSet<>(conflicts)) {
            byte[] mine = read(repo, ours, path);
            byte[] remote = read(repo, theirs, path);
            Path file = folder.resolve(path);
            if (mine != null) {
                Files.createDirectories(file.getParent());
                Files.write(file, mine);
                git.add().addFilepattern(path).call();
            } else {
                Files.deleteIfExists(file);
                git.rm().addFilepattern(path).call();
            }
            if (remote != null && !Arrays.equals(mine, remote)) {
                String copy = path + ".remote-" + stamp;
                Path copyFile = folder.resolve(copy);
                Files.createDirectories(copyFile.getParent());
                Files.write(copyFile, remote);
                git.add().addFilepattern(copy).call();
                copies.add(copy);
            }
        }
        commit(git, "Merge campaign changes from GitHub (kept both versions of " + conflicts.size() + " file"
            + (conflicts.size() == 1 ? "" : "s") + ")");
        return new Result(Outcome.CONFLICT, copies, "Both computers changed " + conflicts.size() + " file"
            + (conflicts.size() == 1 ? "" : "s") + ". This computer's version was kept; GitHub's version was saved"
            + " next to it as: " + String.join(", ", copies));
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
