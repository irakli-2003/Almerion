package com.dnd.data;

import com.dnd.security.FirebasePrivateStore;
import com.dnd.security.PrivateAccount;
import com.dnd.security.PrivateSessionFiles;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Device-local, account-encrypted drafts. The campaign file is never an autosave target. */
public final class StorylineDrafts {
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(StorylineDrafts.class.getName());
    public record Draft(int version, String editorId, String file, String sourceId,
                        String baseRevision, long savedAt, String text) {
        @Override public String toString() { return java.time.Instant.ofEpochMilli(savedAt).toString(); }
    }

    private static final ObjectMapper MAPPER = JsonMappers.create()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Path directory;
    private final Path file;
    private final StorylineService service;
    private final PrivateAccount account;
    private final String sourceId;

    public StorylineDrafts(Path deviceRoot, StorylineService service, Path file, PrivateAccount account) {
        this.service = Objects.requireNonNull(service);
        this.account = Objects.requireNonNull(account);
        FirebasePrivateStore.validateKey(account.uid());
        this.file = file.toAbsolutePath().normalize();
        Path root = deviceRoot.toAbsolutePath().normalize();
        if (root.startsWith(service.getRoot().getParent())) {
            throw new IllegalArgumentException("Recovery drafts must stay outside the campaign.");
        }
        sourceId = service.editorPrivateFileId(file);
        directory = root.resolve(account.uid()).resolve(hash(this.file.toString()));
        verifySource();
    }

    public synchronized void save(String editorId, String baseRevision, String text) {
        Path target = path(editorId);
        if (text == null || baseRevision == null || !baseRevision.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid recovery draft.");
        }
        verifySource();
        Draft draft = new Draft(1, editorId, file.toString(), sourceId, baseRevision, System.currentTimeMillis(), text);
        String encoded;
        try {
            encoded = MAPPER.writeValueAsString(draft);
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode the recovery draft.");
        }
        String encrypted = PrivateSessionFiles.create(new PrivateSessionFiles.Content(encoded, List.of()), account);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            checkLinks();
            temporary = Files.createTempFile(directory, ".draft-write-", ".tmp");
            Files.writeString(temporary, encrypted, StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the encrypted recovery draft.", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    LOGGER.log(java.util.logging.Level.WARNING, "Could not remove encrypted draft temporary file.", e);
                }
            }
        }
    }

    public synchronized List<Draft> list() {
        verifySource();
        checkLinks();
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            List<Draft> result = new ArrayList<>();
            for (Path candidate : files.filter(p -> p.getFileName().toString().endsWith(".draft")).toList()) {
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    throw new SecurityException("Recovery draft links are not supported.");
                }
                String raw = Files.readString(candidate, StandardCharsets.UTF_8);
                var header = PrivateSessionFiles.parse(raw).header();
                if (!account.uid().equals(header.ownerUid()) || header.grants().size() != 1) {
                    throw new SecurityException("Recovery draft account mismatch.");
                }
                String plain = PrivateSessionFiles.read(raw, account).text();
                Draft draft;
                try {
                    draft = MAPPER.readValue(plain, Draft.class);
                } catch (IOException e) {
                    throw new SecurityException("Unreadable encrypted recovery draft.");
                }
                if (draft.version() != 1 || draft.editorId() == null || draft.text() == null
                    || draft.baseRevision() == null || !draft.baseRevision().matches("[a-f0-9]{64}")
                    || draft.savedAt() <= 0 || !file.toString().equals(draft.file())
                    || !Objects.equals(sourceId, draft.sourceId()) || !candidate.equals(path(draft.editorId()))) {
                    throw new SecurityException("Recovery draft does not match this session file.");
                }
                result.add(draft);
            }
            return result.stream().sorted(Comparator.comparingLong(Draft::savedAt).reversed()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read encrypted recovery drafts.", e);
        }
    }

    public synchronized void discard(String editorId) {
        checkLinks();
        try {
            Files.deleteIfExists(path(editorId));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not remove the encrypted recovery draft.", e);
        }
    }

    private void verifySource() {
        if (!account.isUnlocked()) throw new SecurityException("Unlock your private-file account first.");
        if (sourceId != null && (!service.isPrivate(file) || !sourceId.equals(service.permissions(file).fileId()))) {
            throw new SecurityException("Recovery draft does not match this session file.");
        }
        if (service.isPrivate(file) && !service.permissions(file).grants().containsKey(account.uid())) {
            throw new SecurityException("You do not have access to this session file. Request access from its owner.");
        }
        // A separate read must not advance the editor's optimistic-save baseline.
        service.forEditor().readText(file);
    }

    private Path path(String editorId) {
        FirebasePrivateStore.validateKey(editorId);
        Path result = directory.resolve(editorId + ".draft");
        checkLinks();
        if (Files.isSymbolicLink(result)) throw new SecurityException("Recovery draft links are not supported.");
        return result;
    }

    private void checkLinks() {
        for (Path component = directory; component != null; component = component.getParent()) {
            if (Files.isSymbolicLink(component)) throw new SecurityException("Recovery draft links are not supported.");
        }
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
