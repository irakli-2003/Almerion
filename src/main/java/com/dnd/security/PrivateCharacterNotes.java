package com.dnd.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Encrypted character notes, re-keyed to the current GM recipients on each save. */
public final class PrivateCharacterNotes {
    public static final String PREFIX = "ALMERION-PRIVATE-CHARACTER-NOTES/1\n";
    public record Envelope(String campaign, String character, String writer,
                           Map<String, String> grants, String ciphertext, String signature) { }

    private PrivateCharacterNotes() { }

    public static String encrypt(String campaign, String character, String text, PrivateAccount account,
                                 List<PrivateAccount.Identity> recipients) {
        validateIds(campaign, character);
        if (account == null || !account.isUnlocked()) throw new SecurityException("Unlock private files first.");
        if (text == null) throw new IllegalArgumentException("Note text is required.");
        byte[] key = PrivateFileCrypto.dataKey();
        try {
            Map<String, String> grants = new TreeMap<>();
            for (PrivateAccount.Identity recipient : recipients) {
                PrivateAccount.Identity trusted = account.identity(recipient.uid());
                if (!trusted.equals(recipient)) throw new SecurityException("A GM identity changed; review access again.");
                grants.put(trusted.uid(), PrivateFileCrypto.wrap(key, PrivateFileCrypto.publicKey(trusted.encryptionKey())));
            }
            if (!grants.containsKey(account.uid())) throw new SecurityException("The current GM must be a recipient.");
            Envelope header = new Envelope(campaign, character, account.uid(), grants, "", "");
            String context = context(header);
            String ciphertext = PrivateFileCrypto.encrypt(text, key, context);
            String signature = PrivateFileCrypto.sign(context + "\n" + ciphertext, account.signingKey());
            return PREFIX + PrivateAccount.json(new Envelope(campaign, character, account.uid(), grants, ciphertext, signature));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static String decrypt(String raw, String campaign, String character, PrivateAccount account,
                                 List<PrivateAccount.Identity> authorizedWriters) {
        validateIds(campaign, character);
        if (account == null || !account.isUnlocked()) throw new SecurityException("Unlock private files first.");
        if (raw == null || !raw.startsWith(PREFIX)) throw new SecurityException("Invalid encrypted character note.");
        Envelope envelope = PrivateAccount.read(raw.substring(PREFIX.length()), Envelope.class);
        if (envelope == null || !campaign.equals(envelope.campaign()) || !character.equals(envelope.character())
            || envelope.grants() == null || envelope.grants().isEmpty() || envelope.writer() == null
            || envelope.ciphertext() == null || envelope.signature() == null) {
            throw new SecurityException("Character note belongs to a different character or campaign.");
        }
        FirebasePrivateStore.validateKey(envelope.writer());
        envelope.grants().keySet().forEach(FirebasePrivateStore::validateKey);
        PrivateAccount.Identity writer = account.identity(envelope.writer());
        if (authorizedWriters.stream().noneMatch(writer::equals)) {
            throw new SecurityException("The note's author is not a current campaign GM. Its signed content needs administrator review before recovery.");
        }
        String context = context(envelope);
        PrivateFileCrypto.verify(context + "\n" + envelope.ciphertext(), envelope.signature(),
            PrivateFileCrypto.publicKey(writer.signingKey()));
        String wrapped = envelope.grants().get(account.uid());
        if (wrapped == null) throw new SecurityException("No key grant for this GM. Another GM must sync the note.");
        byte[] key = PrivateFileCrypto.unwrap(wrapped, account.encryptionKey());
        try {
            return PrivateFileCrypto.decrypt(envelope.ciphertext(), key, context);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static String context(Envelope value) {
        StringBuilder text = new StringBuilder(PREFIX).append(value.campaign()).append('\n')
            .append(Base64.getEncoder().encodeToString(value.character().getBytes(StandardCharsets.UTF_8)))
            .append('\n').append(value.writer());
        new TreeMap<>(value.grants()).forEach((uid, grant) -> text.append('\n').append(uid).append(':').append(grant));
        return text.toString();
    }

    private static void validateIds(String campaign, String character) {
        FirebasePrivateStore.validateKey(campaign);
        if (character == null || character.isBlank()) throw new IllegalArgumentException("Character ID is required.");
    }
}
