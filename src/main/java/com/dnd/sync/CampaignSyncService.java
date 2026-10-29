package com.dnd.sync;

import com.dnd.github.GitHubAccount;
import com.dnd.github.GitHubLink;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs campaign syncing in the background, one job at a time, and reports a status per campaign.
 *
 * <ul>
 *   <li>Opening a synced campaign pulls first ({@link #syncNow}).</li>
 *   <li>While it is open, changes are committed and pushed every {@link #AUTOSAVE_SECONDS} seconds
 *       (files on disk are never rewritten while the GM is working in them).</li>
 *   <li>Closing it, and quitting the app, run a full sync.</li>
 *   <li>When GitHub can't be reached, the work is retried every {@link #RETRY_SECONDS} seconds.</li>
 * </ul>
 */
public final class CampaignSyncService {
    private static final Logger LOGGER = Logger.getLogger(CampaignSyncService.class.getName());
    static final long AUTOSAVE_SECONDS = 90;
    static final long RETRY_SECONDS = 120;
    /** During a battle the board changes constantly; uploading it this rarely keeps the fight smooth. */
    static final long BATTLE_AUTOSAVE_SECONDS = 600;

    public enum State { SYNCING, SYNCED, OFFLINE, CONFLICT, ERROR }

    public record Status(State state, String message) {
    }

    private static final CampaignSyncService SHARED = new CampaignSyncService(GitHubLink.shared());

    private final GitHubLink link;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "campaign-sync");
        t.setDaemon(true);
        return t;
    });
    private final Map<Path, Status> statuses = new ConcurrentHashMap<>();
    private final Map<Path, ScheduledFuture<?>> retries = new ConcurrentHashMap<>();
    private final List<BiConsumer<Path, Status>> listeners = new CopyOnWriteArrayList<>();
    private volatile Path open;
    private volatile ScheduledFuture<?> autosave;
    private final java.util.concurrent.atomic.AtomicInteger battles = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastAutosaveMillis = System.currentTimeMillis();

    CampaignSyncService(GitHubLink link) {
        this.link = link;
    }

    public static CampaignSyncService shared() {
        return SHARED;
    }

    public Optional<Status> status(Path folder) {
        return folder == null ? Optional.empty() : Optional.ofNullable(statuses.get(key(folder)));
    }

    /** Listeners are called on the sync thread; UI code must hop to the FX thread itself. */
    public void addListener(BiConsumer<Path, Status> listener) {
        listeners.add(listener);
    }

    public void removeListener(BiConsumer<Path, Status> listener) {
        listeners.remove(listener);
    }

    /** Full sync (commit, pull, push) in the background. Completes with the outcome; never fails. */
    public CompletableFuture<CampaignRepo.Result> syncNow(Path folder) {
        return submit(folder, CampaignRepo::sync);
    }

    /** Full sync that settles files both computers changed as the GM chose (path to choice). */
    public CompletableFuture<CampaignRepo.Result> resolve(Path folder, Map<String, CampaignRepo.Choice> choices) {
        Map<String, CampaignRepo.Choice> copy = Map.copyOf(choices);
        return submit(folder, repo -> repo.resolve(copy));
    }

    /** Links {@code folder} to {@code remoteUrl} and pushes / merges it. */
    public CompletableFuture<CampaignRepo.Result> connect(Path folder, String remoteUrl) {
        return submit(folder, repo -> repo.connect(remoteUrl));
    }

    /** Clones {@code remoteUrl} into {@code target}. */
    public CompletableFuture<CampaignRepo.Result> download(String remoteUrl, Path target) {
        CompletableFuture<CampaignRepo.Result> future = new CompletableFuture<>();
        executor.execute(() -> {
            Optional<GitHubAccount> account = link.account();
            future.complete(account.isEmpty() ? notLinked()
                : CampaignRepo.cloneInto(remoteUrl, target, credentials(account.get()), identity(account.get())));
        });
        return future;
    }

    /** The GM entered a campaign: keep pushing its changes while it stays open. */
    public synchronized void opened(Path folder) {
        Path key = key(folder);
        if (key.equals(open)) return;
        closed();
        if (!CampaignRepo.isSynced(folder)) return;
        open = key;
        lastAutosaveMillis = System.currentTimeMillis();
        autosave = executor.scheduleWithFixedDelay(() -> {
            if (battles.get() > 0
                    && System.currentTimeMillis() - lastAutosaveMillis < BATTLE_AUTOSAVE_SECONDS * 1000) {
                return;
            }
            lastAutosaveMillis = System.currentTimeMillis();
            run(key, CampaignRepo::commitAndPush);
        }, AUTOSAVE_SECONDS, AUTOSAVE_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * A battle map opened ({@code true}) or closed ({@code false}). While one is open, changes
     * are uploaded only every {@link #BATTLE_AUTOSAVE_SECONDS} seconds instead of every
     * {@link #AUTOSAVE_SECONDS}; nothing is lost: the normal pace resumes when the battle closes,
     * and everything is uploaded when the campaign is closed.
     */
    public void battle(boolean open) {
        if (open) battles.incrementAndGet();
        else battles.updateAndGet(n -> Math.max(0, n - 1));
    }

    /** The GM left the open campaign (if any): stop autosaving and do a full sync. */
    public synchronized void closed() {
        Path previous = open;
        open = null;
        if (autosave != null) autosave.cancel(false);
        autosave = null;
        if (previous != null) syncNow(previous);
    }

    /** Called when the app quits: a final full sync of the open campaign, waiting at most {@code timeoutMillis}. */
    public void shutdown(long timeoutMillis) {
        Path previous = open;
        synchronized (this) {
            open = null;
            if (autosave != null) autosave.cancel(false);
        }
        if (previous == null) return;
        try {
            syncNow(previous).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "Final campaign sync didn't finish before exit", e);
        }
    }

    private CompletableFuture<CampaignRepo.Result> submit(Path folder, Function<CampaignRepo, CampaignRepo.Result> job) {
        Path key = key(folder);
        CompletableFuture<CampaignRepo.Result> future = new CompletableFuture<>();
        update(key, new Status(State.SYNCING, "Syncing with GitHub..."));
        executor.execute(() -> future.complete(run(key, job)));
        return future;
    }

    private CampaignRepo.Result run(Path folder, Function<CampaignRepo, CampaignRepo.Result> job) {
        CampaignRepo.Result result;
        try {
            Optional<GitHubAccount> account = link.account();
            if (account.isEmpty()) {
                result = notLinked();
            } else {
                result = job.apply(new CampaignRepo(folder, credentials(account.get()), identity(account.get())));
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Campaign sync failed", e);
            result = CampaignRepo.Result.of(CampaignRepo.Outcome.FAILED, String.valueOf(e.getMessage()));
        }
        update(folder, toStatus(result));
        ScheduledFuture<?> pending = retries.remove(folder);
        if (pending != null) pending.cancel(false);
        if (result.outcome() == CampaignRepo.Outcome.OFFLINE) {
            retries.put(folder, executor.schedule(() -> run(folder, folder.equals(open)
                ? CampaignRepo::commitAndPush : CampaignRepo::sync), RETRY_SECONDS, TimeUnit.SECONDS));
        }
        return result;
    }

    static Status toStatus(CampaignRepo.Result result) {
        return switch (result.outcome()) {
            case SYNCED -> new Status(State.SYNCED, "Synced with GitHub");
            case CONFLICT -> new Status(State.CONFLICT, result.message());
            case REMOTE_AHEAD -> new Status(State.SYNCED, result.message());
            case OFFLINE -> new Status(State.OFFLINE, result.message());
            case AUTH_FAILED, FAILED -> new Status(State.ERROR, result.message());
        };
    }

    private void update(Path folder, Status status) {
        statuses.put(folder, status);
        for (BiConsumer<Path, Status> listener : listeners) {
            try {
                listener.accept(folder, status);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Sync status listener failed", e);
            }
        }
    }

    private static CampaignRepo.Result notLinked() {
        return CampaignRepo.Result.of(CampaignRepo.Outcome.AUTH_FAILED,
            "Link your GitHub account (Campaign Selection > GitHub) to sync this campaign.");
    }

    static UsernamePasswordCredentialsProvider credentials(GitHubAccount account) {
        return new UsernamePasswordCredentialsProvider("x-access-token", account.token());
    }

    static CampaignRepo.Identity identity(GitHubAccount account) {
        return new CampaignRepo.Identity(account.login(), account.noreplyEmail());
    }

    /** The normalized path statuses are keyed by (and listeners are called with). */
    public static Path key(Path folder) {
        return folder.toAbsolutePath().normalize();
    }
}
