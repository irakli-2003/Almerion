package com.dnd.data;

import com.dnd.security.PrivateAccount;
import com.dnd.security.PrivateCharacterNotes;
import com.dnd.security.PrivateSessionFiles;
import com.dnd.security.PrivateSessionFiles.Access;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** One document, at the same path used by older encrypted lore books. */
public final class LoreBookStore {
    public static final String DOCUMENT = "@gm-lore-book";
    public enum Visibility { SHARED, LEGACY_PRIVATE, PRIVATE }
    public record Opened(LoreBook book, String version, Visibility visibility, Access access) {
        public boolean editable() { return access == Access.WRITE || access == Access.OWNER; }
    }

    private final CharacterNotes file;
    private final String campaign;

    public LoreBookStore(Path root, String campaign) {
        this.campaign = campaign;
        this.file = CharacterNotes.loreBook(root, campaign, null);
    }

    public Visibility visibility() { return visibility(file.raw(DOCUMENT)); }

    private static Visibility visibility(String raw) {
        if (PrivateSessionFiles.isProtected(raw)) return Visibility.PRIVATE;
        if (raw != null && raw.startsWith("ALMERION-PRIVATE-CHARACTER-NOTES/")) return Visibility.LEGACY_PRIVATE;
        return Visibility.SHARED;
    }

    public Opened open(PrivateAccount account, List<PrivateAccount.Identity> legacyWriters) {
        String raw = file.raw(DOCUMENT);
        Visibility visibility = visibility(raw);
        return switch (visibility) {
            case SHARED -> new Opened(LoreBook.parse(raw), raw, visibility, Access.WRITE);
            case LEGACY_PRIVATE -> new Opened(LoreBook.parse(legacyText(raw, account, legacyWriters)), raw, visibility, Access.READ);
            case PRIVATE -> {
                String text = PrivateSessionFiles.read(raw, account).text();
                yield new Opened(LoreBook.parse(text), raw, visibility, PrivateSessionFiles.parse(raw).header().access(account.uid()));
            }
        };
    }

    public void save(Opened opened, String draft, PrivateAccount account) {
        file.replace(DOCUMENT, opened.version(), () -> {
            LoreBook.parse(draft);
            return switch (visibility(opened.version())) {
                case SHARED -> draft;
                case PRIVATE -> PrivateSessionFiles.write(opened.version(), content(draft), account);
                case LEGACY_PRIVATE -> throw new SecurityException("Upgrade the legacy private book before editing it.");
            };
        });
    }

    public void protect(Opened opened, PrivateAccount account, List<PrivateAccount.Identity> legacyWriters) {
        file.replace(DOCUMENT, opened.version(), () -> {
            return switch (visibility(opened.version())) {
                case SHARED -> PrivateSessionFiles.create(content(LoreBook.parse(opened.version()).serialize()), account);
                case LEGACY_PRIVATE -> {
                    var legacy = PrivateCharacterNotes.decryptWithGrants(opened.version(), campaign, DOCUMENT, account, legacyWriters);
                    var editors = legacyWriters.stream().filter(gm -> legacy.grantedUids().contains(gm.uid())
                        && !account.uid().equals(gm.uid())).toList();
                    yield PrivateSessionFiles.create(content(legacy.text()), account, editors);
                }
                case PRIVATE -> throw new IllegalStateException("The lore book is already private.");
            };
        });
    }

    public void replaceAccess(Opened opened, PrivateAccount account, Map<PrivateAccount.Identity, Access> grants) {
        file.replace(DOCUMENT, opened.version(), () -> PrivateSessionFiles.replaceAccess(opened.version(), account, grants));
    }

    public void share(Opened opened, PrivateAccount account) {
        file.replace(DOCUMENT, opened.version(), () -> {
            var envelope = PrivateSessionFiles.verified(opened.version(), account);
            PrivateSessionFiles.requireOwner(envelope, account);
            return PrivateSessionFiles.read(opened.version(), account).text();
        });
    }

    private String legacyText(String raw, PrivateAccount account, List<PrivateAccount.Identity> writers) {
        return PrivateCharacterNotes.decrypt(raw, campaign, DOCUMENT, account, writers);
    }

    private static PrivateSessionFiles.Content content(String text) {
        return new PrivateSessionFiles.Content(text, List.of());
    }
}
