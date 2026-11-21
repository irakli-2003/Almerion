package com.dnd.data;

import com.dnd.security.PrivateAccount;
import com.dnd.security.PrivateCharacterNotes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Separate encrypted campaign files, never part of a character sheet or player snapshot. */
public final class CharacterNotes {
    public record Loaded(String text, String version) { }
    private static final Object WRITES = new Object();
    private final Path directory;
    private final String campaign;
    private final PrivateAccount account;

    public CharacterNotes(Path campaignRoot, String campaign, PrivateAccount account) {
        this(campaignRoot, campaign, account, "gm-character-notes");
    }

    public static CharacterNotes loreBook(Path campaignRoot, String campaign, PrivateAccount account) {
        return new CharacterNotes(campaignRoot, campaign, account, "gm-lore");
    }

    private CharacterNotes(Path campaignRoot, String campaign, PrivateAccount account, String folder) {
        this.directory = campaignRoot.resolve(folder).toAbsolutePath().normalize();
        this.campaign = campaign;
        this.account = account;
    }

    public Loaded read(String character, List<PrivateAccount.Identity> authorizedWriters) {
        String raw = raw(character);
        return raw == null ? new Loaded("", null)
            : new Loaded(PrivateCharacterNotes.decrypt(raw, campaign, character, account, authorizedWriters), raw);
    }

    String raw(String character) {
        Path file = file(character);
        try {
            rejectLinks(file);
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the encrypted character note.", e);
        }
    }

    public void save(String character, Loaded expected, String text, List<PrivateAccount.Identity> recipients) {
        replace(character, expected.version(), () -> PrivateCharacterNotes.encrypt(campaign, character, text, account, recipients));
    }

    void replace(String character, String expected, java.util.function.Supplier<String> replacement) {
        synchronized (WRITES) {
            Path file = file(character);
            Path temporary = null;
            try {
                Path locks = AppHome.resolve("data", "auth", "character-note-locks");
                Files.createDirectories(locks);
                String lockName = digest(directory.toString() + "\n" + character) + ".lock";
                try (FileChannel channel = FileChannel.open(locks.resolve(lockName),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     var ignored = channel.lock()) {
                    rejectLinks(file);
                    String current = Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                        ? Files.readString(file, StandardCharsets.UTF_8) : null;
                    if (!Objects.equals(expected, current)) {
                        throw new IllegalStateException("The document changed elsewhere. Reopen it before saving.");
                    }
                    String next = replacement.get();
                    Files.createDirectories(directory);
                    rejectLinks(file);
                    temporary = Files.createTempFile(directory, ".character-note-", ".tmp");
                    Files.writeString(temporary, next, StandardCharsets.UTF_8);
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not save the encrypted character note.", e);
            } finally {
                if (temporary != null) {
                    try { Files.deleteIfExists(temporary); }
                    catch (IOException e) { throw new UncheckedIOException("Could not remove a temporary note file.", e); }
                }
            }
        }
    }

    private Path file(String character) {
        if (character == null || character.isBlank()) throw new IllegalArgumentException("Character ID is required.");
        return directory.resolve(digest(character) + ".json");
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private void rejectLinks(Path file) {
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) {
            throw new SecurityException("Symbolic links are not allowed for character notes.");
        }
    }
}
