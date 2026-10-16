package com.dnd.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Minimal salted-hash helper for player character passwords.
 *
 * <p>Passwords are never stored in plaintext: a random salt is generated per
 * character, and only {@code SHA-256(salt + password)} (hex-encoded) is
 * persisted. This is a lightweight, dependency-free scheme appropriate for a
 * single-player-facing hobby app - not a substitute for a production-grade
 * KDF (e.g. bcrypt/scrypt/argon2) if this project's threat model ever
 * changes.</p>
 */
public final class PasswordHasher {
    private static final String ALGORITHM = "SHA-256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** Generates a new random salt, hex-encoded. */
    public static String generateSalt() {
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        return toHex(saltBytes);
    }

    /** Hashes {@code password} with {@code salt}, returning a hex-encoded digest. */
    public static String hash(String password, String salt) {
        String combined = (salt == null ? "" : salt) + (password == null ? "" : password);
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hashed = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            return toHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available on every standard JDK.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** @return {@code true} if hashing {@code password} with {@code salt} equals {@code expectedHash}. */
    public static boolean matches(String password, String salt, String expectedHash) {
        if (expectedHash == null) {
            return false;
        }
        if (expectedHash.startsWith(PBKDF2_PREFIX)) {
            return MessageDigest.isEqual(
                strongHash(password, salt).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
        }
        return hash(password, salt).equalsIgnoreCase(expectedHash);
    }

    private static final String PBKDF2_PREFIX = "pbkdf2$";
    private static final int PBKDF2_ITERATIONS = 120_000;

    /**
     * Slow, salted PBKDF2-HMAC-SHA256 hash for account passwords, which are stored in the
     * shared online directory. Prefixed so {@link #matches} can tell it from the legacy hash.
     */
    public static String strongHash(String password, String salt) {
        try {
            javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                (password == null ? "" : password).toCharArray(),
                (salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8),
                PBKDF2_ITERATIONS, 256);
            return PBKDF2_PREFIX + toHex(factory.generateSecret(spec).getEncoded());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }
}

