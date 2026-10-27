package com.dnd.github;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TokenStoreTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** Stand-in for DPAPI: reversible, but the stored text differs from the token. */
    private static final TokenStore.Protector REVERSE = new TokenStore.Protector() {
        @Override
        public String name() {
            return "reverse";
        }

        @Override
        public String protect(String secret) {
            return new StringBuilder(secret).reverse().toString();
        }

        @Override
        public String unprotect(String stored) {
            return new StringBuilder(stored).reverse().toString();
        }
    };

    private Path file() {
        return temp.getRoot().toPath().resolve("github").resolve("account.json");
    }

    @Test
    public void roundTripsThroughTheProtector() throws Exception {
        TokenStore store = new TokenStore(file(), REVERSE);
        assertTrue(store.load().isEmpty());
        store.save(new GitHubAccount(42, "gm", "ghp_token123"));
        String onDisk = Files.readString(file());
        assertFalse("token must not be stored as is", onDisk.contains("ghp_token123"));
        assertTrue(onDisk.contains("\"protection\" : \"reverse\""));
        GitHubAccount loaded = store.load().orElseThrow();
        assertEquals(new GitHubAccount(42, "gm", "ghp_token123"), loaded);
        assertEquals("42+gm@users.noreply.github.com", loaded.noreplyEmail());
        store.clear();
        assertTrue(store.load().isEmpty());
        assertFalse(Files.exists(file()));
    }

    @Test
    public void fallsBackToPlainStorageWhenTheProtectorFails() throws Exception {
        TokenStore.Protector broken = new TokenStore.Protector() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public String protect(String secret) throws IOException {
                throw new IOException("no DPAPI here");
            }

            @Override
            public String unprotect(String stored) throws IOException {
                throw new IOException("no DPAPI here");
            }
        };
        TokenStore store = new TokenStore(file(), broken);
        store.save(new GitHubAccount(1, "gm", "tok"));
        assertEquals("tok", store.load().orElseThrow().token());
    }

    @Test
    public void unreadableFilesMeanNotLinked() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{not json");
        assertTrue(new TokenStore(file(), TokenStore.NONE).load().isEmpty());
        Files.writeString(file(), "{\"login\":\"gm\",\"protection\":\"something-else\",\"token\":\"x\"}");
        assertTrue(new TokenStore(file(), TokenStore.NONE).load().isEmpty());
    }

    @Test
    public void toStringNeverShowsTheToken() {
        assertFalse(new GitHubAccount(1, "gm", "ghp_secret").toString().contains("ghp_secret"));
    }

    @Test
    public void windowsDpapiRoundTrip() throws Exception {
        Assume.assumeTrue(WindowsDpapi.isWindows());
        WindowsDpapi dpapi = new WindowsDpapi();
        String stored;
        try {
            stored = dpapi.protect("ghp_dpapi_test");
        } catch (IOException e) {
            Assume.assumeNoException("DPAPI helper unavailable on this machine", e);
            return;
        }
        assertFalse(stored.contains("ghp_dpapi_test"));
        assertEquals("ghp_dpapi_test", dpapi.unprotect(stored));
    }
}
