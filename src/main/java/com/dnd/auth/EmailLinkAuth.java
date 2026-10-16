package com.dnd.auth;

/**
 * Sends verification and password-reset emails as links, through an identity provider that
 * owns the mail delivery (Firebase Authentication), so no SMTP password has to ship with the
 * app. The provider keeps its own copy of the password, which is how a reset done through
 * the emailed link reaches the app.
 */
public interface EmailLinkAuth {

    /** Creates (or signs into) the provider account and emails a verification link. */
    void sendVerification(String email, String password);

    /** @return whether the provider accepts this email/password pair. */
    boolean passwordMatches(String email, String password);

    /** @return whether the link in the verification email has been clicked. */
    boolean isEmailVerified(String email, String password);

    /** Emails a password-reset link. */
    void sendPasswordReset(String email);

    class EmailLinkException extends RuntimeException {
        private final String code;

        public EmailLinkException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        /** Provider error code such as {@code EMAIL_NOT_FOUND}, or {@code NETWORK}. */
        public String code() {
            return code;
        }
    }
}
