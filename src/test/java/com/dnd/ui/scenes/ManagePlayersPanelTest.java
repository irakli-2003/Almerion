package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.SessionRosterStore;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.creature.Monster;
import com.dnd.model.session.TrackedCreature;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class ManagePlayersPanelTest {

    @BeforeClass
    public static void startToolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            ready.countDown();
        }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    private interface Work { void run() throws Exception; }

    private static void onFx(Work work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { work.run(); } catch (Throwable t) { failure.set(t); } finally { done.countDown(); }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test
    public void listsPlayersAndSessionCreaturesWithQuickHpAndDetailNavigation() throws Exception {
        Path root = Files.createTempDirectory("dnd-manage-players");
        CampaignRepositories repos = new CampaignRepositories(root);
        PlayerCharacter aria = new PlayerCharacter();
        aria.setId("aria");
        aria.setName("Aria");
        aria.setMaxHitPoints(20);
        aria.setCurrentHitPoints(20);
        repos.players().save(aria);
        Monster goblin = new Monster();
        goblin.setId("gob");
        goblin.setName("Goblin");
        goblin.setMaxHitPoints(7);
        goblin.setArmorClass(13);
        repos.monsters().save(goblin);
        Path session = root.resolve("Session 1.md");
        Files.writeString(session, "text");

        onFx(() -> {
            ManagePlayersPanel panel = new ManagePlayersPanel(new CharacterCreationWizard(null, repos), repos, session, s -> { });
            panel.showList();
            assertNotNull("player card is listed", find(panel.node(), n -> n instanceof Label l && "Aria".equals(l.getText())));

            for (int i = 0; i < 2; i++) {
                panel.addCreature(new TrackedCreature(TrackedCreature.Kind.MONSTER, "gob", "Goblin", 7, 0, 13, null));
            }
            assertEquals(List.of("Goblin", "Goblin 2"), panel.creatures().stream().map(TrackedCreature::getName).toList());

            // Default amount is 5: the second goblin goes from 7 to 2, the first is untouched.
            button(card(panel, "Goblin 2"), "−").fire();
            List<TrackedCreature> saved = SessionRosterStore.load(session);
            assertEquals(7, saved.stream().filter(c -> c.getName().equals("Goblin")).findFirst().orElseThrow().getCurrentHitPoints());
            assertEquals(2, saved.stream().filter(c -> c.getName().equals("Goblin 2")).findFirst().orElseThrow().getCurrentHitPoints());

            button(card(panel, "Aria"), "+").fire();
            assertEquals("healing is capped at max HP", 20, repos.players().getById("aria").getCurrentHitPoints());
            button(card(panel, "Aria"), "−").fire();
            assertEquals(15, repos.players().getById("aria").getCurrentHitPoints());

            panel.showCreature(panel.creatures().get(1));
            Button back = (Button) find(panel.node(), n -> n instanceof Button b && b.getText().startsWith("← Back"));
            assertNotNull("detail view has a back button", back);
            back.fire();
            assertNotNull("back returns to the card list",
                find(panel.node(), n -> n instanceof Label l && "Party & Creatures".equals(l.getText())));
        });
    }

    @Test
    public void inlinePickersAddFromCatalogAndCreateNewEntries() throws Exception {
        Path root = Files.createTempDirectory("dnd-manage-pickers");
        CampaignRepositories repos = new CampaignRepositories(root);
        PlayerCharacter aria = new PlayerCharacter();
        aria.setId("aria");
        aria.setName("Aria");
        aria.setMaxHitPoints(20);
        aria.setCurrentHitPoints(20);
        repos.players().save(aria);
        repos.items().save(ManagePlayersPanel.newItem("rope", "Rope", "gear", "50 ft"));
        com.dnd.model.combat.Effect burn = new com.dnd.model.combat.Effect("burn", "Burning", "", true, false, 3, 0);
        burn.setDurationRounds(2);
        repos.effects().save(burn);
        Path session = root.resolve("Session 1.md");
        Files.writeString(session, "text");

        onFx(() -> {
            ManagePlayersPanel panel = new ManagePlayersPanel(new CharacterCreationWizard(null, repos), repos, session, s -> { });
            panel.showPlayer(repos.players().getById("aria"));

            // Item: open the inline picker, search, Enter adds the first match; no dialog involved.
            button(panel.node(), "+ Add Item").fire();
            TextField itemSearch = (TextField) find(panel.node(), n -> n instanceof TextField t && t.getPromptText() != null && t.getPromptText().startsWith("Search items"));
            assertNotNull("item picker opens inline", itemSearch);
            itemSearch.setText("rop");
            itemSearch.getOnAction().handle(null);
            assertEquals(List.of("rope"), repos.players().getById("aria").getItems().stream().map(i -> i.getItemId()).toList());

            // Create a brand-new item from the picker and it is given right away.
            button(panel.node(), "✚ New item...").fire();
            TextField name = (TextField) find(panel.node(), n -> n instanceof TextField t && "Name".equals(t.getPromptText()));
            name.setText("Silver Bullet");
            button(panel.node(), "Create & Add").fire();
            assertNotNull("new item saved to catalog", repos.items().getById("item-silver-bullet"));
            assertTrue(repos.players().getById("aria").getItems().stream().anyMatch(i -> "item-silver-bullet".equals(i.getItemId())));

            // Effect: picker applies the catalog default duration.
            button(panel.node(), "+ Add Effect").fire();
            TextField effectSearch = (TextField) find(panel.node(), n -> n instanceof TextField t && t.getPromptText() != null && t.getPromptText().startsWith("Search effects"));
            effectSearch.setText("burn");
            effectSearch.getOnAction().handle(null);
            assertEquals(2, repos.players().getById("aria").getActiveEffects().get(0).getRemainingRounds());

            button(panel.node(), "✚ New effect...").fire();
            TextField effectName = (TextField) find(panel.node(), n -> n instanceof TextField t && t.getPromptText() != null && t.getPromptText().startsWith("Name, e.g."));
            effectName.setText("Blessed");
            button(panel.node(), "Create & Apply").fire();
            assertNotNull(repos.effects().getById("effect-blessed"));
            assertEquals(2, repos.players().getById("aria").getActiveEffects().size());

            // Award XP inline: everyone ticked by default.
            panel.showAwardXp();
            int before = repos.players().getById("aria").getXp();
            button(panel.node(), "Award").fire();
            assertEquals(before + 100, repos.players().getById("aria").getXp());
        });
    }

    @Test
    public void addPickerAddsCreaturesWithoutClosing() throws Exception {
        Path root = Files.createTempDirectory("dnd-manage-add");
        CampaignRepositories repos = new CampaignRepositories(root);
        Monster goblin = new Monster();
        goblin.setId("gob");
        goblin.setName("Goblin");
        goblin.setMaxHitPoints(7);
        repos.monsters().save(goblin);
        Path session = root.resolve("Session 1.md");
        Files.writeString(session, "text");

        onFx(() -> {
            ManagePlayersPanel panel = new ManagePlayersPanel(new CharacterCreationWizard(null, repos), repos, session, s -> { });
            panel.showList();
            button(panel.node(), "+ Add").fire();
            TextField search = (TextField) find(panel.node(), n -> n instanceof TextField t && t.getPromptText() != null && t.getPromptText().startsWith("Search monsters"));
            search.setText("gob");
            search.getOnAction().handle(null);
            search.getOnAction().handle(null);
            assertEquals(List.of("Goblin", "Goblin 2"), panel.creatures().stream().map(TrackedCreature::getName).toList());
            assertNotNull("picker stays open", find(panel.node(), n -> n == search));
            card(panel, "Goblin 2");
        });
    }

    @Test public void selectedPlayerShowsStatsAndFullSpellDescriptions() throws Exception {
        Path root = Files.createTempDirectory("dnd-manage-sheet");
        var repos = new CampaignRepositories(root);
        var pc = new PlayerCharacter();
        pc.setId("hero"); pc.setName("Hero");
        var spell = new com.dnd.model.magic.Spell();
        spell.setId("light"); spell.setName("Light"); spell.setDescription("A bright light shines for one hour.");
        repos.spells().save(spell);
        var learned = new PlayerCharacter.PlayerSpell(); learned.setSpellId("light");
        pc.setSpells(new ArrayList<>(List.of(learned)));
        repos.players().save(pc);
        onFx(() -> {
            var panel = new ManagePlayersPanel(new CharacterCreationWizard(null, repos), repos, root.resolve("Session.md"), s -> { });
            panel.showPlayer(pc);
            var full = (javafx.scene.control.TitledPane) find(panel.node(), n -> n instanceof javafx.scene.control.TitledPane p
                && "Full character sheet".equals(p.getText()));
            assertNotNull(full);
            assertNotNull(find(full.getContent(), n -> n instanceof Label l && "Abilities".equals(l.getText())));
            var details = (javafx.scene.control.TitledPane) find(full.getContent(), n -> n instanceof javafx.scene.control.TitledPane p
                && p.getText().startsWith("Light"));
            assertNotNull(details);
            assertTrue(((javafx.scene.control.TextArea) details.getContent()).getText().contains("one hour"));
            assertNotNull(details.getTooltip());
        });
    }

    @Test public void hpButtonsUseTheFreshCharacterAfterAnUpgradeWasApproved() throws Exception {
        Path root = Files.createTempDirectory("dnd-manage-upgraded");
        var repos = new CampaignRepositories(root);
        var cls = new com.dnd.model.character.CharacterClass(); cls.setId("fighter");
        repos.classes().save(cls);
        var pc = new PlayerCharacter("hero", "Hero", "fighter", "human", 5,
            new com.dnd.model.character.stats.CoreStats(10, 10, 10, 10, 10, 10), new ArrayList<>(), new ArrayList<>());
        pc.setMaxHitPoints(40); pc.setCurrentHitPoints(10); pc.setXp(100); repos.players().save(pc);
        onFx(() -> {
            var panel = new ManagePlayersPanel(new CharacterCreationWizard(null, repos), repos, root.resolve("Session.md"), s -> { });
            panel.showList();
            var service = com.dnd.model.character.UpgradeRequests.at(root);
            var options = service.options("hero", null);
            String node = options.choices().stream().filter(c -> c.blocked().isBlank()).findFirst().orElseThrow().id();
            String proposal = com.dnd.data.JsonMappers.create().writeValueAsString(
                new com.dnd.model.character.UpgradeRequests.Proposal(options.revision(), java.util.Map.of("strength", 2), node, java.util.Map.of()));
            String id = java.util.UUID.randomUUID().toString();
            service.execute("pc:hero", new com.dnd.chat.SessionChat.Command(id, "upgrade-submit", null, List.of(), "", proposal));
            service.execute("gm", new com.dnd.chat.SessionChat.Command(java.util.UUID.randomUUID().toString(),
                "upgrade-accept", id, List.of(), "", proposal));
            int hp = repos.players().getById("hero").getCurrentHitPoints();
            button(card(panel, "Hero"), "−").fire();
            assertEquals(6, repos.players().getById("hero").getLevel());
            assertEquals(hp - 5, repos.players().getById("hero").getCurrentHitPoints());
        });
    }

    private static Node card(ManagePlayersPanel panel, String name) {
        Node card = find(panel.node(), n -> n instanceof HBox h && h.getStyleClass().contains("creature-card")
            && find(h, m -> m instanceof Label l && name.equals(l.getText())) != null);
        assertNotNull("card for " + name, card);
        return card;
    }

    private static Button button(Node within, String text) {
        Node b = find(within, n -> n instanceof Button x && text.equals(x.getText()));
        assertNotNull("button " + text, b);
        return (Button) b;
    }

    private static Node find(Node node, Predicate<Node> test) {
        if (test.test(node)) return node;
        List<Node> children = new ArrayList<>();
        if (node instanceof ScrollPane sp && sp.getContent() != null) children.add(sp.getContent());
        if (node instanceof Parent p) children.addAll(p.getChildrenUnmodifiable());
        for (Node child : children) {
            Node found = find(child, test);
            if (found != null) return found;
        }
        return null;
    }
}
