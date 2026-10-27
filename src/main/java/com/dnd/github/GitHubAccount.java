package com.dnd.github;

/** A linked GitHub account. {@link #toString()} leaves the token out so it can't end up in logs. */
public record GitHubAccount(long id, String login, String token) {

    /** GitHub's private commit email for the account, used as the commit author. */
    public String noreplyEmail() {
        return id + "+" + login + "@users.noreply.github.com";
    }

    @Override
    public String toString() {
        return "GitHubAccount[" + login + "]";
    }
}
