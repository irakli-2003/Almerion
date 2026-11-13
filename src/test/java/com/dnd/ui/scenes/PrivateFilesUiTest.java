package com.dnd.ui.scenes;

import com.dnd.auth.User;
import com.dnd.cli.core.CampaignContext;
import com.dnd.cli.core.CliSession;
import com.dnd.cli.storage.CampaignStorage;
import com.dnd.cloud.CampaignMeta;
import com.dnd.cloud.LocalCloudStore;
import com.dnd.data.StorylineService;
import com.dnd.security.PrivateAccount;
import com.dnd.ui.UiSession;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.*;

public class PrivateFilesUiTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Runnable start = () -> { Platform.setImplicitExit(false); ready.countDown(); };
        try { Platform.startup(start); } catch (IllegalStateException running) { Platform.runLater(start); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test public void storyPanelRequestsAndOwnerInboxDeniesWithoutOpeningPrivateContent() throws Exception {
        LocalCloudStore cloud = new LocalCloudStore();
        PrivateAccount owner = PrivateAccount.finishSetup(cloud,
            PrivateAccount.prepare("owner", "owner@example.test", "Owner", "password"), "password");
        PrivateAccount requester = PrivateAccount.finishSetup(cloud,
            PrivateAccount.prepare("requester", "requester@example.test", "Requester", "password"), "password");
        Path campaign = temp.newFolder("campaign").toPath();
        CampaignMeta.ensureId(campaign);
        StorylineService service = new StorylineService(campaign, () -> owner);
        service.ensureRoot();
        Path privateFile = service.createFile(service.getRoot(), "Private.txt");
        service.writeText(privateFile, "Do not show the hidden villain.");
        String encrypted = Files.readString(privateFile);
        onFx(() -> {
            Platform.setImplicitExit(false);
            Stage stage = new Stage();
            UiSession session = session(stage, campaign);
            AtomicReference<String> info = new AtomicReference<>();
            StorylineScene scene = new StorylineScene(session) {
                @Override protected <T> void background(Supplier<T> work, Consumer<T> success, Consumer<Throwable> failure) {
                    try { success.accept(work.get()); } catch (Throwable error) { throw new AssertionError(error); }
                }
                @Override protected void showInfo(String title, String message) { info.set(title); }
                @Override protected void showError(String title, Throwable error) { throw new AssertionError(error); }
            };
            try {
                user(session, requester);
                stage.setScene(scene.build());
                stage.show();
                Parent root = stage.getScene().getRoot();
                root.applyCss();
                root.layout();
                assertNotNull(button(root, "Unlock private files"));
                assertNotNull(button(root, "Lock private files"));
                Button access = button(root, "Session-file access");
                assertTrue(access.isDisabled());
                TreeView<?> tree = (TreeView<?>) root.lookup(".tree-view");
                selectFirstChild(tree);
                assertFalse(access.isDisabled());
                access.fire();
                assertEquals("Access requested", info.get());
                assertEquals(1, owner.requests().size());
                assertEquals(requester.uid(), owner.requests().getFirst().requesterUid());
                assertEquals(encrypted, Files.readString(privateFile));

                user(session, owner);
                button(root, "Notifications").fire();
                Stage inbox = Window.getWindows().stream().filter(window -> window instanceof Stage s
                    && "Notifications".equals(s.getTitle())).map(window -> (Stage) window).findFirst().orElseThrow();
                Parent inboxRoot = inbox.getScene().getRoot();
                inboxRoot.applyCss();
                inboxRoot.layout();
                assertNotNull(button(inboxRoot, "Review and approve"));
                button(inboxRoot, "Deny").fire();
                assertEquals("denied", owner.requests().getFirst().status());
                assertNull(button(inboxRoot, "Review and approve"));
                assertEquals(encrypted, Files.readString(privateFile));
                assertFalse(inboxRoot.lookupAll(".label").stream().filter(node -> node instanceof javafx.scene.control.Label)
                    .map(node -> ((javafx.scene.control.Label) node).getText())
                    .anyMatch(text -> text != null && text.contains("hidden villain")));
                inbox.close();
                button(root, "Lock private files").fire();
                assertNull(session.getPrivateAccount());
                assertFalse(owner.isUnlocked());
            } finally {
                for (Window window : java.util.List.copyOf(Window.getWindows())) window.hide();
                session.lockPrivateAccount();
                stage.close();
            }
        });
    }

    @Test public void oldAccountMustExplicitlyChooseProviderEnrollmentAndCanCancel() throws Exception {
        onFx(() -> {
            BaseScene owner = new CharacterCreationWizard(null, new com.dnd.data.CampaignRepositories(temp.getRoot().toPath()));
            var dialog = PrivateFilesUi.unlockDialog(owner);
            var pane = dialog.getDialogPane();
            var verify = pane.getButtonTypes().stream().filter(type -> "Set up / verify email".equals(type.getText()))
                .findFirst().orElseThrow();
            assertTrue(pane.lookupButton(verify).isDisabled());
            assertTrue(pane.lookupButton(javafx.scene.control.ButtonType.OK).isDisabled());
            ((javafx.scene.control.PasswordField) pane.getContent()).setText("existing-account-password");
            assertFalse(pane.lookupButton(verify).isDisabled());
            var enrollment = dialog.getResultConverter().call(verify);
            assertTrue(enrollment.verifyEmail());
            assertEquals("existing-account-password", enrollment.password());
            assertFalse(dialog.getResultConverter().call(javafx.scene.control.ButtonType.OK).verifyEmail());
            assertNull(dialog.getResultConverter().call(javafx.scene.control.ButtonType.CANCEL));
            dialog.close();
        });
    }

    @Test public void editorAutomaticallyEncryptsTypingAndSaveCloseRemovesOnlyItsDraft() throws Exception {
        String oldHome = System.getProperty("dnd.home");
        System.setProperty("dnd.home", temp.getRoot().getAbsolutePath());
        LocalCloudStore cloud = new LocalCloudStore();
        PrivateAccount account = PrivateAccount.finishSetup(cloud,
            PrivateAccount.prepare("editor", "editor@example.test", "Editor", "password"), "password");
        Path campaign = temp.newFolder("campaign").toPath();
        StorylineService service = new StorylineService(campaign, () -> account);
        service.ensureRoot();
        Path file = service.createFile(service.getRoot(), "session.txt");
        service.writeText(file, "Saved original.");
        String original = Files.readString(file);
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            onFx(() -> {
                Platform.setImplicitExit(false);
                Stage stage = new Stage();
                UiSession session = session(stage, campaign);
                user(session, account);
                AtomicReference<StorylineEditorWindow> editorRef = new AtomicReference<>();
                StorylineScene owner = new StorylineScene(session) {
                    @Override protected <T> void background(Supplier<T> work, Consumer<T> success, Consumer<Throwable> failure) {
                        try {
                            success.accept(work.get());
                            StorylineEditorWindow editor = editorRef.get();
                            var drafts = (com.dnd.data.StorylineDrafts) field(editor, "drafts");
                            assertEquals("Unfinished secret.", drafts.list().getFirst().text());
                            assertEquals(original, Files.readString(file));
                            Button saveClose = button(((Stage) field(editor, "editorStage")).getScene().getRoot(), "Save & Close");
                            assertNotNull(saveClose);
                            saveClose.fire();
                            assertTrue(drafts.list().isEmpty());
                            assertEquals("Unfinished secret.", service.forEditor().readText(file));
                        } catch (Throwable problem) {
                            error.set(problem);
                            for (Window window : java.util.List.copyOf(Window.getWindows())) window.hide();
                        }
                    }
                    @Override protected void showError(String title, Throwable problem) { throw new AssertionError(title, problem); }
                };
                StorylineEditorWindow editor = new StorylineEditorWindow(owner, service.forEditor(),
                    new com.dnd.data.CampaignRepositories(campaign), file);
                editorRef.set(editor);
                javafx.animation.PauseTransition watchdog = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(12));
                watchdog.setOnFinished(e -> {
                    error.set(new AssertionError("Autosave did not complete."));
                    for (Window window : java.util.List.copyOf(Window.getWindows())) window.hide();
                });
                watchdog.play();
                Platform.runLater(() -> {
                    try {
                        ((org.fxmisc.richtext.CodeArea) field(editor, "area")).replaceText("Unfinished secret.");
                    } catch (Exception problem) {
                        error.set(problem);
                    }
                });
                try {
                    editor.show();
                } finally {
                    watchdog.stop();
                    session.lockPrivateAccount();
                    stage.close();
                }
            });
        } finally {
            if (oldHome == null) System.clearProperty("dnd.home"); else System.setProperty("dnd.home", oldHome);
            account.close();
        }
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @Test public void queuedAutosaveCannotRecreateDraftAfterSaveOrDiscard() throws Exception {
        LocalCloudStore cloud = new LocalCloudStore();
        PrivateAccount account = PrivateAccount.finishSetup(cloud,
            PrivateAccount.prepare("editor", "editor@example.test", "Editor", "password"), "password");
        Path campaign = temp.newFolder("campaign").toPath();
        StorylineService service = new StorylineService(campaign, () -> account);
        service.ensureRoot();
        Path file = service.createFile(service.getRoot(), "session.txt");
        onFx(() -> {
            Stage stage = new Stage();
            UiSession session = session(stage, campaign);
            user(session, account);
            java.util.List<Runnable> queued = new java.util.ArrayList<>();
            StorylineScene owner = new StorylineScene(session) {
                @Override protected <T> void background(Supplier<T> work, Consumer<T> success, Consumer<Throwable> failure) {
                    queued.add(() -> {
                        try { success.accept(work.get()); } catch (Throwable problem) { failure.accept(problem); }
                    });
                }
                @Override protected void showError(String title, Throwable problem) { throw new AssertionError(title, problem); }
            };
            try {
                StorylineService editorService = service.forEditor();
                editorService.readText(file);
                var drafts = new com.dnd.data.StorylineDrafts(temp.getRoot().toPath().resolve("device"), editorService, file, account);
                drafts.save("other-editor", editorService.editorRevision(file), "Another window.");
                var editor = new StorylineEditorWindow(owner, editorService, new com.dnd.data.CampaignRepositories(campaign), file);
                var area = new org.fxmisc.richtext.CodeArea("Save this text.");
                field(editor, "area", area);
                field(editor, "statusLabel", new javafx.scene.control.Label());
                field(editor, "drafts", drafts);
                field(editor, "dirty", true);
                invoke(editor, "autosave");
                assertEquals(1, queued.size());
                assertEquals(true, invoke(editor, "save"));
                queued.removeFirst().run();
                assertEquals("Save this text.", service.forEditor().readText(file));
                assertEquals("other-editor", drafts.list().getFirst().editorId());
                assertEquals(1, drafts.list().size());

                area.replaceText("Discard this text.");
                field(editor, "dirty", true);
                field(editor, "editRevision", 1L);
                invoke(editor, "autosave");
                assertEquals(true, invoke(editor, "discardOwnDraft"));
                queued.removeFirst().run();
                assertEquals("other-editor", drafts.list().getFirst().editorId());
                assertEquals(1, drafts.list().size());
                assertEquals("Save this text.", service.forEditor().readText(file));

                stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane()));
                stage.show();
                field(editor, "editorStage", stage);
                area.replaceText("Save this text.");
                AtomicReference<Throwable> reviewError = new AtomicReference<>();
                Platform.runLater(() -> {
                    try {
                        Parent dialogRoot = Window.getWindows().stream().filter(window -> window != stage)
                            .map(window -> window.getScene().getRoot())
                            .filter(root -> button(root, "Recover into editor") != null).findFirst().orElseThrow();
                        assertTrue(dialogRoot.lookupAll(".label").stream()
                            .filter(node -> node instanceof javafx.scene.control.Label)
                            .map(node -> ((javafx.scene.control.Label) node).getText())
                            .anyMatch(text -> text != null && text.contains("changed since this draft")));
                        var tabs = (javafx.scene.control.TabPane) dialogRoot.lookup(".tab-pane");
                        assertEquals("Another window.", ((javafx.scene.control.TextArea) tabs.getTabs().get(0).getContent()).getText());
                        assertEquals("Save this text.", ((javafx.scene.control.TextArea) tabs.getTabs().get(1).getContent()).getText());
                        button(dialogRoot, "Recover into editor").fire();
                    } catch (Throwable problem) {
                        reviewError.set(problem);
                    } finally {
                        for (Window window : java.util.List.copyOf(Window.getWindows())) if (window != stage) window.hide();
                    }
                });
                invoke(editor, "reviewDrafts");
                if (reviewError.get() != null) throw new AssertionError(reviewError.get());
                assertEquals("Another window.", area.getText());
                assertEquals("Save this text.", service.forEditor().readText(file));
                assertEquals(true, invoke(editor, "save"));
                assertTrue(drafts.list().isEmpty());
            } finally {
                session.lockPrivateAccount();
                stage.close();
            }
        });
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test public void restorePointUiRequiresCurrentGmRoleAndDeletedCampaignCreatorIdentity() throws Exception {
        var oldConfig = com.dnd.cloud.Cloud.config();
        var directoryStore = new LocalCloudStore();
        com.dnd.cloud.Cloud.override(directoryStore, new com.dnd.cloud.CloudConfig(false, "", "", "", ""));
        Path campaign = temp.newFolder("campaign").toPath();
        String identity = CampaignMeta.ensureId(campaign);
        var directory = com.dnd.cloud.CampaignDirectory.shared();
        var record = directory.register(identity, "Campaign", "Author");
        String memberKey = com.dnd.cloud.CloudKeys.of("Reader");
        record.members.put(memberKey, new com.dnd.cloud.CampaignRecord.Member("Reader", com.dnd.cloud.CampaignRecord.Role.PLAYER));
        directoryStore.put("campaigns/" + identity, record);
        try {
            onFx(() -> {
                Stage stage = new Stage();
                UiSession session = session(stage, campaign);
                var user = new User();
                user.setUsername("Reader");
                user.setProviderUid("reader-uid");
                session.setCurrentUser(user);
                BaseScene owner = new StorylineScene(session);
                var constructor = RestorePointsUi.class.getDeclaredConstructor(BaseScene.class, Path.class, Runnable.class);
                constructor.setAccessible(true);
                var window = constructor.newInstance(owner, campaign, (Runnable) () -> { });
                var allowed = RestorePointsUi.class.getDeclaredMethod("authorized", Path.class, com.dnd.data.CampaignRestorePoints.Point.class);
                allowed.setAccessible(true);
                var point = new com.dnd.data.CampaignRestorePoints.Point(1, "fixture", 1, "Before campaign deletion",
                    campaign.toString(), identity, "Campaign", "reader-uid");
                assertEquals(false, allowed.invoke(window, campaign, point));
                record.members.get(memberKey).role = com.dnd.cloud.CampaignRecord.Role.DM;
                directoryStore.put("campaigns/" + identity, record);
                assertEquals(true, allowed.invoke(window, campaign, point));
                directoryStore.delete("campaigns/" + identity);
                assertEquals(true, allowed.invoke(window, campaign, point));
                user.setProviderUid("another-uid");
                assertEquals(false, allowed.invoke(window, campaign, point));
                stage.close();
            });
        } finally {
            com.dnd.cloud.Cloud.override(null, oldConfig);
        }
    }

    private static void field(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test public void restoreWindowListsComparesCapturesAndRestoresSelectedFile() throws Exception {
        String oldHome = System.getProperty("dnd.home");
        var oldConfig = com.dnd.cloud.Cloud.config();
        System.setProperty("dnd.home", temp.getRoot().getAbsolutePath());
        var directoryStore = new LocalCloudStore();
        com.dnd.cloud.Cloud.override(directoryStore, new com.dnd.cloud.CloudConfig(false, "", "", "", ""));
        Path campaign = temp.newFolder("campaign").toPath();
        Path notes = campaign.resolve("notes.txt");
        Files.writeString(notes, "Before changes");
        var points = com.dnd.data.CampaignRestorePoints.forCampaign(campaign);
        var original = points.capture("Manual restore point", "gm-uid");
        Files.writeString(notes, "Current notes");
        com.dnd.cloud.CampaignDirectory.shared().register(CampaignMeta.idOf(campaign).orElseThrow(), "Campaign", "GM");
        AtomicReference<RestorePointsUi> window = new AtomicReference<>();
        CountDownLatch restored = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean reloaded = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            onFx(() -> {
                Stage stage = new Stage();
                UiSession session = session(stage, campaign);
                User gm = new User();
                gm.setUsername("GM");
                gm.setProviderUid("gm-uid");
                session.setCurrentUser(gm);
                StorylineScene owner = new StorylineScene(session) {
                    @Override protected void showInfo(String title, String message) {
                        assertEquals("Files restored", title);
                        restored.countDown();
                    }
                    @Override protected void showError(String title, Throwable problem) {
                        error.set(problem);
                        restored.countDown();
                    }
                };
                stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane()));
                stage.show();
                var constructor = RestorePointsUi.class.getDeclaredConstructor(BaseScene.class, Path.class, Runnable.class);
                constructor.setAccessible(true);
                window.set(constructor.newInstance(owner, campaign, (Runnable) () -> reloaded.set(true)));
                invoke(window.get(), "open");
            });
            awaitFx(() -> field(window.get(), "plan") != null, error);
            onFx(() -> {
                @SuppressWarnings("unchecked")
                var list = (javafx.scene.control.ListView<com.dnd.data.CampaignArchive.FileEntry>) field(window.get(), "files");
                list.getSelectionModel().select(list.getItems().stream().filter(e -> e.path().equals("notes.txt")).findFirst().orElseThrow());
            });
            awaitFx(() -> "Before changes".equals(((javafx.scene.control.TextArea) field(window.get(), "saved")).getText()), error);
            onFx(() -> {
                assertEquals("Current notes", ((javafx.scene.control.TextArea) field(window.get(), "current")).getText());
                Stage stage = (Stage) field(window.get(), "stage");
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                button(stage.getScene().getRoot(), "Create restore point").fire();
            });
            awaitFx(() -> field(window.get(), "plan") instanceof com.dnd.data.CampaignRestorePoints.Plan plan
                && !plan.point().id().equals(original.id()) && !((javafx.scene.layout.VBox) field(window.get(), "layout")).isDisabled(), error);
            assertEquals(2, points.list().size());
            onFx(() -> {
                javafx.scene.control.ComboBox<?> choices = (javafx.scene.control.ComboBox<?>) field(window.get(), "points");
                choices.getSelectionModel().selectLast();
            });
            awaitFx(() -> field(window.get(), "plan") instanceof com.dnd.data.CampaignRestorePoints.Plan plan
                && plan.point().id().equals(original.id()), error);
            onFx(() -> {
                @SuppressWarnings("unchecked")
                var list = (javafx.scene.control.ListView<com.dnd.data.CampaignArchive.FileEntry>) field(window.get(), "files");
                list.getSelectionModel().select(list.getItems().stream().filter(e -> e.path().equals("notes.txt")).findFirst().orElseThrow());
                Platform.runLater(() -> {
                    try {
                        var dialog = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                            .filter(root -> root instanceof javafx.scene.control.DialogPane).findFirst().orElseThrow();
                        ((Button) ((javafx.scene.control.DialogPane) dialog).lookupButton(javafx.scene.control.ButtonType.OK)).fire();
                    } catch (Throwable problem) {
                        error.set(problem);
                        restored.countDown();
                        for (Window w : java.util.List.copyOf(Window.getWindows())) if (w != fieldUnchecked(window.get(), "stage")) w.hide();
                    }
                });
                button(((Stage) field(window.get(), "stage")).getScene().getRoot(), "Restore selected files").fire();
            });
            assertTrue(restored.await(20, TimeUnit.SECONDS));
            if (error.get() != null) throw new AssertionError(error.get());
            assertTrue(reloaded.get());
            assertEquals("Before changes", Files.readString(notes));
            assertEquals(3, points.list().size());
        } finally {
            onFx(() -> { for (Window w : java.util.List.copyOf(Window.getWindows())) w.hide(); });
            com.dnd.cloud.Cloud.override(null, oldConfig);
            if (oldHome == null) System.clearProperty("dnd.home"); else System.setProperty("dnd.home", oldHome);
        }
    }

    private interface FxCondition { boolean get() throws Exception; }

    @Test public void actionHistoryWindowComparesAndConfirmsUndoAndChecksCurrentMembership() throws Exception {
        String oldHome = System.getProperty("dnd.home");
        var oldConfig = com.dnd.cloud.Cloud.config();
        System.setProperty("dnd.home", temp.getRoot().getAbsolutePath());
        var store = new LocalCloudStore();
        com.dnd.cloud.Cloud.override(store, new com.dnd.cloud.CloudConfig(false, "", "", "", ""));
        Path campaign = temp.newFolder("history-campaign").toPath();
        String id = CampaignMeta.ensureId(campaign);
        var record = com.dnd.cloud.CampaignDirectory.shared().register(id, "Campaign", "GM");
        var repos = new com.dnd.data.CampaignRepositories(campaign);
        var pc = new com.dnd.model.character.PlayerCharacter();
        pc.setId("hero");
        pc.setName("Hero");
        pc.setMaxHitPoints(20);
        pc.setCurrentHitPoints(20);
        repos.players().add(pc);
        pc.applyDamage(6);
        repos.players().save(pc);
        AtomicReference<OperationHistoryUi> window = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch undone = new CountDownLatch(1);
        try {
            onFx(() -> {
                Stage stage = new Stage();
                UiSession session = session(stage, campaign);
                User gm = new User();
                gm.setUsername("GM");
                session.setCurrentUser(gm);
                session.setDm(true);
                StorylineScene owner = new StorylineScene(session) {
                    @Override protected void showInfo(String title, String message) {
                        assertEquals("Action undone", title);
                        undone.countDown();
                    }
                    @Override protected void showError(String title, Throwable problem) {
                        error.set(problem);
                        undone.countDown();
                    }
                };
                stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane()));
                stage.show();
                var constructor = OperationHistoryUi.class.getDeclaredConstructor(BaseScene.class);
                constructor.setAccessible(true);
                window.set(constructor.newInstance(owner));
                record.members.get(com.dnd.cloud.CloudKeys.of("GM")).role = com.dnd.cloud.CampaignRecord.Role.PLAYER;
                store.put("campaigns/" + id, record);
                var rejected = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> invoke(window.get(), "authorize"));
                assertTrue(rejected.getCause() instanceof SecurityException);
                record.members.get(com.dnd.cloud.CloudKeys.of("GM")).role = com.dnd.cloud.CampaignRecord.Role.DM;
                store.put("campaigns/" + id, record);
                invoke(window.get(), "open");
            });
            awaitFx(() -> !((javafx.scene.control.Button) field(window.get(), "undo")).isDisabled(), error);
            onFx(() -> {
                String before = ((javafx.scene.control.TextArea) field(window.get(), "before")).getText();
                String after = ((javafx.scene.control.TextArea) field(window.get(), "after")).getText();
                assertTrue(before.contains("currentHitPoints\n20"));
                assertTrue(after.contains("currentHitPoints\n14"));
                assertFalse(((javafx.scene.control.TextArea) field(window.get(), "before")).isEditable());
                Platform.runLater(() -> {
                    try {
                        var pane = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                            .filter(root -> root instanceof javafx.scene.control.DialogPane)
                            .map(root -> (javafx.scene.control.DialogPane) root).findFirst().orElseThrow();
                        ((Button) pane.lookupButton(javafx.scene.control.ButtonType.OK)).fire();
                    } catch (Throwable problem) {
                        error.set(problem);
                        undone.countDown();
                        for (Window w : java.util.List.copyOf(Window.getWindows())) w.hide();
                    }
                });
                ((Button) field(window.get(), "undo")).fire();
            });
            assertTrue(undone.await(20, TimeUnit.SECONDS));
            if (error.get() != null) throw new AssertionError(error.get());
            assertEquals(20, repos.players().getById("hero").getCurrentHitPoints());
            assertEquals(2, new com.dnd.data.OperationHistory(campaign).list().size());
        } finally {
            onFx(() -> { for (Window w : java.util.List.copyOf(Window.getWindows())) w.hide(); });
            com.dnd.sync.CampaignSyncService.shared().closed();
            com.dnd.cloud.Cloud.override(null, oldConfig);
            if (oldHome == null) System.clearProperty("dnd.home"); else System.setProperty("dnd.home", oldHome);
        }
    }

    private static void awaitFx(FxCondition condition, AtomicReference<Throwable> error) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (error.get() != null) throw new AssertionError(error.get());
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            onFx(() -> ready.set(condition.get()));
            if (ready.get()) return;
            Thread.sleep(25);
        }
        fail("Restore window did not finish its operation.");
    }

    private static Object fieldUnchecked(Object target, String name) {
        try { return field(target, name); } catch (Exception e) { throw new AssertionError(e); }
    }

    private static Object invoke(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private UiSession session(Stage stage, Path campaign) {
        CampaignStorage storage = new CampaignStorage(temp.getRoot().toPath());
        CliSession cli = new CliSession(storage, null);
        cli.setCampaignContext(new CampaignContext("Test campaign", campaign));
        return new UiSession(cli, storage, stage);
    }

    private static void user(UiSession session, PrivateAccount account) {
        User user = new User();
        user.setEmail(account.identity().email());
        user.setUsername(account.identity().name());
        session.setCurrentUser(user);
        session.setPrivateAccount(account);
    }

    private static <T> void selectFirstChild(TreeView<T> tree) {
        tree.getSelectionModel().select(tree.getRoot().getChildren().getFirst());
    }

    private static Button button(Parent root, String text) {
        return root.lookupAll(".button").stream().filter(node -> node instanceof Button b && text.equals(b.getText()))
            .map(node -> (Button) node).findFirst().orElse(null);
    }

    private interface Work { void run() throws Exception; }
    private static void onFx(Work work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { work.run(); } catch (Throwable error) { failure.set(error); } finally { done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
