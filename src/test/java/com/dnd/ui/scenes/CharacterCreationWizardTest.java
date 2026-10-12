package com.dnd.ui.scenes;

import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.magic.SpellSlot;
import com.dnd.model.magic.SpellcastingType;
import com.dnd.model.world.Dice;
import com.dnd.ui.scenes.CharacterCreationWizard.State;
import com.dnd.ui.scenes.CharacterCreationWizard.Step;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import static org.junit.Assert.*;

public class CharacterCreationWizardTest {

    private static CharacterClass cls(String id, int hitDie, SpellcastingType type, List<String> subclasses) {
        CharacterClass c = new CharacterClass(id, id, "desc", new Dice("d" + hitDie, "d" + hitDie, hitDie), List.of(), Map.of());
        c.setSpellcasting(type);
        c.setSubclasses(subclasses);
        return c;
    }

    private static State complete(CharacterClass c) {
        State s = new State();
        s.race = new CharacterRace("human", "Human", "", Map.of(), 30);
        s.cls = c;
        s.subclass = c.getSubclasses().isEmpty() ? null : c.getSubclasses().get(0);
        s.name = "Aria Swift";
        return s;
    }

    @Test
    public void subclassDescriptionExplainsTheSubclassAndEachFeature() {
        CharacterClass wizard = new CharacterClass();
        wizard.setSubclasses(new java.util.ArrayList<>(List.of("School of Evocation")));
        wizard.setSubclassDescriptions(new java.util.LinkedHashMap<>(Map.of("School of Evocation", "Battle mages.")));
        wizard.setFeatures(new java.util.ArrayList<>(List.of(
            new com.dnd.model.character.ClassFeature(6, "Potent Cantrip", "Cantrips still hurt on a save.", "School of Evocation"),
            new com.dnd.model.character.ClassFeature(2, "Sculpt Spells", "Allies are spared.", "School of Evocation"),
            new com.dnd.model.character.ClassFeature(2, "Portent", "Roll two d20s.", "School of Divination"))));

        String text = CharacterCreationWizard.describeSubclass(wizard, "school of evocation");

        assertTrue(text, text.startsWith("Battle mages."));
        assertTrue(text, text.contains("Sculpt Spells") && text.contains("Allies are spared."));
        assertTrue(text, text.indexOf("Sculpt Spells") < text.indexOf("Potent Cantrip"));
        assertFalse(text, text.contains("Portent"));
    }

    @Test
    public void cannotAdvanceWithoutRaceClassOrName() {
        State s = new State();
        assertFalse(CharacterCreationWizard.canAdvance(Step.RACE, s));
        assertFalse(CharacterCreationWizard.canAdvance(Step.CLASS, s));
        assertFalse(CharacterCreationWizard.canAdvance(Step.IDENTITY, s));
        s.race = new CharacterRace("elf", "Elf", "", Map.of(), 30);
        s.cls = cls("fighter", 10, SpellcastingType.NONE, List.of());
        assertTrue(CharacterCreationWizard.canAdvance(Step.RACE, s));
        assertTrue(CharacterCreationWizard.canAdvance(Step.CLASS, s));
        s.name = "   ";
        assertFalse(CharacterCreationWizard.canAdvance(Step.IDENTITY, s));
        assertFalse(CharacterCreationWizard.canAdvance(Step.SUMMARY, s));
        s.name = "Bob";
        assertTrue(CharacterCreationWizard.canAdvance(Step.IDENTITY, s));
        assertTrue(CharacterCreationWizard.canAdvance(Step.SUMMARY, s));
        assertTrue(CharacterCreationWizard.canAdvance(Step.SPELLS, s));
        assertTrue(CharacterCreationWizard.canAdvance(Step.ITEMS, s));
    }

    @Test
    public void subclassRequiredOnlyWhenClassHasSubclasses() {
        State s = new State();
        s.cls = cls("fighter", 10, SpellcastingType.NONE, List.of());
        assertTrue(CharacterCreationWizard.canAdvance(Step.SUBCLASS, s));
        s.cls = cls("barbarian", 12, SpellcastingType.NONE, List.of("Path of the Berserker"));
        assertFalse(CharacterCreationWizard.canAdvance(Step.SUBCLASS, s));
        s.subclass = "Path of the Berserker";
        assertTrue(CharacterCreationWizard.canAdvance(Step.SUBCLASS, s));
    }

    @Test
    public void buildsFullCasterWithHitPointsAndSlots() {
        State s = complete(cls("wizard", 6, SpellcastingType.FULL, List.of("School of Evocation")));
        s.level = 3;
        s.scores = new int[]{8, 14, 14, 15, 12, 10};
        s.spellIds.add("magic-missile");
        s.itemIds.add("dagger");
        PlayerCharacter pc = CharacterCreationWizard.buildCharacter(s, List.of());

        // CON 14 -> +2: level 1 = 6+2, then 2 levels of (6/2+1)+2 = 6 each.
        assertEquals(20, pc.getMaxHitPoints());
        assertEquals(20, pc.getCurrentHitPoints());
        assertEquals(3, pc.getLevel());
        assertEquals(0, pc.getXp());
        assertEquals("wizard", pc.getClassId());
        assertEquals("human", pc.getRaceId());
        assertEquals("School of Evocation", pc.getSubclass());
        assertEquals(15, pc.getStats().getIntelligence());
        assertEquals(1, pc.getSpells().size());
        assertEquals("magic-missile", pc.getSpells().get(0).getSpellId());
        assertEquals(1, pc.getItems().size());
        assertEquals(100, pc.getItems().get(0).getCondition().getDurability());
        assertFalse(pc.getItems().get(0).isEquipped());

        int first = 0, second = 0;
        for (SpellSlot slot : pc.getSpellSlots()) {
            if (slot.getLevel() == 1) first = slot.getMax();
            if (slot.getLevel() == 2) second = slot.getMax();
        }
        assertEquals(4, first);
        assertEquals(2, second);
        assertEquals(0, pc.getMaxMana());
    }

    @Test
    public void nonCasterHasNoSlotsAndMinimumHitPoints() {
        State s = complete(cls("fighter", 10, SpellcastingType.NONE, List.of()));
        s.scores = new int[]{15, 14, 3, 12, 10, 8};
        s.spellIds.add("ignored");
        PlayerCharacter pc = CharacterCreationWizard.buildCharacter(s, List.of());
        assertEquals(6, pc.getMaxHitPoints()); // 10 - 4
        assertTrue(pc.getSpellSlots().isEmpty());
        assertTrue(pc.getSpells().isEmpty());
        assertNull(pc.getSubclass());
        // d6 with CON 3 (-4): level 1 = 2, each later level clamps to the 1 HP minimum.
        assertEquals(3, CharacterCreationWizard.maxHitPoints(6, 3, 2));
    }

    @Test
    public void generatesUniqueIds() {
        assertEquals("player-aria-swift", CharacterCreationWizard.uniqueId("Aria Swift", List.of()));
        assertEquals("player-aria-swift-2",
            CharacterCreationWizard.uniqueId("Aria Swift", List.of("player-aria-swift")));
        assertEquals("player-aria-swift-3",
            CharacterCreationWizard.uniqueId("  aria   SWIFT! ", List.of("player-aria-swift", "player-aria-swift-2")));
        assertEquals("player-character", CharacterCreationWizard.uniqueId("???", List.of()));

        PlayerCharacter existing = new PlayerCharacter();
        existing.setId("player-aria-swift");
        PlayerCharacter pc = CharacterCreationWizard.buildCharacter(
            complete(cls("fighter", 10, SpellcastingType.NONE, List.of())), List.of(existing));
        assertEquals("player-aria-swift-2", pc.getId());
    }

    @Test(expected = IllegalStateException.class)
    public void buildRejectsIncompleteState() {
        CharacterCreationWizard.buildCharacter(new State(), List.of());
    }

    @Test
    public void sortsCaseInsensitively() {
        assertEquals(List.of("apple", "Banana", "cherry"),
            CharacterCreationWizard.sortedByName(List.of("cherry", "Banana", "apple"), x -> x));
    }

    @Test
    public void wizardSceneShowsStepIndicatorAndDisablesNextUntilRaceChosen() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            ready.countDown();
        }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        Path root = Files.createTempDirectory("dnd-wizard-test");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                CharacterCreationWizard wizard = new CharacterCreationWizard(null, new com.dnd.data.CampaignRepositories(root));
                Scene scene = wizard.build();
                assertNotNull(find(scene.getRoot(), n -> n instanceof Label l && l.getText().startsWith("Step 1 of 8")));
                Node next = find(scene.getRoot(), n -> n instanceof Button b && b.getText().startsWith("Next"));
                assertNotNull(next);
                assertTrue(next.isDisabled());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static Node find(Node node, java.util.function.Predicate<Node> p) {
        if (p.test(node)) return node;
        if (node instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            Node r = find(sp.getContent(), p);
            if (r != null) return r;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node r = find(child, p);
                if (r != null) return r;
            }
        }
        return null;
    }
}