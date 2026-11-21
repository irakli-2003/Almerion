package com.dnd.security;

import com.dnd.cloud.LocalCloudStore;
import com.dnd.data.SessionRosterStore;
import com.dnd.data.StorylineService;
import com.dnd.data.StorylineDrafts;
import com.dnd.data.CharacterNotes;
import com.dnd.data.SessionSnapshot;
import com.dnd.model.session.TrackedCreature;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

public class PrivateSessionFilesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String PASSWORD = "test-private-password";
    private static LocalCloudStore cloud;
    private static PrivateAccount owner;
    private static PrivateAccount friend;
    private static PrivateAccount author;
    private static PrivateAccount.Setup ownerSetup;

    @BeforeClass public static void keys() {
        cloud = new LocalCloudStore();
        ownerSetup = PrivateAccount.prepare("co-gm", "co-gm@example.test", "Co-GM", PASSWORD);
        owner = PrivateAccount.finishSetup(cloud, ownerSetup, PASSWORD);
        friend = PrivateAccount.finishSetup(cloud, PrivateAccount.prepare("friend", "friend@example.test", "Friend", PASSWORD), PASSWORD);
        author = PrivateAccount.finishSetup(cloud, PrivateAccount.prepare("campaign-author", "author@example.test", "Author", PASSWORD), PASSWORD);
    }

    private static PrivateSessionFiles.Content content() {
        return new PrivateSessionFiles.Content("The secret villain is the king.",
            List.of(new TrackedCreature(TrackedCreature.Kind.NPC, "secret-npc", "Hidden Assassin", 7, 0, 12, null)));
    }

    @Test public void sharedCharacterNotesStayEncryptedAndOutsidePlayerSnapshots() throws Exception {
        Path root = temp.newFolder("notes").toPath();
        CharacterNotes notes = new CharacterNotes(root, "campaign", owner);
        var gms = List.of(owner.identity(), friend.identity());
        CharacterNotes.Loaded empty = notes.read("hero", gms);
        notes.save("hero", empty, "Secret patron", List.of(owner.identity(), friend.identity()));
        CharacterNotes.Loaded saved = notes.read("hero", gms);
        assertEquals("Secret patron", saved.text());
        assertFalse(saved.version().contains("Secret patron"));
        assertFalse(SessionSnapshot.capture(root, "Campaign").contains("Secret patron"));
        assertEquals("Secret patron", new CharacterNotes(root, "campaign", friend).read("hero", gms).text());
        assertThrows(SecurityException.class, () -> new CharacterNotes(root, "campaign", author).read("hero", gms));
        assertThrows(SecurityException.class, () -> new CharacterNotes(root, "other-campaign", friend).read("hero", gms));
        assertThrows(SecurityException.class, () -> notes.read("hero", List.of(friend.identity())));
        String forgery = PrivateCharacterNotes.encrypt("campaign", "hero", "Forged", author,
            List.of(author.identity(), owner.identity()));
        assertThrows(SecurityException.class, () ->
            PrivateCharacterNotes.decrypt(forgery, "campaign", "hero", owner, gms));
        String tampered = saved.version().replace("campaign", "different");
        assertThrows(SecurityException.class, () ->
            PrivateCharacterNotes.decrypt(tampered, "campaign", "hero", owner, gms));
        assertThrows(IllegalStateException.class, () -> notes.save("hero", empty, "Overwrite", List.of(owner.identity())));
        notes.save("hero", saved, "Updated", List.of(owner.identity()));
        assertThrows(SecurityException.class, () -> new CharacterNotes(root, "campaign", friend).read("hero", gms));
        assertEquals("Secret patron", PrivateCharacterNotes.decrypt(saved.version(), "campaign", "hero", friend, gms));
    }

    @Test public void offlineCacheUnlocksWithPasswordAndNeverAllowsCloudWrites() throws Exception {
        Path cache = temp.newFolder("offline-private").toPath();
        OfflinePrivateAccounts.save(cache, ownerSetup.vault(), List.of(owner.identity(), friend.identity()));
        String stored = Files.readString(cache.resolve(owner.uid() + ".json"));
        assertFalse(stored.contains(PASSWORD));
        PrivateAccount offline = OfflinePrivateAccounts.unlock(cache, owner.uid(), owner.identity().email(), PASSWORD);
        String encrypted = PrivateCharacterNotes.encrypt("campaign", "hero", "Offline secret", offline,
            List.of(offline.identity(), offline.identity(friend.uid())));
        assertEquals("Offline secret", PrivateCharacterNotes.decrypt(encrypted, "campaign", "hero", friend,
            List.of(owner.identity(), friend.identity())));
        assertThrows(com.dnd.cloud.CloudStore.CloudException.class,
            () -> offline.requestAccess(friend.uid(), "file", "campaign", "file"));
        assertThrows(SessionCipher.CipherException.class, () ->
            OfflinePrivateAccounts.unlock(cache, owner.uid(), owner.identity().email(), "wrong-password"));
        assertThrows(SecurityException.class, () ->
            OfflinePrivateAccounts.unlock(cache, owner.uid(), "other@example.test", PASSWORD));
        offline.close();
    }

    @Test public void loreBookIsEncryptedSeparatelyAndNeverEntersPlayerSnapshots() throws Exception {
        Path root = temp.newFolder("lore").toPath();
        var gms = List.of(owner.identity(), friend.identity());
        CharacterNotes store = CharacterNotes.loreBook(root, "campaign", owner);
        CharacterNotes.Loaded empty = store.read("@gm-lore-book", gms);
        store.save("@gm-lore-book", empty, "{\"title\":\"Secret lore\"}", gms);
        var saved = new CharacterNotes(root, "campaign", friend);
        assertNull(saved.read("@gm-lore-book", gms).version());
        assertEquals("{\"title\":\"Secret lore\"}",
            CharacterNotes.loreBook(root, "campaign", friend).read("@gm-lore-book", gms).text());
        assertFalse(store.read("@gm-lore-book", gms).version().contains("Secret lore"));
        assertFalse(SessionSnapshot.capture(root, "Campaign").contains("Secret lore"));
        assertThrows(SecurityException.class,
            () -> CharacterNotes.loreBook(root, "campaign", author).read("@gm-lore-book", gms));
    }

    @Test public void legacyLoreStaysPrivateAndCanGrantSessionStyleAccessToNewGms() throws Exception {
        var root = temp.newFolder("legacy-lore").toPath();
        var lore = new com.dnd.data.LoreBookStore(root, "campaign");
        var legacy = CharacterNotes.loreBook(root, "campaign", owner);
        var id = com.dnd.data.LoreBookStore.DOCUMENT;
        var gms = List.of(owner.identity(), friend.identity());
        String draft = "{\"title\":\"Secret lore\",\"sections\":[]}";
        legacy.save(id, legacy.read(id, gms), draft, gms);
        assertThrows(SecurityException.class, () -> lore.open(author, gms));
        var opened = lore.open(owner, gms);
        assertFalse(opened.editable());
        lore.protect(opened, owner, gms);
        assertEquals(PrivateSessionFiles.Access.WRITE, lore.open(friend, List.of()).access());
        assertThrows(SecurityException.class, () -> lore.open(author, List.of()));
        var protectedBook = lore.open(owner, List.of());
        lore.replaceAccess(protectedBook, owner, java.util.Map.of(owner.identity(), PrivateSessionFiles.Access.OWNER,
            friend.identity(), PrivateSessionFiles.Access.OWNER, author.identity(), PrivateSessionFiles.Access.READ));
        var readOnly = lore.open(author, List.of());
        assertEquals("Secret lore", readOnly.book().title);
        assertFalse(readOnly.editable());
        assertThrows(SecurityException.class, () -> lore.save(readOnly, draft, author));
        assertThrows(SecurityException.class, () -> lore.share(readOnly, author));
        assertThrows(IllegalStateException.class, () -> lore.save(protectedBook, draft, owner));
        lore.replaceAccess(lore.open(friend, List.of()), friend, java.util.Map.of(friend.identity(), PrivateSessionFiles.Access.OWNER,
            author.identity(), PrivateSessionFiles.Access.WRITE));
        lore.save(lore.open(author, List.of()), "{\"title\":\"Edited lore\",\"sections\":[]}", author);
        assertThrows(SecurityException.class, () -> lore.open(owner, List.of()));
        lore.share(lore.open(friend, List.of()), friend);
        assertEquals("Edited lore", lore.open(null, List.of()).book().title);
        assertFalse(SessionSnapshot.capture(root, "Campaign").contains("Edited lore"));
    }

    @Test public void campaignAuthorCannotReadCoGmFileEvenWithSyncedBytes() {
        String file = PrivateSessionFiles.create(content(), owner);
        assertFalse(file.contains("secret villain"));
        assertFalse(file.contains("Hidden Assassin"));
        assertEquals("The secret villain is the king.", PrivateSessionFiles.read(file, owner).text());
        assertThrows(SecurityException.class, () -> PrivateSessionFiles.read(file, author));
        assertThrows(SecurityException.class, () -> PrivateSessionFiles.write(file, content(), author));
        assertThrows(SecurityException.class, () -> PrivateSessionFiles.grant(file, author, List.of(author.identity())));
    }

    @Test public void grantAllowsEditingButNotPermissionChangesAndRevocationProtectsFutureWrites() {
        String original = PrivateSessionFiles.create(content(), owner);
        String granted = PrivateSessionFiles.grant(original, owner, List.of(friend.identity()));
        assertEquals("Hidden Assassin", PrivateSessionFiles.read(granted, friend).roster().getFirst().getName());
        String edited = PrivateSessionFiles.write(granted, new PrivateSessionFiles.Content("Updated secret", List.of()), friend);
        assertEquals("Updated secret", PrivateSessionFiles.read(edited, owner).text());
        assertThrows(SecurityException.class, () -> PrivateSessionFiles.grant(edited, friend, List.of(author.identity())));
        String revoked = PrivateSessionFiles.grant(edited, owner, List.of());
        assertThrows(SecurityException.class, () -> PrivateSessionFiles.read(revoked, friend));
        assertEquals("Updated secret", PrivateSessionFiles.read(revoked, owner).text());
        assertEquals("Updated secret", PrivateSessionFiles.read(edited, friend).text()); // Old copies cannot be revoked.
    }

    @Test public void forgedGrantAndModifiedCiphertextFailClosed() {
        String original = PrivateSessionFiles.create(content(), owner);
        var envelope = PrivateSessionFiles.parse(original);
        Map<String, String> grants = new TreeMap<>(envelope.header().grants());
        grants.put(author.uid(), grants.get(owner.uid()));
        var forged = new PrivateSessionFiles.Envelope(new PrivateSessionFiles.Header(envelope.header().fileId(),
            owner.uid(), grants), envelope.ownerSignature(), envelope.writerUid(), envelope.ciphertext(), envelope.signature());
        assertThrows(SecurityException.class, () -> PrivateSessionFiles.read(
            PrivateSessionFiles.PREFIX + PrivateAccount.json(forged), author));
        String cipher = envelope.ciphertext();
        var modified = new PrivateSessionFiles.Envelope(envelope.header(), envelope.ownerSignature(), envelope.writerUid(),
            (cipher.charAt(0) == 'A' ? "B" : "A") + cipher.substring(1), envelope.signature());
        assertThrows(SecurityException.class, () -> PrivateSessionFiles.read(
            PrivateSessionFiles.PREFIX + PrivateAccount.json(modified), owner));
    }

    @Test public void swappedFileContextAndUnsupportedVersionAreRejected() {
        var original = PrivateSessionFiles.parse(PrivateSessionFiles.create(content(), owner));
        var another = PrivateSessionFiles.parse(PrivateSessionFiles.create(content(), owner));
        var swapped = new PrivateSessionFiles.Envelope(another.header(), another.ownerSignature(),
            original.writerUid(), original.ciphertext(), original.signature());
        assertThrows(SecurityException.class, () -> PrivateSessionFiles.read(
            PrivateSessionFiles.PREFIX + PrivateAccount.json(swapped), owner));
        assertThrows(SecurityException.class, () -> PrivateSessionFiles.parse("ALMERION-PRIVATE-SESSION/2\n{}"));
    }

    @Test public void secondDeviceAndPasswordResetPreserveOriginalFileKeys() {
        LocalCloudStore accounts = new LocalCloudStore();
        accounts.put("accounts/" + owner.uid(), ownerSetup.vault());
        String file = PrivateSessionFiles.create(content(), owner);
        PrivateAccount device = PrivateAccount.unlock(accounts, PrivateAccount.load(accounts, owner.uid()), PASSWORD);
        assertEquals(content().text(), PrivateSessionFiles.read(file, device).text());
        device.close();
        assertThrows(SecurityException.class, () -> PrivateSessionFiles.read(file, device));
        assertThrows(SessionCipher.CipherException.class,
            () -> PrivateAccount.unlock(accounts, ownerSetup.vault(), "new-password"));
        assertThrows(IllegalArgumentException.class, () -> PrivateAccount.recover(accounts, ownerSetup.vault(), "wrong", "new-password"));
        PrivateAccount recovered = PrivateAccount.recover(accounts, ownerSetup.vault(), ownerSetup.recoveryKey(), "new-password");
        assertEquals(owner.identity(), recovered.identity());
        assertEquals(content().text(), PrivateSessionFiles.read(file, recovered).text());
        PrivateAccount afterReset = PrivateAccount.unlock(accounts, PrivateAccount.load(accounts, owner.uid()), "new-password");
        assertEquals(content().text(), PrivateSessionFiles.read(file, afterReset).text());
        assertFalse(accounts.get("accounts/" + owner.uid()).toString().contains(ownerSetup.recoveryKey()));
        assertThrows(IllegalStateException.class, () -> PrivateAccount.finishSetup(accounts, ownerSetup, PASSWORD));
    }

    @Test public void newUiFilesAreEncryptedAndNoAccountCannotCreateOrReadThem() throws Exception {
        StorylineService service = service(owner);
        Path file = service.createFile(service.getRoot(), "session.txt");
        service.writeText(file, content().text());
        service.writeRoster(file, content().roster());
        assertTrue(service.isPrivate(file));
        assertEquals(owner.uid(), service.permissions(file).ownerUid());
        assertFalse(Files.readString(file).contains("secret villain"));
        assertFalse(Files.exists(SessionRosterStore.rosterFile(file)));
        assertThrows(SecurityException.class, () -> SessionRosterStore.load(file));
        assertThrows(SecurityException.class, () -> SessionRosterStore.save(file, content().roster()));
        StorylineService locked = service(null);
        assertThrows(SecurityException.class, () -> locked.createFile(locked.getRoot(), "locked.txt"));
        assertFalse(Files.exists(locked.getRoot().resolve("locked.txt")));
        assertThrows(SecurityException.class, () -> locked.readText(file));
        StorylineService legacyApi = new StorylineService(temp.getRoot().toPath());
        assertThrows(SecurityException.class, () -> legacyApi.readText(file));
        assertThrows(SecurityException.class, () -> legacyApi.writeText(file, "Overwrite"));
    }

    @Test public void oldFilesStaySharedUntilExplicitProtectionIncludesRoster() throws Exception {
        StorylineService legacy = new StorylineService(temp.getRoot().toPath());
        legacy.ensureRoot();
        Path file = legacy.createFile(legacy.getRoot(), "old.txt");
        legacy.writeText(file, content().text());
        SessionRosterStore.save(file, content().roster());
        StorylineService secured = service(owner);
        assertFalse(secured.isPrivate(file));
        assertEquals(content().text(), service(author).readText(file));
        secured.protect(file);
        assertEquals(content().text(), secured.readText(file));
        assertEquals("Hidden Assassin", secured.readRoster(file).getFirst().getName());
        assertFalse(Files.exists(SessionRosterStore.rosterFile(file)));
        assertThrows(SecurityException.class, () -> service(author).readText(file));
    }

    @Test public void readOnlyGrantAllowsReadingButBlocksTextAndRosterEdits() throws Exception {
        StorylineService secured = service(owner);
        Path file = secured.createFile(secured.getRoot(), "read-only.txt");
        secured.writeText(file, "Hidden notes");
        secured.setAccess(file, List.of(friend.identity()));
        assertEquals(PrivateSessionFiles.Access.WRITE, secured.permissions(file).access(friend.uid()));
        secured.setAccess(file, Map.of(friend.identity(), PrivateSessionFiles.Access.READ), secured.permissions(file));
        StorylineService reader = service(friend);
        assertEquals("Hidden notes", reader.readText(file));
        String before = Files.readString(file);
        assertThrows(SecurityException.class, () -> reader.writeText(file, "Not allowed"));
        assertThrows(SecurityException.class, () -> reader.writeRoster(file, content().roster()));
        assertThrows(SecurityException.class, () -> reader.setAccess(file, List.of(friend.identity())));
        assertEquals(before, Files.readString(file));
        assertEquals(PrivateSessionFiles.Access.OWNER, secured.permissions(file).access(owner.uid()));

        secured.setAccess(file, Map.of(friend.identity(), PrivateSessionFiles.Access.WRITE), secured.permissions(file));
        service(friend).writeText(file, "Allowed edit");
        assertEquals("Allowed edit", secured.readText(file));
    }

    @Test public void coOwnersCanManageAccessAndTheCreatorCanHandOffOwnership() throws Exception {
        StorylineService secured = service(owner);
        Path file = secured.createFile(secured.getRoot(), "handoff.txt");
        secured.writeText(file, "Secret encounter");
        var coOwner = service(friend);
        var writer = service(author);
        var roles = PrivateSessionFiles.Access.OWNER;
        secured.replaceAccess(file, Map.of(owner.identity(), roles, friend.identity(), roles,
            author.identity(), PrivateSessionFiles.Access.WRITE), secured.permissions(file));
        assertEquals(java.util.Set.of(owner.uid(), friend.uid()), coOwner.verifiedPermissions(file).owners());
        writer.writeText(file, "Updated encounter");
        assertThrows(SecurityException.class, () -> writer.replaceAccess(file, Map.of(author.identity(), roles),
            writer.permissions(file)));
        coOwner.replaceAccess(file, Map.of(owner.identity(), roles, friend.identity(), roles,
            author.identity(), PrivateSessionFiles.Access.READ), coOwner.permissions(file));
        assertEquals("Updated encounter", writer.readText(file));
        assertThrows(SecurityException.class, () -> service(author).writeText(file, "Not allowed"));
        assertThrows(IllegalArgumentException.class, () -> secured.replaceAccess(file, Map.of(), secured.permissions(file)));
        secured.replaceAccess(file, Map.of(friend.identity(), roles), secured.permissions(file));
        assertEquals("Updated encounter", coOwner.readText(file));
        assertThrows(SecurityException.class, () -> secured.readText(file));
        assertThrows(SecurityException.class, () -> secured.delete(file));
        coOwner.writeText(file, "New owner's notes");
        coOwner.replaceAccess(file, Map.of(friend.identity(), roles, author.identity(), PrivateSessionFiles.Access.READ),
            coOwner.permissions(file));
        assertEquals("New owner's notes", writer.readText(file));
    }

    @Test public void nonOwnerCannotDeleteMoveOrReorderFileOrContainingFolder() throws Exception {
        StorylineService secured = service(owner);
        Path folder = secured.createFolder(secured.getRoot(), "Arc");
        Path file = secured.createFile(folder, "private.txt");
        Path destination = secured.createFolder(secured.getRoot(), "Other");
        secured.setAccess(file, List.of(friend.identity()));
        StorylineService recipient = service(friend);
        assertThrows(SecurityException.class, () -> recipient.delete(file));
        assertThrows(SecurityException.class, () -> recipient.delete(folder));
        assertThrows(SecurityException.class, () -> recipient.move(folder, destination));
        assertThrows(SecurityException.class, () -> recipient.reorder(file, 0));
        assertTrue(Files.exists(file));
        Path moved = secured.move(folder, destination).resolve(file.getFileName());
        assertEquals("", secured.readText(moved));
        secured.delete(moved);
        assertFalse(Files.exists(moved));
    }

    @Test public void editorRejectsSavingAfterRevocationAndStaleAccessDialogCannotOverwriteGrant() throws Exception {
        StorylineService secured = service(owner);
        Path file = secured.createFile(secured.getRoot(), "session.txt");
        secured.setAccess(file, List.of(friend.identity()));
        var oldHeader = secured.permissions(file);
        StorylineService editor = service(friend).forEditor();
        editor.readText(file);
        secured.setAccess(file, List.of());
        String revoked = Files.readString(file);
        assertThrows(IllegalStateException.class, () -> editor.writeText(file, "stale edit"));
        assertThrows(IllegalStateException.class, () -> editor.writeRoster(file, content().roster()));
        assertThrows(IllegalStateException.class, () -> secured.setAccess(file, List.of(author.identity()), oldHeader));
        assertEquals(revoked, Files.readString(file));
    }

    @Test public void pathTraversalCannotEscapePrivateStoryline() throws Exception {
        StorylineService secured = service(owner);
        assertThrows(IllegalArgumentException.class, () -> secured.createFolder(secured.getRoot(), ".."));
        assertThrows(IllegalArgumentException.class, () -> secured.createFile(temp.getRoot().toPath(), "outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> secured.delete(secured.getRoot()));
        Path outside = temp.newFile("outside.txt").toPath();
        assertThrows(IllegalArgumentException.class, () -> secured.readText(outside));
        assertThrows(IllegalArgumentException.class, () -> secured.move(outside, secured.getRoot()));
        assertTrue(Files.exists(outside));
    }

    @Test public void privateEditorNeverFallsBackToPlaintextAfterExternalFileReplacement() throws Exception {
        StorylineService service = service(owner);
        Path file = service.createFile(service.getRoot(), "session.txt");
        service.writeText(file, content().text());
        StorylineService editor = service.forEditor();
        editor.readText(file);
        Files.writeString(file, "External shared replacement");
        assertThrows(IllegalStateException.class, () -> editor.writeText(file, content().text()));
        assertThrows(IllegalStateException.class, () -> editor.writeRoster(file, content().roster()));
        assertEquals("External shared replacement", Files.readString(file));
        assertFalse(Files.exists(SessionRosterStore.rosterFile(file)));
    }

    @Test public void requestsDeduplicateAndOwnerResponsesReachRequester() {
        String id = java.util.UUID.randomUUID().toString();
        friend.requestAccess(owner.uid(), id, "campaign-id", "Private.txt");
        friend.requestAccess(owner.uid(), id, "campaign-id", "Private.txt");
        var incoming = owner.requests().stream().filter(request -> id.equals(request.fileId())).toList();
        assertEquals(1, incoming.size());
        assertEquals(friend.uid(), incoming.getFirst().requesterUid());
        owner.answer(incoming.getFirst(), true);
        var response = friend.outgoing().keySet().stream().filter(request -> id.equals(request.fileId())).findFirst().orElseThrow();
        assertEquals("approved", response.status());
        assertEquals(friend.uid(), response.requesterUid());
        friend.requestAccess(owner.uid(), id, "campaign-id", "Private.txt");
        assertEquals("pending", friend.outgoing().keySet().stream().filter(request -> id.equals(request.fileId()))
            .findFirst().orElseThrow().status());
    }

    @Test public void campaignExportImportCarriesOnlyCiphertextAndPreservesRecipientAccess() throws Exception {
        Path source = temp.newFolder("source").toPath();
        com.dnd.cloud.CampaignMeta.ensureId(source);
        StorylineService service = new StorylineService(source, () -> owner);
        service.ensureRoot();
        Path file = service.createFile(service.getRoot(), "Private.txt");
        service.writeText(file, content().text());
        service.writeRoster(file, content().roster());
        service.setAccess(file, List.of(friend.identity()));
        String ciphertext = Files.readString(file);
        Path archive = temp.getRoot().toPath().resolve("campaign.zip");
        com.dnd.data.CampaignArchive.exportCampaign(source, archive);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            String stored = new String(zip.getInputStream(zip.getEntry("campaign/storyline/Private.txt")).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(ciphertext, stored);
            assertFalse(stored.contains(content().text()));
            assertFalse(stored.contains("Hidden Assassin"));
            assertNull(zip.getEntry("campaign/storyline/.Private.txt.roster.json"));
        }
        var storage = new com.dnd.cli.storage.CampaignStorage(temp.newFolder("second-device").toPath());
        storage.ensureInitialized();
        Path imported = com.dnd.data.CampaignArchive.importCampaign(storage, archive).campaign();
        Path syncedFile = imported.resolve("storyline").resolve("Private.txt");
        assertEquals(ciphertext, Files.readString(syncedFile));
        StorylineService reader = new StorylineService(imported, () -> friend);
        assertEquals(content().text(), reader.readText(syncedFile));
        assertEquals("Hidden Assassin", reader.readRoster(syncedFile).getFirst().getName());
        assertThrows(SecurityException.class, () -> new StorylineService(imported, () -> author).readText(syncedFile));
    }

    private StorylineService service(PrivateAccount account) {
        StorylineService service = new StorylineService(temp.getRoot().toPath(), () -> account);
        service.ensureRoot();
        return service;
    }

    @Test public void recoveryDraftIsPersistentAndEncryptedOnlyForItsEditingAccount() throws Exception {
        StorylineService secured = draftService(owner);
        Path file = secured.createFile(secured.getRoot(), "session.txt");
        secured.writeText(file, "Saved original.");
        secured.setAccess(file, List.of(friend.identity()));
        StorylineService editor = draftService(friend).forEditor();
        editor.readText(file);
        Path deviceRoot = temp.newFolder("device").toPath().resolve("drafts");
        var drafts = new StorylineDrafts(deviceRoot, editor, file, friend);
        String original = Files.readString(file);
        String revision = editor.editorRevision(file);
        drafts.save("editor-one", revision, "My unsaved secret.");
        drafts.save("editor-two", revision, "");
        StorylineService reopened = editor.forEditor();
        reopened.readText(file);
        var copy = new StorylineDrafts(deviceRoot, reopened, file, friend);
        assertEquals(2, copy.list().size());
        assertTrue(copy.list().stream().anyMatch(d -> d.text().equals("My unsaved secret.") && d.baseRevision().equals(revision)));
        assertEquals(original, Files.readString(file));
        try (var files = Files.walk(deviceRoot)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                assertTrue(path.getFileName().toString().endsWith(".draft"));
                String raw = Files.readString(path);
                assertFalse(raw.contains("My unsaved secret."));
                assertFalse(raw.contains(file.toString()));
                assertEquals(List.of(friend.uid()), List.copyOf(PrivateSessionFiles.parse(raw).header().grants().keySet()));
                assertThrows(SecurityException.class, () -> PrivateSessionFiles.read(raw, owner));
            }
        }
        copy.discard("editor-one");
        assertEquals("", copy.list().getFirst().text());
        copy.save("editor-two", revision, "Updated second window.");
        assertEquals(1, copy.list().size());
        assertEquals("Updated second window.", copy.list().getFirst().text());
        assertThrows(IllegalArgumentException.class, () -> copy.discard("../other-account"));
        assertThrows(IllegalArgumentException.class,
            () -> new StorylineDrafts(secured.getRoot().resolve("drafts"), reopened, file, friend));
        var secondDevice = new StorylineDrafts(temp.newFolder("other-device").toPath(), reopened, file, friend);
        assertTrue(secondDevice.list().isEmpty());
        secured.readText(file);
        assertTrue(new StorylineDrafts(deviceRoot, secured, file, owner).list().isEmpty());
    }

    @Test public void recoveryChecksDoNotAdvancePlaintextEditorsBaseline() throws Exception {
        StorylineService legacy = new StorylineService(temp.getRoot().toPath().resolve("campaign"));
        legacy.ensureRoot();
        Path file = legacy.createFile(legacy.getRoot(), "shared.txt");
        legacy.writeText(file, "Original shared text.");
        StorylineService editor = legacy.forEditor();
        editor.readText(file);
        String revision = editor.editorRevision(file);
        Path deviceRoot = temp.newFolder("device").toPath().resolve("drafts");
        var drafts = new StorylineDrafts(deviceRoot, editor, file, owner);
        Files.writeString(file, "Changed on another device.");
        drafts.save("editor", revision, "My unfinished edit.");
        assertEquals(revision, editor.editorRevision(file));
        assertEquals(revision, drafts.list().getFirst().baseRevision());
        assertThrows(IllegalStateException.class, () -> editor.writeText(file, "My unfinished edit."));
        assertEquals("Changed on another device.", Files.readString(file));
    }

    @Test public void revokedPrivateGrantBlocksRecoveryAndFurtherDraftWritesWithoutDeletingExistingCopy() throws Exception {
        StorylineService secured = draftService(owner);
        Path file = secured.createFile(secured.getRoot(), "session.txt");
        secured.setAccess(file, List.of(friend.identity()));
        StorylineService editor = draftService(friend).forEditor();
        editor.readText(file);
        Path deviceRoot = temp.newFolder("device").toPath().resolve("drafts");
        var drafts = new StorylineDrafts(deviceRoot, editor, file, friend);
        String revision = editor.editorRevision(file);
        drafts.save("editor", revision, "Unsaved private text.");
        secured.setAccess(file, List.of());
        assertThrows(SecurityException.class, drafts::list);
        assertThrows(SecurityException.class, () -> drafts.save("editor", revision, "Later edit."));
        try (var files = Files.walk(deviceRoot)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".draft")).count());
        }
        secured.setAccess(file, List.of(friend.identity()));
        assertEquals("Unsaved private text.", drafts.list().getFirst().text());
    }

    @Test public void replacementSourceAndCorruptedDraftAreRejected() throws Exception {
        StorylineService secured = draftService(owner);
        Path file = secured.createFile(secured.getRoot(), "session.txt");
        StorylineService editor = secured.forEditor();
        editor.readText(file);
        Path deviceRoot = temp.newFolder("device").toPath().resolve("drafts");
        var drafts = new StorylineDrafts(deviceRoot, editor, file, owner);
        drafts.save("editor", editor.editorRevision(file), "Unfinished secret.");
        String original = Files.readString(file);
        Files.writeString(file, "Plaintext replacement.");
        assertThrows(SecurityException.class, drafts::list);
        assertThrows(SecurityException.class, () -> drafts.save("editor", editor.editorRevision(file), "Should not save."));
        Files.writeString(file, PrivateSessionFiles.create(content(), owner));
        assertThrows(SecurityException.class, drafts::list);
        Files.writeString(file, original);
        Path draft;
        try (var files = Files.walk(deviceRoot)) {
            draft = files.filter(path -> path.getFileName().toString().endsWith(".draft")).findFirst().orElseThrow();
        }
        Files.writeString(draft, "Damaged draft");
        assertThrows(SecurityException.class, drafts::list);
        assertEquals("Damaged draft", Files.readString(draft));
    }

    private StorylineService draftService(PrivateAccount account) {
        StorylineService service = new StorylineService(temp.getRoot().toPath().resolve("campaign"), () -> account);
        service.ensureRoot();
        return service;
    }

    @Test public void restorePointPreservesCurrentGrantsAndRejectsRevokedReader() throws Exception {
        StorylineService service = draftService(owner);
        Path file = service.createFile(service.getRoot(), "session.txt");
        service.writeText(file, "Older secret");
        service.writeRoster(file, content().roster());
        service.setAccess(file, List.of(friend.identity()));
        var points = new com.dnd.data.CampaignRestorePoints(temp.newFolder("restore-device").toPath(), service.getRoot().getParent());
        var old = points.capture("Before changes");
        var editor = service.forEditor();
        editor.readText(file);
        editor.writeText(file, "Latest secret");
        service.setAccess(file, List.of());
        String current = Files.readString(file);
        var header = service.permissions(file);
        var plan = points.plan(old.id());
        assertThrows(SecurityException.class, () -> points.preview(plan, "storyline/session.txt", friend));
        assertThrows(java.io.IOException.class, () -> points.restore(plan, List.of("storyline/session.txt"), friend));
        assertEquals(current, Files.readString(file));
        points.restore(plan, List.of("storyline/session.txt"), owner);
        assertEquals("Older secret", service.forEditor().readText(file));
        assertEquals(content().roster().getFirst().getName(), service.forEditor().readRoster(file).getFirst().getName());
        assertEquals(header.grants(), service.permissions(file).grants());
        assertEquals(header.fileId(), service.permissions(file).fileId());
        assertThrows(SecurityException.class, () -> PrivateSessionFiles.read(Files.readString(file), friend));
        assertFalse(Files.exists(SessionRosterStore.rosterFile(file)));
    }

    @Test public void restoringOldSharedTextIntoPrivateFileNeverDowngradesEncryption() throws Exception {
        StorylineService service = draftService(owner);
        Path file = service.getRoot().resolve("legacy.txt");
        Files.writeString(file, "Older shared text");
        SessionRosterStore.save(file, content().roster());
        var points = new com.dnd.data.CampaignRestorePoints(temp.newFolder("restore-device").toPath(), service.getRoot().getParent());
        var old = points.capture("Before protection");
        service.protect(file);
        service.writeText(file, "New private text");
        var header = service.permissions(file);
        var plan = points.plan(old.id());
        points.restore(plan, List.of("storyline/legacy.txt"), owner);
        assertTrue(service.isPrivate(file));
        assertEquals(header, service.permissions(file));
        assertEquals("Older shared text", service.forEditor().readText(file));
        assertEquals(content().roster().getFirst().getName(), service.forEditor().readRoster(file).getFirst().getName());
        assertFalse(Files.exists(SessionRosterStore.rosterFile(file)));
    }
}
