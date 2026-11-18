package com.dnd.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.dnd.model.session.TrackedCreature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.*;

/** An owner-signed key-grant envelope containing encrypted text AND encounter roster. */
public final class PrivateSessionFiles {
    public static final String PREFIX = "ALMERION-PRIVATE-SESSION/1\n";
    private static final String ROLE_PREFIX = "ALMERION-PRIVATE-SESSION/2\n";
    private static final String OWNERS_PREFIX = "ALMERION-PRIVATE-SESSION/3\n";
    public enum Access { READ, WRITE, OWNER }
    public record OwnerRevision(String signer, Set<String> owners, String policyHash,
                                String handoffHash, String signature) {
        public OwnerRevision { owners = Collections.unmodifiableSet(new TreeSet<>(owners)); }
    }
    public record Header(String fileId, String ownerUid, Map<String, String> grants,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Set<String> readOnly,
                         @JsonInclude(JsonInclude.Include.NON_NULL) List<OwnerRevision> ownership) {
        public Header {
            grants = Collections.unmodifiableMap(new TreeMap<>(grants));
            if (readOnly != null) readOnly = Collections.unmodifiableSet(new TreeSet<>(readOnly));
            if (ownership != null) ownership = List.copyOf(ownership);
        }
        public Header(String fileId, String ownerUid, Map<String, String> grants) {
            this(fileId, ownerUid, grants, null, null);
        }
        public Set<String> owners() {
            return ownership == null || ownership.isEmpty() ? Set.of(ownerUid) : ownership.getLast().owners();
        }
        public Access access(String uid) {
            if (uid == null || !grants.containsKey(uid)) return null;
            if (readOnly != null && readOnly.contains(uid)) return Access.READ;
            return owners().contains(uid) ? Access.OWNER : Access.WRITE;
        }
        private String prefix() { return ownership != null ? OWNERS_PREFIX : readOnly == null ? PREFIX : ROLE_PREFIX; }
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
        String prefix = raw.startsWith(PREFIX) ? PREFIX : raw.startsWith(ROLE_PREFIX) ? ROLE_PREFIX
            : raw.startsWith(OWNERS_PREFIX) ? OWNERS_PREFIX : null;
        if (prefix == null) throw new SecurityException("Unsupported encrypted session-file version.");
        Envelope envelope = PrivateAccount.read(raw.substring(prefix.length()), Envelope.class);
        Header header = envelope.header();
        if (header == null || header.grants() == null || header.grants().isEmpty()
            || !prefix.equals(header.prefix()) || header.ownerUid() == null
            || (header.ownership() != null && (header.ownership().isEmpty() || header.readOnly() == null))
            || header.owners().isEmpty() || header.owners().stream().anyMatch(uid -> header.access(uid) != Access.OWNER)
            || (!canWrite(header, envelope.writerUid()) && header.ownership() == null)
            || (header.readOnly() != null && !header.grants().keySet().containsAll(header.readOnly()))
            || envelope.ciphertext() == null || envelope.signature() == null || envelope.ownerSignature() == null) {
            throw new SecurityException("Incomplete encrypted session-file permissions.");
        }
        FirebasePrivateStore.validateKey(header.fileId());
        FirebasePrivateStore.validateKey(header.ownerUid());
        FirebasePrivateStore.validateKey(envelope.writerUid());
        header.grants().keySet().forEach(FirebasePrivateStore::validateKey);
        if (header.readOnly() != null && header.grants().values().stream()
            .anyMatch(value -> value == null || !value.matches("[A-Za-z0-9+/]+={0,2}"))) {
            throw new SecurityException("Invalid encrypted session-file key grant.");
        }
        return envelope;
    }

    public static String create(Content content, PrivateAccount owner) {
        return create(content, owner, List.of());
    }

    public static String create(Content content, PrivateAccount owner, List<PrivateAccount.Identity> editors) {
        require(owner);
        Map<PrivateAccount.Identity, Access> grants = new LinkedHashMap<>();
        editors.forEach(identity -> grants.put(identity, Access.WRITE));
        return replacePermissions(UUID.randomUUID().toString(), content, owner, grants, null, true);
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
        if (!canWrite(envelope.header(), account.uid())) {
            throw new SecurityException("You do not have write access to this session file.");
        }
        byte[] key = key(envelope, account);
        try {
            return encode(encrypt(envelope.header(), envelope.ownerSignature(), content, key, account));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static String grant(String raw, PrivateAccount owner, List<PrivateAccount.Identity> recipients) {
        Map<PrivateAccount.Identity, Access> access = new LinkedHashMap<>();
        recipients.forEach(identity -> access.put(identity, Access.WRITE));
        return grant(raw, owner, access);
    }

    public static String grant(String raw, PrivateAccount owner, Map<PrivateAccount.Identity, Access> recipients) {
        return grant(raw, owner, recipients, true);
    }

    public static String replaceAccess(String raw, PrivateAccount owner, Map<PrivateAccount.Identity, Access> recipients) {
        return grant(raw, owner, recipients, false);
    }

    private static String grant(String raw, PrivateAccount owner, Map<PrivateAccount.Identity, Access> recipients,
                                boolean retainOwners) {
        require(owner);
        Envelope envelope = verified(raw, owner);
        requireOwner(envelope, owner);
        Content content = read(raw, owner);
        return replacePermissions(envelope.header().fileId(), content, owner, recipients, envelope.header(), retainOwners);
    }

    public static void requireOwner(Envelope envelope, PrivateAccount account) {
        require(account);
        if (!envelope.header().owners().contains(account.uid())) {
            throw new SecurityException("Only a session-file owner can change access, move, or delete this file.");
        }
    }

    public static Envelope verified(String raw, PrivateAccount account) {
        require(account);
        Envelope envelope = parse(raw);
        Header header = envelope.header();
        if (header.ownership() == null) {
            PrivateAccount.Identity owner = account.identity(header.ownerUid());
            PrivateFileCrypto.verify(headerText(header), envelope.ownerSignature(),
                PrivateFileCrypto.publicKey(owner.signingKey()));
        } else {
            verifyOwnership(envelope, account);
        }
        PrivateAccount.Identity writer = account.identity(envelope.writerUid());
        PrivateFileCrypto.verify(context(envelope) + "\n" + envelope.ciphertext(), envelope.signature(),
            PrivateFileCrypto.publicKey(writer.signingKey()));
        return envelope;
    }

    private static boolean canWrite(Header header, String uid) {
        Access access = header.access(uid);
        return access == Access.WRITE || access == Access.OWNER;
    }

    private static String replacePermissions(String fileId, Content content, PrivateAccount owner,
                                             Map<PrivateAccount.Identity, Access> recipients, Header previous,
                                             boolean retainOwners) {
        Map<String, String> grants = new TreeMap<>();
        Set<String> readOnly = new TreeSet<>();
        Set<String> owners = new TreeSet<>();
        String creator = previous == null ? owner.uid() : previous.ownerUid();
        Map<PrivateAccount.Identity, Access> all = new LinkedHashMap<>(recipients);
        if (retainOwners) {
            for (String uid : previous == null ? Set.of(creator) : previous.owners()) {
                all.put(owner.identity(uid), Access.OWNER);
            }
        }
        byte[] key = PrivateFileCrypto.dataKey();
        try {
            for (var entry : all.entrySet()) {
                var candidate = entry.getKey();
                PrivateAccount.Identity trusted = owner.identity(candidate.uid());
                if (!trusted.equals(candidate)) throw new SecurityException("Recipient identity changed. Review access again.");
                grants.put(trusted.uid(), PrivateFileCrypto.wrap(key, PrivateFileCrypto.publicKey(trusted.encryptionKey())));
                switch (Objects.requireNonNull(entry.getValue())) {
                    case READ -> readOnly.add(trusted.uid());
                    case OWNER -> owners.add(trusted.uid());
                    case WRITE -> { }
                }
            }
            if (owners.isEmpty()) throw new IllegalArgumentException("At least one file owner must remain.");
            if (!owners.equals(Set.of(creator)) || previous != null && previous.ownership() != null) {
                return encode(transition(fileId, creator, grants, readOnly, owners, previous, content, key, owner));
            }
            boolean versionTwo = !readOnly.isEmpty() || previous != null && previous.readOnly() != null;
            Header header = new Header(fileId, creator, grants, versionTwo ? readOnly : null, null);
            String ownerSignature = PrivateFileCrypto.sign(headerText(header), owner.signingKey());
            return encode(encrypt(header, ownerSignature, content, key, owner));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static Envelope transition(String fileId, String creator, Map<String, String> grants, Set<String> readOnly,
                                       Set<String> owners, Header previous, Content content, byte[] key, PrivateAccount signer) {
        List<OwnerRevision> chain = new ArrayList<>();
        if (previous != null && previous.ownership() != null) chain.addAll(previous.ownership());
        Header pending = new Header(fileId, creator, grants, readOnly, chain);
        Envelope written = encrypt(pending, "", content, key, signer);
        // A departing owner can seal the handoff contents once, but is not a writer under the new policy.
        String handoff = !grants.containsKey(signer.uid()) || readOnly.contains(signer.uid()) ? hash(written.ciphertext()) : "";
        String policy = hash(headerText(pending));
        String prior = chain.isEmpty() ? "" : chain.getLast().signature();
        String signature = PrivateFileCrypto.sign(revisionText(pending, signer.uid(), owners, policy, handoff, prior), signer.signingKey());
        chain.add(new OwnerRevision(signer.uid(), owners, policy, handoff, signature));
        Header header = new Header(fileId, creator, grants, readOnly, chain);
        return new Envelope(header, signature, written.writerUid(), written.ciphertext(), written.signature());
    }

    private static void verifyOwnership(Envelope envelope, PrivateAccount account) {
        Header header = envelope.header();
        Set<String> authorized = Set.of(header.ownerUid());
        String prior = "";
        for (OwnerRevision step : header.ownership()) {
            if (step.owners().isEmpty() || !authorized.contains(step.signer())) {
                throw new SecurityException("Invalid session-file ownership history.");
            }
            step.owners().forEach(FirebasePrivateStore::validateKey);
            if (!validHash(step.policyHash()) || step.handoffHash() == null
                || !step.handoffHash().isEmpty() && !validHash(step.handoffHash())) {
                throw new SecurityException("Invalid session-file ownership policy.");
            }
            PrivateFileCrypto.verify(revisionText(header, step.signer(), step.owners(), step.policyHash(),
                step.handoffHash(), prior), step.signature(), PrivateFileCrypto.publicKey(account.identity(step.signer()).signingKey()));
            authorized = step.owners();
            prior = step.signature();
        }
        OwnerRevision last = header.ownership().getLast();
        if (!prior.equals(envelope.ownerSignature()) || !hash(headerText(header)).equals(last.policyHash())
            || (!canWrite(header, envelope.writerUid()) && !(envelope.writerUid().equals(last.signer())
                && hash(envelope.ciphertext()).equals(last.handoffHash())))) {
            throw new SecurityException("Invalid session-file ownership policy or writer.");
        }
    }

    private static String revisionText(Header header, String signer, Set<String> owners, String policy, String handoff,
                                       String prior) {
        return OWNERS_PREFIX + header.fileId() + "\n" + header.ownerUid() + "\n" + signer + "\n"
            + String.join(",", owners) + "\n" + policy + "\n" + handoff + "\n" + prior;
    }

    private static boolean validHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Envelope encrypt(Header header, String ownerSignature, Content content, byte[] key, PrivateAccount writer) {
        Envelope unsigned = new Envelope(header, ownerSignature, writer.uid(), "", "");
        String ciphertext = PrivateFileCrypto.encrypt(PrivateAccount.json(content), key, context(unsigned));
        String signature = PrivateFileCrypto.sign(context(unsigned) + "\n" + ciphertext, writer.signingKey());
        return new Envelope(header, ownerSignature, writer.uid(), ciphertext, signature);
    }

    private static String headerText(Header header) {
        StringBuilder text = new StringBuilder(header.prefix()).append(header.fileId()).append('\n').append(header.ownerUid());
        header.grants().forEach((uid, grant) -> text.append('\n').append(uid).append(':').append(grant));
        if (header.readOnly() != null) header.readOnly().forEach(uid -> text.append("\nread-only:").append(uid));
        return text.toString();
    }

    private static String context(Envelope envelope) {
        if (envelope.header().ownership() != null) {
            return OWNERS_PREFIX + hash(headerText(envelope.header())) + "\n" + envelope.writerUid();
        }
        return envelope.header().prefix() + envelope.header().fileId() + "\n" + envelope.header().ownerUid()
            + "\n" + envelope.ownerSignature() + "\n" + envelope.writerUid();
    }

    private static byte[] key(Envelope envelope, PrivateAccount account) {
        String wrapped = envelope.header().grants().get(account.uid());
        if (wrapped == null) throw new SecurityException("You do not have access to this session file. Request access from its owner.");
        return PrivateFileCrypto.unwrap(wrapped, account.encryptionKey());
    }

    private static String encode(Envelope envelope) { return envelope.header().prefix() + PrivateAccount.json(envelope); }
    private static void require(PrivateAccount account) {
        if (account == null || !account.isUnlocked()) throw new SecurityException("Unlock your private-file account first.");
    }
}
