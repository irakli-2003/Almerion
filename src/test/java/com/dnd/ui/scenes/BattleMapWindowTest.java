package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.combat.Ability;
import com.dnd.model.combat.Effect;
import com.dnd.model.combat.TurnHistory;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.creature.Monster;
import com.dnd.model.world.map.*;
import com.dnd.ui.UiSession;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class BattleMapWindowTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass public static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException running) { ready.countDown(); }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @Test public void gmCanManageEffectsResizePanelAndOverrideMovement() throws Exception {
        runBattle((battle, map, stage) -> {
            assertNotNull(stage.getScene().lookup("#battle-panel-split"));
            SplitPane split = (SplitPane) stage.getScene().lookup("#battle-panel-split");
            split.setDividerPositions(.45);
            stage.getScene().getRoot().layout();
            assertTrue(split.getDividerPositions()[0] > .4);
            assertFalse(stage.getScene().getRoot().lookupAll(".button").stream()
                .anyMatch(n -> n instanceof Button b && b.getText().contains("Rewind")));
            MapObject rabbit = map.getCell(0, 0).getOccupants().getFirst();
            CombatState state = TokenSupport.combatOf(rabbit);
            state.setWalkSpeed(1);
            Canvas canvas = (Canvas) field(battle, "canvas");
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 12, 12));
            canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 12, 12));
            int movement = state.movementRemaining();
            map.getCell(4, 4).setPassable(false);
            canvas.getOnMousePressed().handle(mouse(MouseButton.SECONDARY, 4 * 48 + 12, 4 * 48 + 12));
            assertEquals(new Position(4, 4), rabbit.getPosition());
            assertEquals(movement, state.movementRemaining());
            canvas.getOnMousePressed().handle(mouse(MouseButton.SECONDARY, 2 * 48 + 12, 2 * 48 + 12));
            assertEquals("Occupied destinations must be rejected", new Position(4, 4), rabbit.getPosition());
            canvas.getOnMousePressed().handle(mouse(MouseButton.SECONDARY, -12, -12));
            assertEquals(new Position(4, 4), rabbit.getPosition());
            canvas.getOnMouseReleased().handle(mouse(MouseButton.SECONDARY, -12, -12));
            assertEquals(movement, state.movementRemaining());
            UiSession session = (UiSession) field(battle, "uiSession");
            session.setDm(false);
            canvas.getOnMousePressed().handle(mouse(MouseButton.SECONDARY, 12, 12));
            assertEquals(new Position(4, 4), rabbit.getPosition());
            session.setDm(true);
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 2 * 48 + 12, 2 * 48 + 12));
            canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 2 * 48 + 12, 2 * 48 + 12));
            MapObject player = map.getCell(2, 2).getOccupants().getFirst();
            CombatState effectsState = TokenSupport.combatOf(player);
            CampaignRepositories repos = (CampaignRepositories) field(battle, "repos");
            AtomicReference<Throwable> nested = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    DialogPane pane = dialogPane(stage);
                    assertEquals(1, ((ComboBox<?>) pane.lookup(".combo-box")).getItems().size());
                    ((Button) pane.lookupButton(ButtonType.OK)).fire();
                } catch (Throwable t) { nested.set(t); closeDialogs(stage); }
            });
            button(stage, "Add Effect...").fire();
            if (nested.get() != null) throw new AssertionError(nested.get());
            assertEquals(1, effectsState.getActiveEffects().size());
            assertEquals("burn", effectsState.getActiveEffects().getFirst().getEffectId());
            assertEquals(3, effectsState.getActiveEffects().getFirst().getRemainingRounds());
            assertEquals(1, repos.players().getById("player-0").getActiveEffects().size());
            button(stage, "Remove").fire();
            assertTrue(effectsState.getActiveEffects().isEmpty());
            assertTrue(repos.players().getById("player-0").getActiveEffects().isEmpty());
            assertNotNull(button(stage, "Add Effect..."));
        });
    }

    @Test public void rabbitAbilityUsesEnteredDcAndPreservesOnlySuccessfulCharacters() throws Exception {
        runBattle((battle, map, stage) -> {
            MapObject rabbit = map.getCell(0, 0).getOccupants().getFirst();
            MapObject first = map.getCell(2, 2).getOccupants().getFirst();
            MapObject second = map.getCell(3, 3).getOccupants().getFirst();
            TurnHistory history = (TurnHistory) field(battle, "history");
            history.record(map, rabbit, 1);
            TokenSupport.combatOf(rabbit).applyDamage(5);
            TokenSupport.combatOf(rabbit).startCooldown("another-ability", 4);
            TokenSupport.combatOf(first).applyDamage(7);
            TokenSupport.combatOf(second).applyDamage(8);
            history.record(map, rabbit, 2);
            ((com.dnd.model.combat.InitiativeTracker) field(battle, "initiative")).restore(rabbit, 2);
            AtomicReference<Throwable> nested = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    DialogPane pane = dialogPane(stage);
                    ScrollPane scroll = (ScrollPane) pane.getContent();
                    VBox checks = (VBox) scroll.getContent();
                    @SuppressWarnings("unchecked")
                    Spinner<Integer> roll = (Spinner<Integer>) checks.getChildren().get(1);
                    roll.getEditor().setText("");
                    ((Button) pane.lookupButton(ButtonType.OK)).fire();
                    assertTrue("Invalid input must keep the dialog open", pane.getScene().getWindow().isShowing());
                    assertEquals(15, TokenSupport.combatOf(rabbit).getCurrentHitPoints());
                    roll.getEditor().setText("12");
                    var totals = checks.lookupAll(".spinner").stream().filter(n -> n != roll).toList();
                    for (var node : totals) {
                        @SuppressWarnings("unchecked") Spinner<Integer> total = (Spinner<Integer>) node;
                        String name = ((Label) ((javafx.scene.layout.HBox) total.getParent())
                            .getChildren().getFirst()).getText();
                        total.getEditor().setText(name.equals("Success") ? "17" : "16");
                    }
                    ((Button) pane.lookupButton(ButtonType.OK)).fire();
                } catch (Throwable t) { nested.set(t); closeDialogs(stage); }
            });
            useRabbitAbility(battle, stage);
            if (nested.get() != null) throw new AssertionError(nested.get());
            assertEquals(19, TokenSupport.combatOf(rabbit).getCurrentHitPoints());
            assertEquals(18, TokenSupport.combatOf(rabbit).getCurrentMana());
            assertEquals(13, TokenSupport.combatOf(first).getCurrentHitPoints());
            assertEquals(20, TokenSupport.combatOf(second).getCurrentHitPoints());
            assertEquals(3, TokenSupport.combatOf(rabbit).cooldownFor("pocket_watch_rewind"));
            assertEquals(0, TokenSupport.combatOf(rabbit).cooldownFor("another-ability"));
        });
    }

    @Test public void cancellingRewindSpendsNothingAndMissingHistoryIsExplained() throws Exception {
        runBattle((battle, map, stage) -> {
            MapObject rabbit = map.getCell(0, 0).getOccupants().getFirst();
            ((TurnHistory) field(battle, "history")).clear();
            useRabbitAbility(battle, stage);
            assertTrue(((Label) field(battle, "statusLabel")).getText().contains("No earlier turn"));
            TurnHistory history = (TurnHistory) field(battle, "history");
            history.record(map, rabbit, 1);
            TokenSupport.combatOf(rabbit).applyDamage(5);
            history.record(map, rabbit, 2);
            ((com.dnd.model.combat.InitiativeTracker) field(battle, "initiative")).restore(rabbit, 2);
            AtomicReference<Throwable> nested = new AtomicReference<>();
            Platform.runLater(() -> {
                try { ((Button) dialogPane(stage).lookupButton(ButtonType.CANCEL)).fire(); }
                catch (Throwable t) { nested.set(t); closeDialogs(stage); }
            });
            useRabbitAbility(battle, stage);
            if (nested.get() != null) throw new AssertionError(nested.get());
            assertEquals(15, TokenSupport.combatOf(rabbit).getCurrentHitPoints());
            assertEquals(20, TokenSupport.combatOf(rabbit).getCurrentMana());
            assertEquals(0, TokenSupport.combatOf(rabbit).cooldownFor("pocket_watch_rewind"));
            assertEquals(2, history.previousTurnOf(rabbit, false).getRound());
        });
    }

    private interface Check { void run(BattleMapWindow battle, GameMap map, Stage stage) throws Exception; }

    private void runBattle(Check check) throws Exception {
        Path root = temp.getRoot().toPath();
        CampaignRepositories repos = new CampaignRepositories(root);
        GameMap map = new GameMap("arena", "Arena", 6, 6);
        Monster monster = new Monster();
        monster.setId("rabbit");
        monster.setName("Rabbit");
        Ability ability = new Ability();
        ability.setId("pocket_watch_rewind");
        ability.setName("Pocket Watch Rewind");
        ability.setRecharge(3);
        ability.setManaCost(2);
        ability.setHpCost(1);
        monster.setAbilities(java.util.List.of(ability));
        MonsterToken rabbit = new MonsterToken(monster);
        map.placeObject(rabbit, 0, 0);
        for (int i = 0; i < 2; i++) {
            PlayerCharacter pc = new PlayerCharacter();
            pc.setId("player-" + i);
            pc.setName(i == 0 ? "Success" : "Failure");
            pc.setMaxHitPoints(20);
            pc.setCurrentHitPoints(20);
            repos.players().add(pc);
            map.placeObject(new PlayerToken(pc), i + 2, i + 2);
        }
        for (int i = 0; i < 6; i++) for (int j = 0; j < 6; j++) {
            for (MapObject token : map.getCell(i, j).getOccupants()) {
                TokenSupport.combatOf(token).setMaxHitPoints(20);
                TokenSupport.combatOf(token).setCurrentHitPoints(20);
                TokenSupport.combatOf(token).setMaxMana(20);
                TokenSupport.combatOf(token).setCurrentMana(20);
            }
        }
        repos.maps().add(map);
        Effect burn = new Effect("burn", "Burn", "Burning", true, false, 2, 0);
        burn.setDurationRounds(3);
        repos.effects().add(burn);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            Stage owner = new Stage();
            try {
                Platform.setImplicitExit(false);
                UiSession session = new UiSession(null, null, owner) {
                    @Override public Path campaignRoot() { return root; }
                };
                session.setDm(true);
                BaseScene base = new BaseScene(session) {
                    @Override public Scene build() { throw new UnsupportedOperationException(); }
                };
                BattleMapWindow battle = new BattleMapWindow(base, session, "arena");
                battle.show();
                Stage stage = new ArrayList<>(Window.getWindows()).stream()
                    .filter(w -> w instanceof Stage s && s.getTitle().equals("Battle: Arena"))
                    .map(w -> (Stage) w).findFirst().orElseThrow();
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                check.run(battle, (GameMap) field(battle, "map"), stage);
            } catch (Throwable t) { failure.set(t); }
            finally {
                new ArrayList<>(Window.getWindows()).forEach(Window::hide);
                owner.close();
                done.countDown();
            }
        });
        assertTrue(done.await(40, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Button button(Stage stage, String text) {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        return stage.getScene().getRoot().lookupAll(".button").stream()
            .filter(n -> n instanceof Button b && b.getText().equals(text))
            .map(n -> (Button) n).findFirst().orElseThrow();
    }

    private static void useRabbitAbility(BattleMapWindow battle, Stage stage) throws Exception {
        Canvas canvas = (Canvas) field(battle, "canvas");
        canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 12, 12));
        canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 12, 12));
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        Label ability = stage.getScene().getRoot().lookupAll(".hover-row").stream()
            .filter(n -> n instanceof Label l && l.getText().equals("Pocket Watch Rewind"))
            .map(n -> (Label) n).findFirst().orElseThrow();
        ability.getOnMouseClicked().handle(mouse(MouseButton.PRIMARY, 0, 0));
    }

    private static DialogPane dialogPane(Stage owner) {
        return new ArrayList<>(Window.getWindows()).stream()
            .filter(w -> w != owner && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane)
            .map(w -> (DialogPane) w.getScene().getRoot()).findFirst().orElseThrow();
    }

    private static void closeDialogs(Stage owner) {
        new ArrayList<>(Window.getWindows()).stream().filter(w -> w != owner).forEach(Window::hide);
    }

    private static MouseEvent mouse(MouseButton button, double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_PRESSED, x, y, x, y, button, 1,
            false, false, false, false, false, false, false, false, false, true, null);
    }
}
