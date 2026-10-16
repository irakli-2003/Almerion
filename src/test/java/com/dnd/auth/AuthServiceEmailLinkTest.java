package com.dnd.auth;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class AuthServiceEmailLinkTest {

    /** Stands in for Firebase Authentication: remembers passwords and which inboxes clicked their link. */
    static class FakeLinks implements EmailLinkAuth {
        final Map<String, String> passwords = new HashMap<>();
        final Set<String> verified = new HashSet<>();
        int verificationsSent;
        int resetsSent;

        public void sendVerification(String email, String password) {
            passwords.putIfAbsent(email, password);
            if (!passwords.get(email).equals(password)) throw new EmailLinkException("INVALID_PASSWORD", "bad", null);
            verificationsSent++;
        }

        public boolean passwordMatches(String email, String password) {
            return password.equals(passwords.get(email));
        }

        public boolean isEmailVerified(String email, String password) {
            if (!passwordMatches(email, password)) throw new EmailLinkException("INVALID_PASSWORD", "bad", null);
            return verified.contains(email);
        }

        public void sendPasswordReset(String email) {
            if (!passwords.containsKey(email)) throw new EmailLinkException("EMAIL_NOT_FOUND", "unknown", null);
            resetsSent++;
        }
    }

    private final FakeLinks links = new FakeLinks();

    private AuthService service(boolean allowOnScreenResetCodes) throws Exception {
        UserStore store = new UserStore(Files.createTempDirectory("auth-links").resolve("users.json"));
        return new AuthService(store, new EmailService(new SmtpConfig()), links, allowOnScreenResetCodes);
    }

    @Test
    public void registrationSendsLinkAndLoginWaitsForTheClick() throws Exception {
        AuthService auth = service(false);
        AuthService.CodeResult result = auth.register("darius", "d@example.com", "secret1", "secret1");
        assertTrue(result.success());
        assertTrue(result.sentAsLink());
        assertNull("no code is shown on screen in link mode", result.code());

        assertFalse(auth.login("darius", "secret1").success());
        assertEquals(2, links.verificationsSent);

        links.verified.add("d@example.com");
        assertTrue(auth.login("darius", "secret1").success());
    }

    @Test
    public void passwordResetByLinkIsAcceptedOnlyForVerifiedInbox() throws Exception {
        AuthService auth = service(false);
        auth.register("shako", "s@example.com", "oldpass", "oldpass");
        links.verified.add("s@example.com");
        assertTrue(auth.login("shako", "oldpass").success());

        AuthService.CodeResult reset = auth.requestPasswordReset("s@example.com");
        assertTrue(reset.sentAsLink());
        assertNull(reset.code());

        links.passwords.put("s@example.com", "newpass");
        assertTrue(auth.login("shako", "newpass").success());
        assertFalse(auth.login("shako", "oldpass").success());
        assertFalse(auth.login("shako", "guess12").success());
    }

    @Test
    public void resetCodesAreNeverShownOnScreenWhenDisallowed() throws Exception {
        UserStore store = new UserStore(Files.createTempDirectory("auth-nolinks").resolve("users.json"));
        AuthService auth = new AuthService(store, new EmailService(new SmtpConfig()), null, false);
        auth.register("ratboy", "r@example.com", "cheese1", "cheese1");
        AuthService.CodeResult reset = auth.requestPasswordReset("r@example.com");
        assertFalse(reset.success());
        assertNull(reset.code());
    }

    @Test
    public void resendInLinkModeAsksToLogInInstead() throws Exception {
        AuthService auth = service(false);
        auth.register("moss", "m@example.com", "courier", "courier");
        assertFalse(auth.resendVerification("m@example.com").success());
    }

    @Test
    public void codesAreStoredHashed() throws Exception {
        Path file = Files.createTempDirectory("auth-hash").resolve("users.json");
        AuthService auth = new AuthService(new UserStore(file), new EmailService(new SmtpConfig()));
        AuthService.CodeResult result = auth.register("vito", "v@example.com", "lion123", "lion123");
        assertFalse(Files.readString(file).contains(result.code()));
        assertTrue(auth.verifyEmail("v@example.com", result.code()).success());
    }
}
