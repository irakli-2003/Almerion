package com.dnd.ui.scenes;

import com.dnd.chat.SessionChat;
import com.dnd.cli.core.*;
import com.dnd.cli.storage.CampaignStorage;
import com.dnd.data.*;
import com.dnd.model.character.*;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.ui.UiSession;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.stage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class UpgradeRequestsUiTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @BeforeClass public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Runnable start = () -> { Platform.setImplicitExit(false); ready.countDown(); };
        try { Platform.startup(start); } catch (IllegalStateException running) { Platform.runLater(start); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test public void gmReviewsEditsAndAcceptsAPlayerProposal() throws Exception {
        var root = temp.newFolder().toPath();
        var repos = new CampaignRepositories(root);
        CharacterClass cls = new CharacterClass(); cls.setId("fighter"); cls.setName("Fighter");
        repos.classes().save(cls);
        PlayerCharacter pc = new PlayerCharacter("hero", "Hero", "fighter", "human", 5,
            new CoreStats(10, 10, 10, 10, 10, 10), new ArrayList<>(), new ArrayList<>());
        pc.setXp(100); pc.setMaxHitPoints(40); pc.setCurrentHitPoints(4); repos.players().save(pc);
        var service = UpgradeRequests.at(root);
        var options = service.options("hero", null);
        String node = options.choices().stream().filter(c -> c.blocked().isBlank()).findFirst().orElseThrow().id();
        var proposal = new UpgradeRequests.Proposal(options.revision(), Map.of("strength", 2), node, Map.of());
        service.execute("pc:hero", new SessionChat.Command(UUID.randomUUID().toString(), "upgrade-submit", null,
            List.of(), "", JsonMappers.create().writeValueAsString(proposal)));
        onFx(() -> {
            Stage primary = new Stage();
            var storage = new CampaignStorage(temp.getRoot().toPath());
            var cli = new CliSession(storage, null);
            cli.setCampaignContext(new CampaignContext("Test", root));
            UiSession session = new UiSession(cli, storage, primary); session.setDm(true);
            BaseScene owner = new StorylineScene(session) {
                @Override protected <T> void background(Supplier<T> work, Consumer<T> success, Consumer<Throwable> failure) {
                    T value;
                    try { value = work.get(); } catch (RuntimeException error) { failure.accept(error); return; }
                    success.accept(value);
                }
            };
            primary.setScene(new Scene(new StackPane())); primary.show();
            try {
                UpgradeRequestsUi.show(owner);
                Stage window = Window.getWindows().stream().filter(w -> w instanceof Stage s
                    && "Level-up requests".equals(s.getTitle())).map(w -> (Stage) w).findFirst().orElseThrow();
                var sceneRoot = window.getScene().getRoot();
                sceneRoot.applyCss(); sceneRoot.layout();
                button(sceneRoot, "Review & edit").fire();
                sceneRoot.applyCss(); sceneRoot.layout();
                var spinners = sceneRoot.lookupAll(".spinner").stream().map(n -> (Spinner<?>) n).toList();
                assertEquals(6, spinners.size());
                for (Spinner<?> spinner : spinners) {
                    @SuppressWarnings("unchecked") Spinner<Integer> input = (Spinner<Integer>) spinner;
                    input.getValueFactory().setValue(0);
                }
                // The wizard follows schema order; assign two points to Constitution instead.
                var grid = sceneRoot.lookupAll(".spinner").stream()
                    .map(n -> (Spinner<?>) n).filter(s -> s.getParent() instanceof javafx.scene.layout.HBox h
                        && h.getChildren().getFirst() instanceof Label label && label.getText().startsWith("Constitution")).findFirst().orElseThrow();
                @SuppressWarnings("unchecked") Spinner<Integer> constitution = (Spinner<Integer>) grid;
                constitution.getValueFactory().setValue(2);
                button(sceneRoot, "Next: class upgrade").fire();
                sceneRoot.applyCss(); sceneRoot.layout();
                @SuppressWarnings("unchecked") ComboBox<UpgradeRequests.Choice> nodes =
                    (ComboBox<UpgradeRequests.Choice>) sceneRoot.lookup(".combo-box");
                nodes.getSelectionModel().selectFirst();
                assertTrue(sceneRoot.lookupAll(".text-area").stream().map(n -> (TextArea) n)
                    .anyMatch(a -> !a.getText().isBlank()));
                button(sceneRoot, "Accept edited upgrade").fire();
                assertEquals("accepted", service.inbox("gm").requests().getFirst().status());
                PlayerCharacter upgraded = repos.players().getById("hero");
                assertEquals(6, upgraded.getLevel());
                assertEquals(10, upgraded.getStats().getStrength());
                assertEquals(12, upgraded.getStats().getConstitution());
                session.setDm(false);
                primary.setScene(new Scene(new StackPane()));
                assertFalse("GM review closes when switching to player mode", window.isShowing());
            } finally { primary.close(); }
        });
    }

    private static Button button(javafx.scene.Parent root, String text) {
        return root.lookupAll(".button").stream().filter(n -> n instanceof Button b && text.equals(b.getText()))
            .map(n -> (Button) n).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + text));
    }
    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> { try { work.run(); } catch (Throwable t) { error.set(t); } finally { done.countDown(); } });
        assertTrue(done.await(25, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
