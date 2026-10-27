package com.dnd.github;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** The GitHub account linked on this computer, shared by the whole app. */
public final class GitHubLink {
    private static volatile GitHubLink shared;

    private final TokenStore store;
    private final HttpTransport http;
    private final GitHubConfig config;
    private final List<Consumer<Optional<GitHubAccount>>> listeners = new CopyOnWriteArrayList<>();
    private volatile Optional<GitHubAccount> account;

    public GitHubLink(TokenStore store, HttpTransport http, GitHubConfig config) {
        this.store = store;
        this.http = http;
        this.config = config;
    }

    public static GitHubLink shared() {
        if (shared == null) {
            synchronized (GitHubLink.class) {
                if (shared == null) {
                    shared = new GitHubLink(TokenStore.forCurrentUser(), new JdkHttpTransport(), GitHubConfig.load());
                }
            }
        }
        return shared;
    }

    public GitHubConfig config() {
        return config;
    }

    public HttpTransport http() {
        return http;
    }

    /** The linked account; the first call reads (and on Windows decrypts) the stored token, so call it off the UI thread. */
    public Optional<GitHubAccount> account() {
        Optional<GitHubAccount> current = account;
        if (current == null) {
            synchronized (this) {
                if (account == null) account = store.load();
                current = account;
            }
        }
        return current;
    }

    /** The account without touching the disk; empty until {@link #account()} has run once. */
    public Optional<GitHubAccount> cachedAccount() {
        Optional<GitHubAccount> current = account;
        return current == null ? Optional.empty() : current;
    }

    public GitHubApi api() {
        return new GitHubApi(http, account().map(GitHubAccount::token)
            .orElseThrow(() -> new GitHubException("Link your GitHub account first.")));
    }

    /** Checks {@code token} with GitHub, then remembers it for this OS user. Network: call off the UI thread. */
    public GitHubAccount link(String token) throws IOException {
        GitHubAccount verified = new GitHubApi(http, token.trim()).user();
        store.save(verified);
        account = Optional.of(verified);
        listeners.forEach(l -> l.accept(account));
        return verified;
    }

    public void unlink() throws IOException {
        store.clear();
        account = Optional.empty();
        listeners.forEach(l -> l.accept(account));
    }

    public void addListener(Consumer<Optional<GitHubAccount>> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<Optional<GitHubAccount>> listener) {
        listeners.remove(listener);
    }
}
