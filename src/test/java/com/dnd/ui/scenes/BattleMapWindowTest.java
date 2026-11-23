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

    @Test public void defeatXpPaysOnlyAtDeathAndDoesNotRepeatAfterRevival() throws Exception {
        runBattle((battle, map, stage) -> {
            var repos = new CampaignRepositories(temp.getRoot().toPath());
            var target = map.getCell(0, 0).getOccupants().getFirst();
            var state = TokenSupport.combatOf(target);
            state.setKillXp(40);
            state.setSharedXp(11);
            state.setCurrentHitPoints(0);
            state.setDownedByPlayerId("player-0");
            Canvas canvas = (Canvas) field(battle, "canvas");
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 12, 12));
            canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 12, 12));
            button(stage, "Failed Save").fire();
            button(stage, "Failed Save").fire();
            assertEquals(0, repos.players().getById("player-0").getXp());
            button(stage, "Failed Save").fire();
            assertEquals(45, repos.players().getById("player-0").getXp());
            assertEquals(5, repos.players().getById("player-1").getXp());
            var saved = TokenSupport.combatOf(repos.maps().getById(map.getId()).getCell(0, 0).getOccupants().getFirst());
            assertEquals(state.getXpRewardId(), saved.getXpRewardId());
            assertFalse(com.dnd.data.BattleXp.award(repos, map.getId() + "/" + saved.getXpRewardId(), 40, 11,
                "player-1", java.util.List.of("player-0", "player-1")).applied());
            state.revive();
            var panel = BattleMapWindow.class.getDeclaredMethod("showDetail", MapObject.class);
            panel.setAccessible(true);
            panel.invoke(battle, target);
            button(stage, "Kill").fire();
            assertEquals(45, repos.players().getById("player-0").getXp());
            assertEquals(5, repos.players().getById("player-1").getXp());
        });
    }

    @Test public void unattributedKillCanBeDeferredAndResolvedByChoosingAPlayer() throws Exception {
        runBattle((battle, map, stage) -> {
            var repos = new CampaignRepositories(temp.getRoot().toPath());
            var target = map.getCell(0, 0).getOccupants().getFirst();
            TokenSupport.combatOf(target).setKillXp(23);
            Canvas canvas = (Canvas) field(battle, "canvas");
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 12, 12));
            canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 12, 12));
            Platform.runLater(() -> ((Button) dialogPane(stage).lookupButton(ButtonType.CANCEL)).fire());
            button(stage, "Kill").fire();
            assertEquals(0, repos.players().getById("player-0").getXp());
            Platform.runLater(() -> {
                var pane = dialogPane(stage);
                var combo = (ComboBox<?>) pane.lookup(".combo-box");
                combo.getSelectionModel().select(0);
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
            });
            button(stage, "Resolve defeat XP").fire();
            assertEquals(0, repos.players().getById("player-0").getXp());
            assertEquals(23, repos.players().getById("player-1").getXp());
        });
    }

    @Test public void hpInputIsCompactAndRevivalUsesEnteredD4WithIndependentSaves() throws Exception {
        runBattle((battle, map, stage) -> {
            Canvas canvas = (Canvas) field(battle, "canvas");
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 108, 108));
            canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 108, 108));
            var player = map.getCell(2, 2).getOccupants().getFirst();
            var state = TokenSupport.combatOf(player);
            SplitPane split = (SplitPane) stage.getScene().lookup("#battle-panel-split");
            split.setDividerPositions(.21);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            Spinner<?> amount = (Spinner<?>) stage.getScene().lookup("#hp-change-amount");
            assertEquals(1, amount.getValue());
            Button heal = button(stage, "Heal");
            assertTrue(heal.getWidth() + .5 >= heal.prefWidth(-1));
            var host = (javafx.scene.Node) field(battle, "panelHost");
            assertTrue(heal.localToScene(heal.getBoundsInLocal()).getMaxX()
                <= host.localToScene(host.getBoundsInLocal()).getMaxX());
            amount.getEditor().setText("7");
            button(stage, "Damage").fire();
            assertEquals(13, state.getCurrentHitPoints());
            amount.getEditor().setText("3");
            button(stage, "Heal").fire();
            assertEquals(16, state.getCurrentHitPoints());
            amount.getEditor().setText("invalid");
            button(stage, "Damage").fire();
            assertEquals(16, state.getCurrentHitPoints());
            amount.getEditor().setText("16");
            button(stage, "Damage").fire();
            button(stage, "Failed Save").fire();
            button(stage, "Made Save").fire();
            button(stage, "Made Save").fire();
            assertEquals(1, state.getDeathSaveFailures());
            assertEquals(2, state.getDeathSaveSuccesses());
            Platform.runLater(() -> ((Button) dialogPane(stage).lookupButton(ButtonType.CANCEL)).fire());
            button(stage, "Made Save").fire();
            assertEquals(0, state.getCurrentHitPoints());
            assertEquals(2, state.getDeathSaveSuccesses());
            Platform.runLater(() -> {
                var pane = dialogPane(stage);
                ((TextField) pane.lookup(".text-field")).setText("2");
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
            });
            button(stage, "Made Save").fire();
            assertEquals(2, state.getCurrentHitPoints());
            assertEquals(0, state.getDeathSaveSuccesses());
            assertEquals(0, state.getDeathSaveFailures());
            var repos = (CampaignRepositories) field(battle, "repos");
            assertEquals(2, repos.players().getById("player-0").getCurrentHitPoints());
            Platform.runLater(() -> {
                var pane = dialogPane(stage);
                ((TextField) pane.lookup(".text-field")).setText("4");
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
            });
            button(stage, "Revive").fire();
            assertEquals(4, state.getCurrentHitPoints());
        });
    }

    @Test public void abilityCastingUsesConfiguredRollsAndCancelSpendsNothing() throws Exception {
        runBattle((battle, map, stage) -> {
            var caster = map.getCell(0, 0).getOccupants().getFirst();
            var target = map.getCell(2, 2).getOccupants().getFirst();
            Ability ability = new Ability();
            ability.setId("flame");
            ability.setName("Flame");
            ability.setRange(60);
            ability.setManaCost(2);
            ability.getChecks().setAttackRoll(true);
            ability.getChecks().setSavingThrow("dexterity");
            ability.getChecks().setSaveDc(15);
            var damage = new com.dnd.model.combat.Damage();
            damage.setConstant(10);
            ability.setDamage(damage);
            TokenSupport.abilitiesOf(caster).add(ability);
            Canvas canvas = (Canvas) field(battle, "canvas");
            Runnable aim = () -> {
                canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 12, 12));
                canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 12, 12));
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                Label action = stage.getScene().getRoot().lookupAll(".hover-row").stream()
                    .filter(n -> n instanceof Label l && l.getText().startsWith("Flame"))
                    .map(n -> (Label) n).findFirst().orElseThrow();
                action.getOnMouseClicked().handle(mouse(MouseButton.PRIMARY, 0, 0));
            };
            aim.run();
            AtomicReference<Throwable> nested = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    var pane = dialogPane(stage);
                    assertTrue(((CheckBox) pane.lookup(".check-box")).isSelected());
                    ((Spinner<?>) pane.lookup("#cast-attack-die-0")).getEditor().setText("20");
                    ((Spinner<?>) pane.lookup("#cast-save-die-0")).getEditor().setText("15");
                    ((Button) pane.lookupButton(ButtonType.OK)).fire();
                } catch (Throwable t) { nested.set(t); closeDialogs(stage); }
            });
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 108, 108));
            if (nested.get() != null) throw new AssertionError(nested.get());
            assertEquals(15, TokenSupport.combatOf(target).getCurrentHitPoints());
            assertEquals(18, TokenSupport.combatOf(caster).getCurrentMana());
            aim.run();
            Platform.runLater(() -> ((Button) dialogPane(stage).lookupButton(ButtonType.CANCEL)).fire());
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 108, 108));
            assertEquals(15, TokenSupport.combatOf(target).getCurrentHitPoints());
            assertEquals(18, TokenSupport.combatOf(caster).getCurrentMana());
        });
    }

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

    @Test public void closingBattleResumesCurrentTurnWithoutStartingItAgain() throws Exception {
        runBattle((battle, map, stage) -> {
            var tracker = (com.dnd.model.combat.InitiativeTracker) field(battle, "initiative");
            button(stage, "Next Turn ▸").fire();
            button(stage, "Next Turn ▸").fire();
            button(stage, "Next Turn ▸").fire();
            button(stage, "Next Turn ▸").fire();
            var position = tracker.position();
            assertEquals(2, position.round());
            assertNotEquals(tracker.order().getFirst(), tracker.current());
            TokenSupport.combatOf(tracker.current()).setMovementUsed(10);
            stage.close();

            var base = (BaseScene) field(battle, "owner");
            var session = (UiSession) field(battle, "uiSession");
            var reopened = new BattleMapWindow(base, session, "arena");
            reopened.show();
            var reopenedStage = Window.getWindows().stream()
                .filter(w -> w instanceof Stage s && s.getTitle().equals("Battle: Arena"))
                .map(w -> (Stage) w).findFirst().orElseThrow();
            try {
                var resumed = (com.dnd.model.combat.InitiativeTracker) field(reopened, "initiative");
                assertEquals(position, resumed.position());
                assertEquals(10, TokenSupport.combatOf(resumed.current()).getMovementUsed());
                button(reopenedStage, "End initiative").fire();
            } finally {
                reopenedStage.close();
            }
            assertNull(new CampaignRepositories(session.campaignRoot()).maps().getById("arena").getInitiativePosition());
        });
    }

    @Test public void tokenDetailPresentsOnlyTheUncroppedImageInAnOwnedWindow() throws Exception {
        runBattle((battle, map, stage) -> {
            com.dnd.ui.ReportUi.install();
            var png = temp.getRoot().toPath().resolve("rabbit.png");
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(94, 60,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png.toFile());
            var token = (MonsterToken) map.getCell(0, 0).getOccupants().getFirst();
            token.getMonster().setImagePath("rabbit.png");
            Canvas canvas = (Canvas) field(battle, "canvas");
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 12, 12));
            canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 12, 12));
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            var button = (Button) stage.getScene().lookup("#battle-present-portrait");
            assertNotNull(button);
            button.fire();
            var presentation = Window.getWindows().stream()
                .filter(w -> w instanceof Stage child && child.getOwner() == stage && child.isFullScreen())
                .map(Stage.class::cast).findFirst().orElseThrow();
            try {
                assertTrue(stage.isShowing());
                var content = (javafx.scene.layout.StackPane) presentation.getScene().getRoot();
                assertEquals(1, content.getChildren().size());
                var image = (javafx.scene.image.ImageView) content.getChildren().getFirst();
                assertTrue(image.isPreserveRatio());
                assertNull(image.getClip());
                assertEquals(94, image.getImage().getWidth(), 0);
                presentation.getScene().getOnKeyPressed().handle(new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ESCAPE,
                    false, false, false, false));
                assertFalse(presentation.isShowing());
                assertTrue(stage.isShowing());
            } finally {
                presentation.close();
            }
        });
    }

    @Test public void projectorMirrorsOnlyTheSelectedPlayerMovementGrid() throws Exception {
        runBattle((battle, map, stage) -> {
            var renderer = (com.dnd.ui.MapRenderer) field(battle, "renderer");
            var canvas = (Canvas) field(battle, "canvas");
            var player = map.getCell(2, 2).getOccupants().getFirst();
            TokenSupport.combatOf(player).setWalkSpeed(4 * CombatState.FEET_PER_SQUARE);
            TokenSupport.combatOf(player).setMovementUsed(2);
            double cell = renderer.getCellSize();
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 2.5 * cell, 2.5 * cell));

            var toggle = BattleMapWindow.class.getDeclaredMethod("toggleProjector");
            toggle.setAccessible(true);
            toggle.invoke(battle);
            var projected = (com.dnd.ui.MapRenderer.Decorations) field(field(battle, "projector"), "decorations");
            assertEquals(1, projected.reachable[2][1]);
            assertEquals(MovementCalculator.UNREACHABLE, projected.reachable[2][5]);
            assertNull(projected.selected);
            assertFalse(projected.showHealthBars);

            var render = BattleMapWindow.class.getDeclaredMethod("renderNow");
            render.setAccessible(true);
            var gm = (com.dnd.ui.MapRenderer.Decorations) field(battle, "decorations");
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 0.5 * cell, 0.5 * cell));
            render.invoke(battle);
            assertNotNull(gm.reachable);
            assertNull(projected.reachable);

            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 2.5 * cell, 2.5 * cell));
            render.invoke(battle);
            assertNotNull(projected.reachable);
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 5.5 * cell, 5.5 * cell));
            render.invoke(battle);
            assertNull(projected.reachable);
        });
    }

    @Test public void restTakenElsewhereWhileTheMapIsOpenIsPulledInNotOverwritten() throws Exception {
        runBattle((battle, map, stage) -> {
            var repos = (CampaignRepositories) field(battle, "repos");
            Canvas canvas = (Canvas) field(battle, "canvas");
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 108, 108));
            canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 108, 108));
            var state = TokenSupport.combatOf(map.getCell(2, 2).getOccupants().getFirst());
            state.getFeatureUses().put("second-wind", 1);
            state.getCooldowns().put("flame", 2);
            Spinner<?> amount = hpAmount(stage);
            amount.getEditor().setText("12");
            button(stage, "Damage").fire();
            assertEquals(8, repos.players().getById("player-0").getCurrentHitPoints());
            assertEquals(1, (int) repos.players().getById("player-0").getFeatureUses().get("second-wind"));

            // The GM panel / character screen rests the stored sheet while the map stays open.
            PlayerCharacter sheet = repos.players().getById("player-0");
            com.dnd.model.character.Progression.longRest(sheet, repos.items()::getById);
            repos.players().save(sheet);

            button(stage, "Next Turn ▸").fire();
            PlayerCharacter stored = repos.players().getById("player-0");
            assertEquals(20, stored.getCurrentHitPoints());
            assertTrue(stored.getFeatureUses().isEmpty());
            assertTrue(stored.getCooldowns().isEmpty());
            assertEquals(20, state.getCurrentHitPoints());
            assertTrue(state.getFeatureUses().isEmpty());

            // A later edit on the map builds on the rested sheet.
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 108, 108));
            canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 108, 108));
            amount = hpAmount(stage);
            amount.getEditor().setText("3");
            button(stage, "Damage").fire();
            assertEquals(17, repos.players().getById("player-0").getCurrentHitPoints());

            // A short rest elsewhere, then the map window closes (saveMap pushes every token).
            sheet = repos.players().getById("player-0");
            com.dnd.model.character.Progression.shortRest(sheet, null, 2, repos.items()::getById);
            repos.players().save(sheet);
            stage.hide();
            assertEquals(19, repos.players().getById("player-0").getCurrentHitPoints());
        });
    }

    @Test public void partyRestUsesTypedHpAndKeepsShortAndLongRestsDistinct() throws Exception {
        runBattle((battle, map, stage) -> {
            var repos = (CampaignRepositories) field(battle, "repos");
            Canvas canvas = (Canvas) field(battle, "canvas");
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 108, 108));
            canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 108, 108));
            var state = TokenSupport.combatOf(map.getCell(2, 2).getOccupants().getFirst());
            state.getFeatureUses().put("second-wind", 1);
            state.getFeatureUses().put("feat-long:rage", 1);
            Spinner<?> amount = hpAmount(stage);
            amount.getEditor().setText("15");
            button(stage, "Damage").fire();

            Platform.runLater(() -> {
                var pane = dialogPane(stage);
                // Typed without pressing Enter: the value must still count.
                ((Spinner<?>) pane.lookup(".spinner")).getEditor().setText("6");
                ((Button) pane.lookupButton(pane.getButtonTypes().stream()
                    .filter(t -> t.getText().equals("Short Rest")).findFirst().orElseThrow())).fire();
            });
            button(stage, "Rest...").fire();
            PlayerCharacter stored = repos.players().getById("player-0");
            assertEquals(11, state.getCurrentHitPoints());
            assertEquals(11, stored.getCurrentHitPoints());
            assertFalse(stored.getFeatureUses().containsKey("second-wind"));
            assertEquals(1, (int) stored.getFeatureUses().get("feat-long:rage"));
            var bars = (javafx.scene.Parent) field(battle, "detailBars");
            assertNotNull("the token panel stays open", bars);
            assertTrue("the open token panel shows the rested HP", bars.lookupAll(".bar-label").stream()
                .anyMatch(n -> n instanceof Label l && l.getText().startsWith("11 / 20")));

            Platform.runLater(() -> {
                var pane = dialogPane(stage);
                ((Button) pane.lookupButton(pane.getButtonTypes().stream()
                    .filter(t -> t.getText().equals("Long Rest")).findFirst().orElseThrow())).fire();
            });
            button(stage, "Rest...").fire();
            stored = repos.players().getById("player-0");
            assertEquals(20, stored.getCurrentHitPoints());
            assertTrue(stored.getFeatureUses().isEmpty());
        });
    }

    @Test public void tokenRestButtonsHealAndRechargeTheSheet() throws Exception {
        runBattle((battle, map, stage) -> {
            var repos = (CampaignRepositories) field(battle, "repos");
            Canvas canvas = (Canvas) field(battle, "canvas");
            canvas.getOnMousePressed().handle(mouse(MouseButton.PRIMARY, 108, 108));
            canvas.getOnMouseReleased().handle(mouse(MouseButton.PRIMARY, 108, 108));
            var state = TokenSupport.combatOf(map.getCell(2, 2).getOccupants().getFirst());
            state.getFeatureUses().put("action-surge", 1);
            state.getFeatureUses().put("feat-long:rage", 1);
            Spinner<?> amount = hpAmount(stage);
            amount.getEditor().setText("10");
            button(stage, "Damage").fire();

            Platform.runLater(() -> {
                var pane = dialogPane(stage);
                ((TextField) pane.lookup(".text-field")).setText("4");
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
            });
            button(stage, "Short Rest").fire();
            PlayerCharacter stored = repos.players().getById("player-0");
            assertEquals(14, stored.getCurrentHitPoints());
            assertEquals(java.util.Map.of("feat-long:rage", 1), stored.getFeatureUses());

            button(stage, "Long Rest").fire();
            stored = repos.players().getById("player-0");
            assertEquals(20, stored.getCurrentHitPoints());
            assertTrue(stored.getFeatureUses().isEmpty());
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
            pc.setStats(new com.dnd.model.character.stats.CoreStats(10, 10, 10, 10, 10, 10));
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

    private static Spinner<?> hpAmount(Stage stage) {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        return (Spinner<?>) stage.getScene().lookup("#hp-change-amount");
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
