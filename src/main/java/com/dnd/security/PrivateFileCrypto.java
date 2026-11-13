package com.dnd.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/** Standard JCA primitives; separate RSA keys are used for wrapping and signing. */
final class PrivateFileCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec("SHA-256", "MGF1",
        MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private static final PSSParameterSpec PSS = new PSSParameterSpec("SHA-256", "MGF1",
        MGF1ParameterSpec.SHA256, 32, 1);

    private PrivateFileCrypto() { }

    static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw failure(e);
        }
    }

    static String encode(Key key) { return Base64.getEncoder().encodeToString(key.getEncoded()); }

    static PublicKey publicKey(String encoded) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(encoded)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw failure(e);
        }
    }

    static PrivateKey privateKey(String encoded) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decode(encoded)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw failure(e);
        }
    }

    static byte[] dataKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }

    static String wrap(byte[] key, PublicKey recipient) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.ENCRYPT_MODE, recipient, OAEP);
            return Base64.getEncoder().encodeToString(cipher.doFinal(key));
        } catch (GeneralSecurityException e) {
            throw failure(e);
        }
    }

    static byte[] unwrap(String wrapped, PrivateKey recipient) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, recipient, OAEP);
            byte[] key = cipher.doFinal(decode(wrapped));
            if (key.length != 32) throw new GeneralSecurityException("Invalid data key");
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw failure(e);
        }
    }

    static String encrypt(String text, byte[] key, String context) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                .put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw failure(e);
        }
    }

    static String decrypt(String encoded, byte[] key, String context) {
        try {
            byte[] raw = decode(encoded);
            if (raw.length < 28) throw new GeneralSecurityException("Truncated ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, raw, 0, 12));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw failure(e);
        }
    }

    static String sign(String message, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance("RSASSA-PSS");
            signature.setParameter(PSS);
            signature.initSign(key);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw failure(e);
        }
    }

    static void verify(String message, String signed, PublicKey key) {
        try {
            Signature signature = Signature.getInstance("RSASSA-PSS");
            signature.setParameter(PSS);
            signature.initVerify(key);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            if (!signature.verify(decode(signed))) throw new GeneralSecurityException("Invalid signature");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw failure(e);
        }
    }

    private static byte[] decode(String value) { return Base64.getDecoder().decode(value); }

    private static SecurityException failure(Exception e) {
        return new SecurityException("Private-file verification failed: missing keys, altered data, or invalid signature.", e);
    }
}
