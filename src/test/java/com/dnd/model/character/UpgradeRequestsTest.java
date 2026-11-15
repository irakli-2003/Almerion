package com.dnd.model.character;

import com.dnd.chat.SessionChat;
import com.dnd.data.*;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.magic.Spell;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class UpgradeRequestsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path root;
    private CampaignRepositories repos;
    private UpgradeRequests service;
    private CharacterClass cls;

    @Before public void setup() throws Exception {
        root = temp.newFolder().toPath();
        repos = new CampaignRepositories(root);
        cls = new CharacterClass();
        cls.setId("fighter"); cls.setName("Fighter");
        UpgradeTree tree = new UpgradeTree();
        var branch = new UpgradeTree.Branch(); branch.setId("defense"); branch.setName("Defense");
        tree.getBranches().add(branch);
        var node = new UpgradeTree.Node();
        node.setId("guard"); node.setName("Guard"); node.setBranchId("defense");
        node.setParentId(UpgradeRules.ROOT);
        node.setDescription("Protect an adjacent ally.");
        node.getReward().setHitPoints(3);
        tree.getNodes().add(node);
        cls.setUpgradeTree(tree);
        repos.classes().save(cls);
        PlayerCharacter pc = new PlayerCharacter("hero", "Hero", "fighter", "human", 5,
            new CoreStats(18, 19, 15, 10, 10, 10), new ArrayList<>(), new ArrayList<>());
        pc.setMaxHitPoints(44); pc.setCurrentHitPoints(7); pc.setXp(150);
        repos.players().save(pc);
        service = new UpgradeRequests(root);
    }

    private SessionChat.Command command(String op, String target, UpgradeRequests.Proposal proposal) throws Exception {
        return new SessionChat.Command(UUID.randomUUID().toString(), op, target, List.of(), "",
            proposal == null ? "" : JsonMappers.create().writeValueAsString(proposal));
    }
    private UpgradeRequests.Proposal proposal(Map<String, Integer> points) {
        var options = service.options("hero", null);
        return new UpgradeRequests.Proposal(options.revision(), points, "guard", Map.of());
    }
    private String submit(UpgradeRequests.Proposal proposal) throws Exception {
        var command = command("upgrade-submit", null, proposal);
        service.execute("pc:hero", command);
        return command.id();
    }

    @Test public void previewSendAndDeclineNeverChangeTheCharacterAndSurviveRestart() throws Exception {
        var original = JsonMappers.create().valueToTree(repos.players().getById("hero"));
        var proposal = proposal(Map.of("strength", 2));
        var submit = command("upgrade-submit", null, proposal);
        service.execute("pc:hero", submit);
        new UpgradeRequests(root).execute("pc:hero", submit);
        assertEquals(1, service.inbox("gm").requests().size());
        assertEquals("pending", service.inbox("gm").requests().getFirst().status());
        service.execute("gm", command("upgrade-decline", submit.id(), null));
        assertEquals("declined", new UpgradeRequests(root).inbox("pc:hero").requests().getFirst().status());
        assertEquals(original, JsonMappers.create().valueToTree(repos.players().getById("hero")));
    }

    @Test public void gmCanEditChoicesAndApprovalAppliesExactlyOnceWithoutFullHealing() throws Exception {
        PlayerCharacter staleEditor = repos.players().getById("hero");
        var original = proposal(Map.of("strength", 2));
        String id = submit(original);
        var edited = new UpgradeRequests.Proposal(original.revision(), Map.of("constitution", 1, "dexterity", 1), "guard", Map.of());
        var approve = command("upgrade-accept", id, edited);
        service.execute("gm", approve);
        PlayerCharacter upgraded = repos.players().getById("hero");
        assertEquals(6, upgraded.getLevel());
        assertEquals(0, upgraded.getXp());
        assertEquals(18, upgraded.getStats().getStrength());
        assertEquals(20, upgraded.getStats().getDexterity());
        assertEquals(16, upgraded.getStats().getConstitution());
        assertEquals(37, upgraded.getMaxHitPoints() - upgraded.getCurrentHitPoints());
        int expectedGain = Math.max(1, Progression.averageHitDie(Progression.plan(staleEditor, cls).hitDieSides) + 3) + 5 + 3;
        assertEquals(44 + expectedGain, upgraded.getMaxHitPoints());
        assertEquals(id, upgraded.getUpgrades().getUnlocks().getLast().getSourceRequestId());
        new UpgradeRequests(root).execute("gm", approve);
        assertEquals(JsonMappers.create().valueToTree(upgraded), JsonMappers.create().valueToTree(repos.players().getById("hero")));
        assertThrows(IllegalStateException.class, () -> repos.players().save(staleEditor));
    }

    @Test public void standardAsiTimingIncludesFighterAndRogueExceptions() {
        var pc = repos.players().getById("hero");
        for (String id : List.of("fighter", "rogue", "wizard")) {
            cls.setId(id);
            for (int level = 1; level < 20; level++) {
                pc.setLevel(level);
                boolean expected = Set.of(4, 8, 12, 16, 19).contains(level + 1)
                    || id.equals("fighter") && Set.of(6, 14).contains(level + 1)
                    || id.equals("rogue") && level + 1 == 10;
                assertEquals(id + " -> " + (level + 1), expected, Progression.plan(pc, cls).abilityScoreImprovement);
            }
        }
    }

    @Test public void overspendingNegativeUnknownAndOverCapPointsAreRejected() {
        for (var points : List.of(Map.of("strength", 3), Map.of("strength", -1, "constitution", 3), Map.of("cheat", 2),
                Map.of("dexterity", 2), Map.<String, Integer>of())) {
            assertThrows(IllegalArgumentException.class, () -> service.options("hero", proposal(points)));
        }
        assertEquals(150, repos.players().getById("hero").getXp());
        var pc = repos.players().getById("hero"); pc.setLevel(6); repos.players().save(pc);
        assertEquals(0, service.options("hero", null).budget());
        assertThrows(IllegalArgumentException.class, () -> service.options("hero", proposal(Map.of("strength", 2))));
    }

    @Test public void staleClassOrCharacterCannotBeApprovedAndDeclineStillPreservesXp() throws Exception {
        var proposal = proposal(Map.of("strength", 2));
        String id = submit(proposal);
        cls.getUpgradeTree().getNodes().getFirst().getReward().setHitPoints(9);
        repos.classes().save(cls);
        assertThrows(IllegalArgumentException.class, () -> service.execute("gm", command("upgrade-accept", id, proposal)));
        assertEquals(5, repos.players().getById("hero").getLevel());
        service.execute("gm", command("upgrade-decline", id, null));
        assertEquals(150, repos.players().getById("hero").getXp());
    }

    @Test public void combatAndXpChangesAreKeptButConcurrentSaveIsRejected() throws Exception {
        var proposal = proposal(Map.of("strength", 2));
        String id = submit(proposal);
        PlayerCharacter before = repos.players().getById("hero");
        PlayerCharacter current = repos.players().getById("hero");
        current.setCurrentHitPoints(1); current.setXp(200); repos.players().save(current);
        assertThrows(IllegalStateException.class, () -> repos.players().updateIfUnchanged(before, before));
        service.execute("gm", command("upgrade-accept", id, proposal));
        PlayerCharacter approved = repos.players().getById("hero");
        assertEquals(43, approved.getMaxHitPoints() - approved.getCurrentHitPoints());
    }

    @Test public void playersCannotDecideRequestsOrUseAnotherCharactersIdentity() throws Exception {
        var proposal = proposal(Map.of("strength", 2));
        String id = submit(proposal);
        assertThrows(SecurityException.class, () -> service.execute("pc:hero", command("upgrade-accept", id, proposal)));
        assertThrows(SecurityException.class, () -> service.execute("pc:hero", command("upgrade-decline", id, null)));
        var options = service.execute("pc:hero", command("upgrade-options", "someone-else", null)).options();
        assertEquals(proposal.revision(), options.revision());
        assertTrue(service.inbox("pc:someone-else").requests().isEmpty());
        assertThrows(IllegalStateException.class, () -> submit(proposal));
    }

    @Test public void approvalReceiptRecoversAnInterruptedCommit() throws Exception {
        var proposal = proposal(Map.of("strength", 2));
        String id = submit(proposal);
        var approve = command("upgrade-accept", id, proposal);
        service.execute("gm", approve);
        PlayerCharacter expected = repos.players().getById("hero");
        Path file = root.resolve("upgrade-requests.json");
        var json = JsonMappers.create().readTree(file.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.get(0)).put("status", "applying");
        JsonMappers.create().writeValue(file.toFile(), json);
        var restarted = new UpgradeRequests(root);
        assertEquals("accepted", restarted.inbox("gm").requests().getFirst().status());
        restarted.execute("gm", approve);
        assertEquals(JsonMappers.create().valueToTree(expected), JsonMappers.create().valueToTree(repos.players().getById("hero")));
    }

    @Test public void knownSpellNeedsOnlyTheGmDefinedAlternativeAndDescriptionsAreIncluded() throws Exception {
        for (String id : List.of("known", "alternative", "unauthorized")) {
            Spell spell = new Spell(); spell.setId(id); spell.setName(id); spell.setDescription("Detailed " + id);
            repos.spells().save(spell);
        }
        var reward = cls.getUpgradeTree().getNodes().getFirst().getReward();
        reward.setSpellIds(List.of("known"));
        var alternative = new UpgradeTree.SpellAlternatives();
        alternative.setSpellId("known"); alternative.setAlternativeSpellIds(List.of("alternative"));
        reward.setSpellAlternatives(List.of(alternative)); repos.classes().save(cls);
        var pc = repos.players().getById("hero");
        pc.setUpgrades(new UpgradeProgress());
        var known = new PlayerCharacter.PlayerSpell(); known.setSpellId("known");
        pc.setSpells(new ArrayList<>(List.of(known))); repos.players().save(pc);
        var options = service.options("hero", null);
        var choice = options.choices().getFirst();
        assertTrue(choice.description().contains("Detailed known"));
        assertTrue(choice.spells().getFirst().options().getFirst().description().contains("Detailed alternative"));
        var draft = proposal(Map.of("strength", 2));
        assertThrows(IllegalArgumentException.class, () -> submit(draft));
        assertThrows(IllegalArgumentException.class, () -> submit(new UpgradeRequests.Proposal(draft.revision(),
            draft.points(), draft.node(), Map.of("known", "unauthorized"))));
        var valid = new UpgradeRequests.Proposal(draft.revision(), draft.points(), draft.node(), Map.of("known", "alternative"));
        String id = submit(valid);
        service.execute("gm", command("upgrade-accept", id, valid));
        assertTrue(repos.players().getById("hero").getSpells().stream().anyMatch(s -> s.getSpellId().equals("alternative")));
    }

    @Test public void encryptedRelayCarriesProposalsAndDecisionsWithoutExposingTheirContents() throws Exception {
        var store = new com.dnd.cloud.LocalCloudStore();
        SessionChat chat = SessionChat.acquire(root);
        try (var relay = new com.dnd.chat.SessionChatRelay(store, "session", chat, (uid, request) -> {
            if (!"owner".equals(uid) || !"hero".equals(request.character())) throw new SecurityException("Wrong character.");
            return "pc:hero";
        })) {
            relay.start();
            try (var client = new com.dnd.chat.SessionChatRelay.Client(store, "session", "owner", "hero", null)) {
                var options = client.execute(command("upgrade-options", null, null)).upgrades().options();
                var proposal = new UpgradeRequests.Proposal(options.revision(), Map.of("strength", 2), "guard", Map.of());
                var submit = command("upgrade-submit", null, proposal);
                assertEquals("pending", client.execute(submit).upgrades().requests().getFirst().status());
                assertThrows(IllegalStateException.class, () -> client.execute(command("upgrade-accept", submit.id(), proposal)));
                chat.execute("gm", command("upgrade-accept", submit.id(), proposal));
                assertEquals("accepted", client.execute(SessionChat.Command.poll(null)).upgrades().requests().getFirst().status());
                assertFalse(store.get("session").toString().contains("Protect an adjacent ally."));
            }
        } finally { SessionChat.release(root, chat); }
    }

    @Test public void untrustedPayloadsCannotSmuggleArbitraryHpOrFractionalAbilityPoints() {
        for (String text : List.of("{\"revision\":\"x\",\"points\":{},\"node\":\"guard\",\"spells\":{},\"hp\":200}",
                "{\"revision\":\"x\",\"points\":{\"strength\":1.5},\"node\":\"guard\",\"spells\":{}}")) {
            var command = new SessionChat.Command(UUID.randomUUID().toString(), "upgrade-submit", null, List.of(), "", text);
            assertThrows(IllegalArgumentException.class, () -> service.execute("pc:hero", command));
        }
        assertEquals(44, repos.players().getById("hero").getMaxHitPoints());
    }
}
