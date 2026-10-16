package com.dnd.auth;

import com.dnd.cloud.Cloud;
import com.dnd.cloud.CloudConfig;
import com.dnd.security.PasswordHasher;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Registration / login / email-verification / password-recovery logic.
 *
 * <p>Emails go out one of two ways:</p>
 * <ul>
 *   <li><b>Links</b> via {@link EmailLinkAuth} (Firebase Authentication), when an API key is
 *       configured. Nothing secret ships with the app: the provider sends the mail.</li>
 *   <li><b>Codes</b> via SMTP ({@link EmailService}). When SMTP isn't configured the code is
 *       shown on screen instead (see {@link CodeResult#emailSent()}), so the flow stays usable.
 *       Password-reset codes are only ever shown on screen for device-local accounts: for
 *       shared online accounts that would let anyone take over anyone's account.</li>
 * </ul>
 * <p>One-time codes are stored hashed, because accounts live in the shared directory.</p>
 */
public class AuthService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final long CODE_VALIDITY_MINUTES = 15;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserDirectory store;
    private final EmailService emailService;
    private final EmailLinkAuth emailLinks;
    private final boolean allowOnScreenResetCodes;
    /** Why the most recent email send failed, surfaced to the UI via {@link CodeResult#emailError()}. */
    private String lastEmailError;

    public AuthService() {
        this(defaultDirectory(), new EmailService(), defaultEmailLinks(), !Cloud.config().enabled());
    }

    public AuthService(UserDirectory store, EmailService emailService) {
        this(store, emailService, null, true);
    }

    public AuthService(UserDirectory store, EmailService emailService, EmailLinkAuth emailLinks,
                       boolean allowOnScreenResetCodes) {
        this.store = store;
        this.emailService = emailService;
        this.emailLinks = emailLinks;
        this.allowOnScreenResetCodes = allowOnScreenResetCodes;
    }

    private static UserDirectory defaultDirectory() {
        return new CloudUserDirectory(Cloud.store(), new UserStore());
    }

    private static EmailLinkAuth defaultEmailLinks() {
        CloudConfig config = Cloud.config();
        return config.emailLinksEnabled() ? new FirebaseEmailLinkAuth(config.authUrl(), config.apiKey()) : null;
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * Result of an operation that needs to convey a one-time code (or link) to the user.
     * {@code sentAsLink} means a clickable link was emailed and there is no code to type.
     */
    public record CodeResult(boolean success, String message, String code, boolean emailSent, String emailError,
                             boolean sentAsLink) {
        public CodeResult(boolean success, String message, String code, boolean emailSent, String emailError) {
            this(success, message, code, emailSent, emailError, false);
        }

        public static CodeResult failure(String message) {
            return new CodeResult(false, message, null, false, null, false);
        }

        static CodeResult link(String message) {
            return new CodeResult(true, message, null, true, null, true);
        }
    }

    /** Result of a plain success/fail operation (verify, reset). */
    public record SimpleResult(boolean success, String message) {
    }

    /** Result of a login attempt. */
    public record LoginResult(boolean success, String message, User user) {
        public static LoginResult failure(String message) {
            return new LoginResult(false, message, null);
        }
    }

    public CodeResult register(String username, String email, String password, String confirmPassword) {
        if (username == null || username.isBlank()) return CodeResult.failure("Username is required.");
        username = username.trim();
        email = email == null ? null : email.trim();
        if (!isValidEmail(email)) return CodeResult.failure("Please enter a valid email address.");
        if (password == null || password.length() < 6) return CodeResult.failure("Password must be at least 6 characters.");
        if (!password.equals(confirmPassword)) return CodeResult.failure("Passwords do not match.");
        if (store.findByUsername(username).isPresent()) return CodeResult.failure("That username is already taken.");
        if (store.findByEmail(email).isPresent()) return CodeResult.failure("An account with that email already exists.");

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        String salt = PasswordHasher.generateSalt();
        user.setPasswordSalt(salt);
        user.setPasswordHash(PasswordHasher.strongHash(password, salt));
        user.setEmailVerified(false);
        String code = generateCode();
        setVerificationCode(user, code);
        store.save(user);

        if (emailLinks != null) {
            try {
                emailLinks.sendVerification(email, password);
                user.setVerificationCode(null);
                user.setVerificationExpiresAtMillis(null);
                store.save(user);
                return CodeResult.link("Account created. We emailed a verification link to " + email
                    + ". Click it (check spam too), then log in.");
            } catch (EmailLinkAuth.EmailLinkException e) {
                lastEmailError = e.getMessage();
                // Fall through to the code flow.
            }
        }

        boolean sent = tryEmail(email,
            "Verify your DnD Campaign Manager account",
            "Welcome, " + username + "!\n\nYour verification code is: " + code
                + "\n\nIt expires in " + CODE_VALIDITY_MINUTES + " minutes.");
        return new CodeResult(true, "Account created. Enter the verification code to continue.", code, sent, lastEmailError);
    }

    public CodeResult resendVerification(String email) {
        Optional<User> found = store.findByEmail(email);
        if (found.isEmpty()) return CodeResult.failure("No account found with that email.");
        User user = found.get();
        if (user.isEmailVerified()) return CodeResult.failure("This account is already verified.");
        if (usesEmailLinks() && user.getVerificationCode() == null) {
            // Sending a link needs the password, so the login screen resends it instead.
            return CodeResult.failure("Log in with your password to get a new verification link.");
        }
        String code = generateCode();
        setVerificationCode(user, code);
        store.save(user);
        boolean sent = tryEmail(email, "Your new verification code",
            "Your verification code is: " + code + "\n\nIt expires in " + CODE_VALIDITY_MINUTES + " minutes.");
        return new CodeResult(true, "A new code was generated.", code, sent, lastEmailError);
    }

    public SimpleResult verifyEmail(String email, String code) {
        Optional<User> found = store.findByEmail(email);
        if (found.isEmpty()) return new SimpleResult(false, "No account found with that email.");
        User user = found.get();
        if (user.isEmailVerified()) return new SimpleResult(true, "This account is already verified.");
        if (!codeMatches(user, user.getVerificationCode(), code)) {
            return new SimpleResult(false, "Incorrect verification code.");
        }
        if (user.getVerificationExpiresAtMillis() != null && System.currentTimeMillis() > user.getVerificationExpiresAtMillis()) {
            return new SimpleResult(false, "This code has expired. Request a new one.");
        }
        user.setEmailVerified(true);
        user.setVerificationCode(null);
        user.setVerificationExpiresAtMillis(null);
        store.save(user);
        return new SimpleResult(true, "Email verified! You can now log in.");
    }

    public LoginResult login(String identifier, String password) {
        Optional<User> found = store.findByEmailOrUsername(identifier == null ? null : identifier.trim());
        if (found.isEmpty()) return LoginResult.failure("No account found with that username/email.");
        User user = found.get();
        boolean changed = false;

        boolean passwordOk = PasswordHasher.matches(password, user.getPasswordSalt(), user.getPasswordHash());
        if (!passwordOk && emailLinks != null) {
            // The password may have been reset through the emailed link: the provider then
            // knows the new one. Only trust it once the provider has seen the inbox owner.
            try {
                if (emailLinks.isEmailVerified(user.getEmail(), password)) {
                    passwordOk = true;
                    user.setEmailVerified(true);
                    setPassword(user, password);
                    changed = true;
                }
            } catch (EmailLinkAuth.EmailLinkException ignored) {
                // Wrong password there too, or offline.
            }
        }
        if (!passwordOk) return LoginResult.failure("Incorrect password.");

        if (!user.isEmailVerified() && emailLinks != null) {
            try {
                if (emailLinks.isEmailVerified(user.getEmail(), password)) {
                    user.setEmailVerified(true);
                    changed = true;
                }
            } catch (EmailLinkAuth.EmailLinkException ignored) {
                // No provider account yet: sendVerification below creates one.
            }
            if (!user.isEmailVerified()) {
                try {
                    emailLinks.sendVerification(user.getEmail(), password);
                    return LoginResult.failure("Please verify your email first: we've emailed a fresh link to "
                        + user.getEmail() + " (check spam). Click it, then log in again.");
                } catch (EmailLinkAuth.EmailLinkException e) {
                    // Fall back to the code-based message.
                }
            }
        }
        if (!user.isEmailVerified()) {
            return LoginResult.failure("Please verify your email before logging in.");
        }
        if (!user.getPasswordHash().startsWith("pbkdf2$")) {
            setPassword(user, password);
            changed = true;
        }
        if (changed) {
            try {
                store.save(user);
            } catch (RuntimeException ignored) {
                // Offline: the upgrade is retried on the next login.
            }
        }
        return new LoginResult(true, "Welcome back, " + user.getUsername() + "!", user);
    }

    public CodeResult requestPasswordReset(String email) {
        Optional<User> found = store.findByEmail(email == null ? null : email.trim());
        if (found.isEmpty()) return CodeResult.failure("No account found with that email.");
        User user = found.get();

        if (emailLinks != null) {
            try {
                emailLinks.sendPasswordReset(user.getEmail());
                return CodeResult.link("We emailed a password reset link to " + user.getEmail()
                    + ". Choose a new password there, then log in here with it.");
            } catch (EmailLinkAuth.EmailLinkException e) {
                lastEmailError = e.getMessage();
                // EMAIL_NOT_FOUND: an older account the provider doesn't know yet - use a code.
            }
        }

        String code = generateCode();
        user.setResetCode(PasswordHasher.hash(code, user.getPasswordSalt()));
        user.setResetExpiresAtMillis(System.currentTimeMillis() + CODE_VALIDITY_MINUTES * 60_000L);
        boolean sent = tryEmail(user.getEmail(), "Reset your DnD Campaign Manager password",
            "Your password reset code is: " + code + "\n\nIt expires in " + CODE_VALIDITY_MINUTES + " minutes.");
        if (!sent && !allowOnScreenResetCodes) {
            return CodeResult.failure("Password reset needs email delivery, which isn't set up for this app yet."
                + " Ask whoever manages the app to configure it.");
        }
        store.save(user);
        return new CodeResult(true, "Enter the reset code and your new password.", code, sent, lastEmailError);
    }

    public SimpleResult resetPassword(String email, String code, String newPassword, String confirmPassword) {
        Optional<User> found = store.findByEmail(email);
        if (found.isEmpty()) return new SimpleResult(false, "No account found with that email.");
        User user = found.get();
        if (!codeMatches(user, user.getResetCode(), code)) {
            return new SimpleResult(false, "Incorrect reset code.");
        }
        if (user.getResetExpiresAtMillis() != null && System.currentTimeMillis() > user.getResetExpiresAtMillis()) {
            return new SimpleResult(false, "This code has expired. Request a new one.");
        }
        if (newPassword == null || newPassword.length() < 6) {
            return new SimpleResult(false, "Password must be at least 6 characters.");
        }
        if (!newPassword.equals(confirmPassword)) {
            return new SimpleResult(false, "Passwords do not match.");
        }
        setPassword(user, newPassword);
        user.setResetCode(null);
        user.setResetExpiresAtMillis(null);
        store.save(user);
        return new SimpleResult(true, "Password reset. You can now log in.");
    }

    /** True when verification and reset emails are sent as links rather than codes. */
    public boolean usesEmailLinks() {
        return emailLinks != null;
    }

    private static void setPassword(User user, String password) {
        String salt = PasswordHasher.generateSalt();
        user.setPasswordSalt(salt);
        user.setPasswordHash(PasswordHasher.strongHash(password, salt));
    }

    private void setVerificationCode(User user, String code) {
        user.setVerificationCode(PasswordHasher.hash(code, user.getPasswordSalt()));
        user.setVerificationExpiresAtMillis(System.currentTimeMillis() + CODE_VALIDITY_MINUTES * 60_000L);
    }

    /** Codes are stored hashed; older accounts may still hold a plain one. */
    private static boolean codeMatches(User user, String stored, String entered) {
        if (stored == null || entered == null || entered.isBlank()) return false;
        String trimmed = entered.trim();
        return stored.equals(PasswordHasher.hash(trimmed, user.getPasswordSalt())) || stored.equals(trimmed);
    }

    private boolean tryEmail(String to, String subject, String body) {
        try {
            emailService.send(to, subject, body);
            lastEmailError = null;
            return true;
        } catch (Exception e) {
            // Record why it failed so the UI can tell the user the actual reason (bad App
            // Password, blocked port, ...) instead of a generic "email isn't set up" message,
            // then fall back to showing the code on-screen so the flow still completes.
            lastEmailError = e.getMessage();
            return false;
        }
    }

    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
