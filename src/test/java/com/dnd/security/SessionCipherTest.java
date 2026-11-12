package com.dnd.security;

import org.junit.Test;

import static org.junit.Assert.*;

public class SessionCipherTest {

    @Test
    public void encryptAndDecryptRoundTrip() {
        String plaintext = "{\"hp\":42,\"map\":\"dungeon\"}";
        String passphrase = "super-secret-dnd-key";

        String ciphertext = SessionCipher.encrypt(plaintext, passphrase);
        assertNotNull(ciphertext);
        assertNotEquals(plaintext, ciphertext);

        String decrypted = SessionCipher.decrypt(ciphertext, passphrase);
        assertEquals(plaintext, decrypted);
    }

    @Test
    public void encryptProducesUniqueCiphertextsForSamePlaintext() {
        String plaintext = "same content";
        String passphrase = "key";

        String c1 = SessionCipher.encrypt(plaintext, passphrase);
        String c2 = SessionCipher.encrypt(plaintext, passphrase);
        // Each call uses a fresh random salt + IV
        assertNotEquals("Ciphertexts should differ due to random IV/salt", c1, c2);
        // Both must decrypt to the same plaintext
        assertEquals(plaintext, SessionCipher.decrypt(c1, passphrase));
        assertEquals(plaintext, SessionCipher.decrypt(c2, passphrase));
    }

    @Test(expected = SessionCipher.CipherException.class)
    public void decryptWithWrongPassphraseFails() {
        String ciphertext = SessionCipher.encrypt("secret", "correct-key");
        SessionCipher.decrypt(ciphertext, "wrong-key");
    }

    @Test(expected = SessionCipher.CipherException.class)
    public void decryptCorruptedDataFails() {
        SessionCipher.decrypt("notvalidbase64!!!", "key");
    }

    @Test
    public void tokenRoundTrip() {
        String dbUrl = "https://my-db.firebaseio.com";
        String sessionId = FirebaseSessionSync.generateSessionId();
        String passphrase = FirebaseSessionSync.generateSessionId();

        String token = FirebaseSessionSync.generateToken(dbUrl, sessionId, passphrase);
        FirebaseSessionSync sync = FirebaseSessionSync.fromToken(token);
        assertNotNull(sync);

        // Re-generate token from the same inputs and compare
        String reEncoded = FirebaseSessionSync.generateToken(dbUrl, sessionId, passphrase);
        assertEquals(token, reEncoded);
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromTokenWithInvalidTokenThrows() {
        FirebaseSessionSync.fromToken("not-a-valid-token!!!");
    }

    @Test
    public void generateSessionIdIsHexAndCorrectLength() {
        String id = FirebaseSessionSync.generateSessionId();
        assertNotNull(id);
        assertEquals(64, id.length());   // 32 bytes -> 64 hex chars
        assertTrue(id.matches("[0-9a-f]+"));
    }
}
