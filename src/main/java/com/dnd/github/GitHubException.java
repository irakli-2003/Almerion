package com.dnd.github;

/** A GitHub request failed with a message that can be shown to the GM as is. */
public class GitHubException extends RuntimeException {
    private final boolean authFailure;

    public GitHubException(String message) {
        this(message, false);
    }

    public GitHubException(String message, boolean authFailure) {
        super(message);
        this.authFailure = authFailure;
    }

    /** True when GitHub rejected the token, so the account has to be linked again. */
    public boolean isAuthFailure() {
        return authFailure;
    }
}
