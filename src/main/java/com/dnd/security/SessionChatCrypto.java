package com.dnd.security;

import java.security.KeyPair;

/** Per-request hybrid encryption, independent of the shared player snapshot token. */
public final class SessionChatCrypto {
    public record Sealed(String key, String data) { }
    private final KeyPair keys = PrivateFileCrypto.keyPair();
    public String publicKey() { return PrivateFileCrypto.encode(keys.getPublic()); }
    public static Sealed seal(String text, String recipient, String context) {
        byte[] key = PrivateFileCrypto.dataKey();
        try {
            return new Sealed(PrivateFileCrypto.wrap(key, PrivateFileCrypto.publicKey(recipient)),
                PrivateFileCrypto.encrypt(text, key, context));
        } finally { java.util.Arrays.fill(key, (byte) 0); }
    }
    public String open(Sealed sealed, String context) {
        byte[] key = PrivateFileCrypto.unwrap(sealed.key(), keys.getPrivate());
        try { return PrivateFileCrypto.decrypt(sealed.data(), key, context); }
        finally { java.util.Arrays.fill(key, (byte) 0); }
    }
}
