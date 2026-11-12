package com.dnd.security;

import com.dnd.model.session.TrackedCreature;

import java.util.*;

/** An owner-signed key-grant envelope containing encrypted text AND encounter roster. */
public final class PrivateSessionFiles {
    public static final String PREFIX = "ALMERION-PRIVATE-SESSION/1\n";
    public record Header(String fileId, String ownerUid, Map<String, String> grants) {
        public Header { grants = Collections.unmodifiableMap(new TreeMap<>(grants)); }
    }
    public record Envelope(Header header, String ownerSignature, String writerUid, String ciphertext, String signature) { }
    public record Content(String text, List<TrackedCreature> roster) {
        public Content {
            text = text == null ? "" : text;
            roster = roster == null ? List.of() : List.copyOf(roster);
        }
    }

    private PrivateSessionFiles() { }

    public static boolean isProtected(String raw) {
        return raw != null && raw.startsWith("ALMERION-PRIVATE-SESSION/");
    }

    public static Envelope parse(String raw) {
        if (!raw.startsWith(PREFIX)) throw new SecurityException("Unsupported encrypted session-file version.");
        Envelope envelope = PrivateAccount.read(raw.substring(PREFIX.length()), Envelope.class);
        Header header = envelope.header();
        if (header == null || header.grants() == null || header.grants().isEmpty()
            || !header.grants().containsKey(header.ownerUid()) || !header.grants().containsKey(envelope.writerUid())
            || envelope.ciphertext() == null || envelope.signature() == null || envelope.ownerSignature() == null) {
            throw new SecurityException("Incomplete encrypted session-file permissions.");
        }
        FirebasePrivateStore.validateKey(header.fileId());
        FirebasePrivateStore.validateKey(header.ownerUid());
        FirebasePrivateStore.validateKey(envelope.writerUid());
        header.grants().keySet().forEach(FirebasePrivateStore::validateKey);
        return envelope;
    }

    public static String create(Content content, PrivateAccount owner) {
        require(owner);
        return replacePermissions(UUID.randomUUID().toString(), owner.uid(), content, owner, List.of(owner.identity()));
    }

    public static Content read(String raw, PrivateAccount account) {
        require(account);
        Envelope envelope = verified(raw, account);
        byte[] key = key(envelope, account);
        try {
            String plain = PrivateFileCrypto.decrypt(envelope.ciphertext(), key, context(envelope));
            return PrivateAccount.read(plain, Content.class);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static String write(String raw, Content content, PrivateAccount account) {
        require(account);
        Envelope envelope = verified(raw, account);
        byte[] key = key(envelope, account);
        try {
            return encode(encrypt(envelope.header(), envelope.ownerSignature(), content, key, account));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static String grant(String raw, PrivateAccount owner, List<PrivateAccount.Identity> recipients) {
        require(owner);
        Envelope envelope = verified(raw, owner);
        requireOwner(envelope, owner);
        Content content = read(raw, owner);
        return replacePermissions(envelope.header().fileId(), owner.uid(), content, owner, recipients);
    }

    public static void requireOwner(Envelope envelope, PrivateAccount account) {
        require(account);
        if (!envelope.header().ownerUid().equals(account.uid())) {
            throw new SecurityException("Only the session-file owner can change access, move, or delete this file.");
        }
    }

    public static Envelope verified(String raw, PrivateAccount account) {
        require(account);
        Envelope envelope = parse(raw);
        PrivateAccount.Identity owner = account.identity(envelope.header().ownerUid());
        PrivateFileCrypto.verify(headerText(envelope.header()), envelope.ownerSignature(),
            PrivateFileCrypto.publicKey(owner.signingKey()));
        PrivateAccount.Identity writer = account.identity(envelope.writerUid());
        PrivateFileCrypto.verify(context(envelope) + "\n" + envelope.ciphertext(), envelope.signature(),
            PrivateFileCrypto.publicKey(writer.signingKey()));
        return envelope;
    }

    private static String replacePermissions(String fileId, String ownerUid, Content content, PrivateAccount owner,
                                             List<PrivateAccount.Identity> recipients) {
        Map<String, String> grants = new TreeMap<>();
        byte[] key = PrivateFileCrypto.dataKey();
        try {
            List<PrivateAccount.Identity> all = new ArrayList<>(recipients);
            all.add(owner.identity());
            for (PrivateAccount.Identity candidate : all) {
                PrivateAccount.Identity trusted = owner.identity(candidate.uid());
                if (!trusted.equals(candidate)) throw new SecurityException("Recipient identity changed. Review access again.");
                grants.put(trusted.uid(), PrivateFileCrypto.wrap(key, PrivateFileCrypto.publicKey(trusted.encryptionKey())));
            }
            Header header = new Header(fileId, ownerUid, grants);
            String ownerSignature = PrivateFileCrypto.sign(headerText(header), owner.signingKey());
            return encode(encrypt(header, ownerSignature, content, key, owner));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static Envelope encrypt(Header header, String ownerSignature, Content content, byte[] key, PrivateAccount writer) {
        Envelope unsigned = new Envelope(header, ownerSignature, writer.uid(), "", "");
        String ciphertext = PrivateFileCrypto.encrypt(PrivateAccount.json(content), key, context(unsigned));
        String signature = PrivateFileCrypto.sign(context(unsigned) + "\n" + ciphertext, writer.signingKey());
        return new Envelope(header, ownerSignature, writer.uid(), ciphertext, signature);
    }

    private static String headerText(Header header) {
        StringBuilder text = new StringBuilder(PREFIX).append(header.fileId()).append('\n').append(header.ownerUid());
        header.grants().forEach((uid, grant) -> text.append('\n').append(uid).append(':').append(grant));
        return text.toString();
    }

    private static String context(Envelope envelope) {
        return PREFIX + envelope.header().fileId() + "\n" + envelope.header().ownerUid()
            + "\n" + envelope.ownerSignature() + "\n" + envelope.writerUid();
    }

    private static byte[] key(Envelope envelope, PrivateAccount account) {
        String wrapped = envelope.header().grants().get(account.uid());
        if (wrapped == null) throw new SecurityException("You do not have access to this session file. Request access from its owner.");
        return PrivateFileCrypto.unwrap(wrapped, account.encryptionKey());
    }

    private static String encode(Envelope envelope) { return PREFIX + PrivateAccount.json(envelope); }
    private static void require(PrivateAccount account) {
        if (account == null || !account.isUnlocked()) throw new SecurityException("Unlock your private-file account first.");
    }
}
