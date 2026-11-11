package com.dnd.model.character;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.JsonMappers;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.magic.Spell;
import com.dnd.model.rules.FeatureRules;
import com.dnd.model.rules.SheetValues;
import com.dnd.model.world.map.PlayerToken;
import com.dnd.model.world.map.TokenSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

public class UpgradeRulesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private CharacterClass cls() {
        CharacterClass cls = new CharacterClass();
        cls.setId("barbarian"); cls.setName("Barbarian");
        cls.setFeatures(List.of(new ClassFeature(1, "Rage", "Existing rage."),
            new ClassFeature(2, "Reckless Attack", "Existing reckless attack.")));
        UpgradeTree tree = new UpgradeTree();
        for (String id : List.of("berserker", "iron-fist")) {
            var branch = new UpgradeTree.Branch(); branch.setId(id); branch.setName(id);
            tree.getBranches().add(branch);
        }
        cls.setUpgradeTree(tree);
        return cls;
    }

    private PlayerCharacter pc(int level) {
        PlayerCharacter pc = new PlayerCharacter("hero", "Hero", "barbarian", "human", level,
            new CoreStats(16, 12, 14, 10, 10, 10), new ArrayList<>(), new ArrayList<>());
        pc.setMaxHitPoints(30); pc.setCurrentHitPoints(20);
        return pc;
    }

    private UpgradeTree.Node node(CharacterClass cls, String id, String branch, String... prerequisites) {
        var node = new UpgradeTree.Node();
        node.setId(id); node.setName(id); node.setBranchId(branch);
        node.setParentId(prerequisites.length == 0 ? UpgradeRules.ROOT : prerequisites[0]);
        node.setPrerequisites(new ArrayList<>(Arrays.asList(prerequisites).subList(Math.min(1, prerequisites.length), prerequisites.length)));
        cls.getUpgradeTree().getNodes().add(node);
        return node;
    }

    private void earn(PlayerCharacter pc, CharacterClass cls) {
        UpgradeRules.initialize(pc, cls);
        pc.setXp(100);
        Progression.apply(pc, Progression.plan(pc, cls));
    }

    private void rejected(Runnable operation, String message) {
        try { operation.run(); fail("Expected rejection: " + message); }
        catch (IllegalArgumentException ex) { assertTrue(ex.getMessage(), ex.getMessage().contains(message)); }
    }

    @Test public void existingLevelsAreConsumedByRealTrainingWithoutExtraRewards() {
        var cls = cls();
        var node = node(cls, "reckless", "berserker"); node.setName("Reckless Attack");
        node.getReward().setHitPoints(5);
        var pc = pc(5);
        assertTrue(UpgradeRules.initialize(pc, cls));
        assertEquals(4, pc.getUpgrades().getEarned());
        assertEquals(4, pc.getUpgrades().getUnlocks().size());
        assertEquals(0, UpgradeRules.available(pc));
        assertTrue(UpgradeRules.selected(pc.getUpgrades(), cls.getId(), "reckless"));
        assertTrue(pc.getUpgrades().getUnlocks().getFirst().isHistorical());
        assertEquals(30, pc.getMaxHitPoints());
        assertEquals(0, UpgradeRules.bonus(pc, UpgradeTree.Reward::getHitPoints));
        assertFalse(UpgradeRules.initialize(pc, cls));
        earn(pc, cls);
        assertEquals(1, UpgradeRules.available(pc));
    }

    @Test public void allStarterClassesHaveFourTiersEightLeavesAndFeatsOnEveryChoice() {
        for (String id : List.of("barbarian", "wizard", "ranger", "bard", "cleric", "rogue", "fighter",
                "artificer", "druid", "monk", "paladin", "sorcerer", "warlock", "custom")) {
            var cls = new CharacterClass(); cls.setId(id);
            var tree = cls.getUpgradeTree();
            UpgradeRules.validateBranching(tree);
            assertEquals(14, tree.getNodes().size());
            Map<String, Integer> children = new HashMap<>();
            for (var n : tree.getNodes()) {
                children.merge(n.getParentId(), 1, Integer::sum);
                assertFalse(n.getReward().getFeatures().isEmpty());
                assertFalse(n.getReward().getFeatures().getFirst().getDescription().isBlank());
                assertEquals(tree.getNodes().stream().filter(child -> n.getId().equals(child.getParentId()))
                    .map(UpgradeTree.Node::getId).toList(), n.getChildren());
            }
            var depth = UpgradeRules.tiers(tree);
            assertEquals(15, depth.size());
            assertEquals(4, depth.values().stream().mapToInt(Integer::intValue).max().orElseThrow());
            tree.getNodes().forEach(n -> assertEquals((int)depth.get(n.getId()), n.getMinimumLevel()));
            assertEquals(8, tree.getNodes().stream().filter(n -> !children.containsKey(n.getId())).count());
            assertTrue(children.values().stream().allMatch(count -> count >= 2));
            assertEquals(4, tree.getNodes().stream().filter(n -> !n.getPrerequisites().isEmpty()).count());
            assertTrue(UpgradeRules.selected(null, id, UpgradeRules.ROOT));
            assertEquals(1, UpgradeRules.foundation(cls, null).getMinimumLevel());
        }
    }

    @Test public void historicalCreditsMoveIntoLowerNodesWithoutNewPointsOrRewards() {
        var cls = cls();
        cls.setUpgradeTree(UpgradeRules.starter(cls));
        var pc = pc(5);
        UpgradeRules.initialize(pc, cls);
        assertTrue(UpgradeRules.selected(pc.getUpgrades(), cls.getId(), "potency-1"));
        assertEquals(4, pc.getUpgrades().getUnlocks().size());
        assertEquals(0, UpgradeRules.available(pc));
        assertEquals(30, pc.getMaxHitPoints());
        var saved = pc.getUpgrades().getUnlocks().stream().filter(u -> u.getNodeId().equals("potency-1")).findFirst().orElseThrow();
        saved.setNodeId("@historical-level-2");
        assertTrue(UpgradeRules.initialize(pc, cls));
        assertTrue(UpgradeRules.selected(pc.getUpgrades(), cls.getId(), "potency-1"));
        assertFalse(UpgradeRules.initialize(pc, cls));
        assertEquals(4, pc.getUpgrades().getUnlocks().size());
        assertEquals(0, UpgradeRules.available(pc));
    }

    @Test public void wholeTreeSavesRejectSticksButIntermediateAndLegacyTreesRemainReadable() {
        var cls = cls();
        node(cls, "a", "berserker", UpgradeRules.ROOT);
        node(cls, "b", "berserker", "a");
        UpgradeRules.validate(cls.getUpgradeTree());
        rejected(() -> UpgradeRules.validateBranching(cls.getUpgradeTree()), "two children");
        cls.setUpgradeTree(new UpgradeTree());
        UpgradeRules.validateBranching(cls.getUpgradeTree());
        cls.setUpgradeTree(UpgradeRules.starter(cls));
        UpgradeRules.validateBranching(cls.getUpgradeTree());
        cls.getUpgradeTree().getNodes().removeIf(n -> Set.of("potency-6", "potency-7").contains(n.getId()));
        rejected(() -> UpgradeRules.validateBranching(cls.getUpgradeTree()), "eight leaf nodes");
    }

    @Test public void featGrantsAreSnapshotBasedAndAppearInCombatWithoutEditingTheClass() throws Exception {
        var cls = cls();
        var node = node(cls, "feat", "berserker", UpgradeRules.ROOT);
        node.getReward().getFeatures().add(new ClassFeature(2, "Pit Guardian", "Protect an ally once per short rest."));
        var pc = pc(1); earn(pc, cls);
        UpgradeRules.unlock(pc, cls, "feat", id -> null);
        node.getReward().getFeatures().getFirst().setName("Changed by GM");
        var mapper = JsonMappers.create();
        var restored = mapper.readValue(mapper.writeValueAsBytes(pc), PlayerCharacter.class);
        assertNotNull(FeatureRules.kit(restored, cls, null).action(FeatureRules.BODYGUARD));
        assertTrue(FeatureRules.features(restored, cls).stream().anyMatch(f -> f.getName().equals("Pit Guardian")));
        assertTrue(cls.getFeatures().stream().noneMatch(f -> f.getName().equals("Pit Guardian")));
        assertEquals(0, UpgradeRules.available(restored));
    }

    @Test public void generatedFeatsHaveRealUseCountersAndCorrectRestRecovery() {
        var cls = cls(); cls.setFeatures(List.of()); cls.setUpgradeTree(UpgradeRules.starter(cls));
        var pc = pc(1); earn(pc, cls);
        UpgradeRules.unlock(pc, cls, "potency-1", id -> null);
        var action = FeatureRules.kit(pc, cls, null).getActions().stream()
            .filter(a -> a.getId().startsWith("feat-short:")).findFirst().orElseThrow();
        var combat = new com.dnd.model.world.map.CombatState();
        combat.setMaxHitPoints(30); combat.setCurrentHitPoints(30);
        assertEquals(1, action.usesLeft(combat.getFeatureUses()));
        FeatureRules.useOnSelf(action, combat, "Hero", new Random());
        assertEquals(0, action.usesLeft(combat.getFeatureUses()));
        FeatureRules.recover(combat.getFeatureUses(), false, pc.getLevel());
        assertEquals(1, action.usesLeft(combat.getFeatureUses()));
        assertEquals(30, combat.getCurrentHitPoints());
        var feat = new ClassFeature(1, "Last Stand", "Once per long rest, reroll a prone save.");
        feat.setUsesPerRest(1);
        pc.setPersonalFeatures(List.of(feat));
        var longRestAction = FeatureRules.kit(pc, cls, null).getActions().stream()
            .filter(a -> a.getId().startsWith("feat-long:")).findFirst().orElseThrow();
        FeatureRules.useOnSelf(longRestAction, combat, "Hero", new Random());
        FeatureRules.recover(combat.getFeatureUses(), false, pc.getLevel());
        assertEquals(0, longRestAction.usesLeft(combat.getFeatureUses()));
        FeatureRules.recover(combat.getFeatureUses(), true, pc.getLevel());
        assertEquals(1, longRestAction.usesLeft(combat.getFeatureUses()));
    }

    private UpgradeTree legacyStarter() {
        var tree = new UpgradeTree();
        for (String id : List.of("potency", "resilience")) {
            var b = new UpgradeTree.Branch(); b.setId(id); b.setName(id.equals("potency") ? "Berserker" : "Iron Fist");
            tree.getBranches().add(b);
            for (int tier = 0; tier < 4; tier++) {
                var n = new UpgradeTree.Node(); n.setId(id + "-" + (tier + 1)); n.setBranchId(id);
                n.setName(b.getName() + " " + (tier + 1)); n.setMinimumLevel(new int[]{2, 6, 10, 14}[tier]);
                n.setDescription("A step along the " + b.getName() + " path. The GM can replace these starter rewards.");
                if (tier > 0) n.getPrerequisites().add(id + "-" + tier);
                var r = n.getReward();
                if (id.equals("potency")) {
                    if (tier == 0) r.setRageDamage(1);
                    if (tier == 1) r.setAttackBonus(1);
                    if (tier == 2) r.setSpeed(5);
                    if (tier == 3) r.setBasicAttackDamage(1);
                } else {
                    if (tier == 0) r.setBasicAttackDamage(1);
                    if (tier == 1) r.setArmorClass(1);
                    if (tier == 2) r.setSpeed(5);
                    if (tier == 3) r.setHitPoints(5);
                }
                tree.getNodes().add(n);
            }
        }
        return tree;
    }

    @Test public void untouchedLegacyStartersExpandButCustomizationsAndEarnedBonusesSurvive() {
        var cls = cls(); var legacy = legacyStarter();
        assertTrue(UpgradeRules.isLegacyStarter(cls, legacy));
        cls.setUpgradeTree(legacy);
        assertEquals(14, cls.getUpgradeTree().getNodes().size());
        var pc = pc(1); earn(pc, cls);
        UpgradeRules.unlock(pc, cls, "potency-1", id -> null);
        var earned = pc.getUpgrades().getUnlocks().getFirst();
        earned.setDescription("A step along the Berserker path. The GM can replace these starter rewards.");
        earned.getReward().setFeatures(List.of());
        int rage = earned.getReward().getRageDamage();
        int count = pc.getUpgrades().getUnlocks().size();
        assertTrue(UpgradeRules.initialize(pc, cls));
        assertFalse(earned.getReward().getFeatures().isEmpty());
        assertEquals(rage, earned.getReward().getRageDamage());
        assertEquals(count, pc.getUpgrades().getUnlocks().size());
        assertEquals(0, UpgradeRules.available(pc));
        var edited = legacyStarter();
        edited.getNodes().getFirst().getReward().setSpeed(10);
        cls.setUpgradeTree(edited);
        assertSame(edited, cls.getUpgradeTree());
        var customizedLinks = legacyStarter();
        customizedLinks.getNodes().getFirst().setParentId(UpgradeRules.ROOT);
        cls.setUpgradeTree(customizedLinks);
        assertSame(customizedLinks, cls.getUpgradeTree());
        cls.setUpgradeTree(new UpgradeTree());
        assertTrue(cls.getUpgradeTree().getNodes().isEmpty());
    }

    @Test public void knownSpellNodesMigrateWithoutDuplicateSpells() {
        var cls = cls();
        var node = node(cls, "magic", "berserker");
        node.getReward().setSpellIds(List.of("spark"));
        var pc = pc(5); pc.setSpells(new ArrayList<>(List.of(new PlayerCharacter.PlayerSpell("spark", 0, true))));
        UpgradeRules.initialize(pc, cls);
        assertTrue(UpgradeRules.selected(pc.getUpgrades(), cls.getId(), "magic"));
        assertEquals(1, pc.getSpells().size());
        assertEquals(0, UpgradeRules.available(pc));
    }

    @Test public void eachLevelAwardsExactlyOneAndReplayingPlanFails() {
        var pc = pc(1); var cls = cls();
        var plan = Progression.plan(pc, cls);
        Progression.apply(pc, plan);
        assertEquals(1, UpgradeRules.available(pc));
        rejected(() -> Progression.apply(pc, plan), "no longer valid");
        assertEquals(1, UpgradeRules.available(pc));
    }

    @Test public void everyPrerequisiteIsRequiredAndBranchesCanMix() {
        var cls = cls();
        node(cls, "rage", "berserker");
        node(cls, "fist", "iron-fist");
        node(cls, "mastery", "iron-fist", "rage", "fist");
        var pc = pc(1); earn(pc, cls);
        rejected(() -> UpgradeRules.unlock(pc, cls, "mastery", id -> null), "Required level: 3");
        UpgradeRules.unlock(pc, cls, "rage", id -> null);
        rejected(() -> UpgradeRules.unlock(pc, cls, "fist", id -> null), "No unlocks");
        earn(pc, cls); UpgradeRules.unlock(pc, cls, "fist", id -> null);
        earn(pc, cls); UpgradeRules.unlock(pc, cls, "mastery", id -> null);
        assertEquals(3, pc.getUpgrades().getUnlocks().size());
        assertEquals(0, UpgradeRules.available(pc));
    }

    @Test public void exclusivityBlocksBothDirectionsAndSurvivesEdits() {
        for (String first : List.of("a", "b")) {
            var cls = cls();
            var a = node(cls, "a", "berserker"); node(cls, "b", "iron-fist");
            a.getExclusiveWith().add("b");
            var pc = pc(1); earn(pc, cls); UpgradeRules.unlock(pc, cls, first, id -> null);
            earn(pc, cls);
            rejected(() -> UpgradeRules.unlock(pc, cls, first.equals("a") ? "b" : "a", id -> null), "exclusive");
            if (first.equals("a")) {
                a.getExclusiveWith().clear();
                rejected(() -> UpgradeRules.unlock(pc, cls, "b", id -> null), "exclusive");
            }
        }
    }

    @Test public void cyclesMissingLinksAndConflictingAncestorsAreRejected() {
        var cls = cls();
        var a = node(cls, "a", "berserker");
        var b = node(cls, "b", "iron-fist", "a");
        a.getPrerequisites().add("b");
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "cycle");
        a.getPrerequisites().clear(); b.getPrerequisites().add("missing");
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "existing node");
        b.getPrerequisites().remove("missing"); a.getExclusiveWith().add("b");
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "mutually exclusive");
    }

    @Test public void crossBranchConflictingParentsAreRejected() {
        var cls = cls(); var a = node(cls, "a", "berserker"); node(cls, "b", "iron-fist");
        a.getExclusiveWith().add("b");
        node(cls, "impossible", "berserker", "a", "b");
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "mutually exclusive");
    }

    @Test public void invalidBonusesDuplicateIdsAndMissingParentsAreRejected() {
        var cls = cls(); var a = node(cls, "a", "berserker");
        a.getReward().setArmorClass(-1);
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "bonuses");
        a.getReward().setArmorClass(0); a.setParentId("");
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "parent");
        a.setParentId(UpgradeRules.ROOT); node(cls, "a", "iron-fist");
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "Duplicate node");
    }

    @Test public void minimumLevelIsEnforcedEvenWithBankedPoints() {
        var cls = cls(); node(cls, "a", "berserker"); node(cls, "b", "berserker", "a");
        var pc = pc(1); earn(pc, cls);
        rejected(() -> UpgradeRules.unlock(pc, cls, "b", id -> null), "Required level: 3");
        assertEquals(1, UpgradeRules.available(pc));
    }

    @Test public void aSinglePathCanReachThirdTierAtThreeAndALeafAtFour() {
        for (String branch : List.of("potency", "resilience")) {
            var cls = cls(); cls.setUpgradeTree(UpgradeRules.starter(cls));
            var pc = pc(1);
            for (int index : new int[]{1, 3, 6}) {
                earn(pc, cls);
                int level = pc.getLevel();
                String id = branch + "-" + index;
                var chosen = cls.getUpgradeTree().getNodes().stream().filter(n -> n.getId().equals(id)).findFirst().orElseThrow();
                assertEquals(level, chosen.getMinimumLevel());
                assertTrue(chosen.getPrerequisites().isEmpty());
                UpgradeRules.unlock(pc, cls, id, spellId -> null);
                assertFalse(UpgradeRules.selected(pc.getUpgrades(), cls.getId(),
                    (branch.equals("potency") ? "resilience" : "potency") + "-1"));
            }
            assertEquals(4, pc.getLevel());
            assertEquals(3, pc.getUpgrades().getUnlocks().size());
            assertEquals(0, UpgradeRules.available(pc));
        }
    }

    @Test public void parentIsMandatoryEvenWithNoOtherPrerequisitesAndExtrasDoNotChangeTier() {
        var cls = cls();
        node(cls, "a", "berserker");
        node(cls, "b", "iron-fist");
        var advanced = node(cls, "advanced", "berserker", "a", "b");
        advanced.setMinimumLevel(20);
        UpgradeRules.validate(cls.getUpgradeTree());
        assertEquals(3, advanced.getMinimumLevel());
        var pc = pc(1); earn(pc, cls);
        UpgradeRules.unlock(pc, cls, "a", id -> null);
        earn(pc, cls);
        rejected(() -> UpgradeRules.unlock(pc, cls, "advanced", id -> null), "Prerequisites");
        UpgradeRules.unlock(pc, cls, "b", id -> null);
        earn(pc, cls); UpgradeRules.unlock(pc, cls, "advanced", id -> null);
        var onlyParent = node(cls, "only-parent", "iron-fist", "advanced");
        assertTrue(onlyParent.getPrerequisites().isEmpty());
        var other = pc(4);
        other.setUpgrades(new UpgradeProgress()); other.getUpgrades().setEarned(3);
        rejected(() -> UpgradeRules.unlock(other, cls, "only-parent", id -> null), "Prerequisites");
    }

    @Test public void parentsAndChildrenRoundTripAndReparentingHasNoChildLimit() throws Exception {
        var cls = cls(); var rootChild = node(cls, "parent", "berserker");
        for (int i = 0; i < 18; i++) node(cls, "child-" + i, "berserker", "parent");
        UpgradeRules.validate(cls.getUpgradeTree());
        assertEquals(18, rootChild.getChildren().size());
        var mapper = JsonMappers.create();
        var restored = mapper.readValue(mapper.writeValueAsBytes(cls), CharacterClass.class);
        var tree = restored.getUpgradeTree();
        var edit = mapper.convertValue(tree.getNodes().getFirst(), UpgradeTree.Node.class);
        edit.getChildren().remove("child-0");
        UpgradeRules.editConnections(tree, edit);
        assertEquals(17, edit.getChildren().size());
        var child = tree.getNodes().stream().filter(n -> n.getId().equals("child-0")).findFirst().orElseThrow();
        assertEquals(UpgradeRules.ROOT, child.getParentId());
        assertEquals(2, child.getMinimumLevel());
        var moved = mapper.convertValue(child, UpgradeTree.Node.class); moved.setParentId("parent");
        UpgradeRules.editConnections(tree, moved);
        assertEquals(18, tree.getNodes().getFirst().getChildren().size());
        assertEquals(3, moved.getMinimumLevel());
        var bad = mapper.convertValue(tree.getNodes().getFirst(), UpgradeTree.Node.class);
        bad.getChildren().add("unknown");
        rejected(() -> UpgradeRules.editConnections(mapper.convertValue(tree, UpgradeTree.class), bad), "Unknown child");
        bad.setChildren(List.of("parent"));
        rejected(() -> UpgradeRules.editConnections(mapper.convertValue(tree, UpgradeTree.class), bad), "own child");
        bad.setChildren(List.of("child-1", "child-1"));
        rejected(() -> UpgradeRules.editConnections(mapper.convertValue(tree, UpgradeTree.class), bad), "unique");
        var cycle = mapper.convertValue(tree.getNodes().getFirst(), UpgradeTree.Node.class);
        cycle.setParentId("child-1");
        rejected(() -> UpgradeRules.editConnections(mapper.convertValue(tree, UpgradeTree.class), cycle), "cycle");
    }

    @Test public void oldConnectionListsMigrateWithoutLosingExtraPrerequisitesOrSavedRewards() throws Exception {
        var cls = cls();
        var a = node(cls, "a", "berserker"); var b = node(cls, "b", "iron-fist");
        var leaf = node(cls, "leaf", "berserker", "a", "b");
        for (var n : List.of(a, b, leaf)) {
            var required = UpgradeRules.requiredNodes(n);
            n.setParentId(null); n.setPrerequisites(required); n.setMinimumLevel(8);
        }
        var mapper = JsonMappers.create();
        var restored = mapper.readValue(mapper.writeValueAsBytes(cls), CharacterClass.class);
        var tree = restored.getUpgradeTree(); UpgradeRules.validate(tree);
        var migrated = tree.getNodes().getLast();
        assertEquals("a", migrated.getParentId());
        assertEquals(List.of("b"), migrated.getPrerequisites());
        assertEquals(3, migrated.getMinimumLevel());
        assertEquals(List.of("leaf"), tree.getNodes().getFirst().getChildren());
        var pc = pc(1); earn(pc, restored); UpgradeRules.unlock(pc, restored, "a", id -> null);
        var before = mapper.valueToTree(pc.getUpgrades());
        UpgradeRules.validate(tree);
        assertEquals(before, mapper.valueToTree(pc.getUpgrades()));
    }

    @Test public void uneditedPreviousStartersLoseBlanketCrossRequirementsButCustomLinksStay() {
        var cls = new CharacterClass(); cls.setId("wizard");
        var old = UpgradeRules.starter(cls);
        for (var n : old.getNodes()) {
            var primary = n.getParentId();
            int index = Integer.parseInt(n.getId().substring(n.getId().lastIndexOf('-') + 1));
            int minimum = index == 1 ? 2 : index <= 3 ? 4 : 8;
            n.setParentId(null); n.setChildren(List.of());
            n.setPrerequisites(new ArrayList<>(List.of(primary)));
            if (index >= 4) n.getPrerequisites().add((n.getBranchId().equals("potency") ? "resilience" : "potency")
                + "-" + (index <= 5 ? 2 : 3));
            n.setMinimumLevel(minimum);
            n.getReward().getFeatures().forEach(f -> f.setLevel(minimum));
        }
        assertTrue(UpgradeRules.isPreviousStarter(cls, old));
        var mapper = JsonMappers.create();
        var custom = mapper.convertValue(old, UpgradeTree.class);
        custom.getNodes().getFirst().setDescription("Custom training");
        cls.setUpgradeTree(custom); assertSame(custom, cls.getUpgradeTree());
        assertEquals(8, cls.getUpgradeTree().getNodes().stream().filter(n -> !n.getPrerequisites().isEmpty()).count());
        cls.setUpgradeTree(old);
        assertEquals(4, cls.getUpgradeTree().getNodes().stream().filter(n -> !n.getPrerequisites().isEmpty()).count());
        UpgradeRules.validate(cls.getUpgradeTree());
        assertEquals(4, cls.getUpgradeTree().getNodes().getLast().getMinimumLevel());
    }

    @Test public void choicesMustBelongToTheCharactersOwnClass() {
        var cls = cls(); node(cls, "a", "berserker");
        var pc = pc(1); earn(pc, cls); pc.setClassId("wizard");
        rejected(() -> UpgradeRules.unlock(pc, cls, "a", id -> null), "character or class changed");
        assertEquals(1, UpgradeRules.available(pc));
        assertTrue(pc.getUpgrades().getUnlocks().isEmpty());
    }

    @Test public void rewardsApplyOnceAndFeedCombatSheetAndSavedSnapshots() throws Exception {
        var cls = cls(); var a = node(cls, "a", "berserker");
        var r = a.getReward();
        r.setHitPoints(4); r.setMana(3); r.setArmorClass(1); r.setAttackBonus(1);
        r.setBasicAttackDamage(2); r.setRageDamage(1); r.setSpeed(5);
        r.setAbilityId("strength"); r.setAbilityIncrease(2); r.setSpellIds(List.of("spark"));
        var pc = pc(1); earn(pc, cls);
        int hp = pc.getMaxHitPoints(), current = pc.getCurrentHitPoints();
        Spell spark = new Spell(); spark.setId("spark"); spark.setLevel(0);
        UpgradeRules.unlock(pc, cls, "a", id -> spark);
        assertEquals(hp + 4, pc.getMaxHitPoints());
        assertEquals(current + 4, pc.getCurrentHitPoints());
        assertEquals(18, pc.getStats().getStrength());
        assertEquals(3, pc.getMaxMana());
        assertEquals(1, pc.getSpells().size());
        assertEquals(0, pc.getSpells().getFirst().getRank());
        rejected(() -> UpgradeRules.unlock(pc, cls, "a", id -> spark), "Already unlocked");
        r.setBasicAttackDamage(9); r.setSpellIds(List.of());
        assertEquals(2, UpgradeRules.bonus(pc, UpgradeTree.Reward::getBasicAttackDamage));
        FeatureRules.Kit kit = FeatureRules.kit(pc, cls, null);
        assertEquals(3, kit.action("rage").getEffect().getMeleeDamageBonus());
        assertEquals(12, FeatureRules.armorClass(pc, kit, id -> null, 0).total());
        assertEquals(35, SheetValues.speed(pc, null, kit, id -> null));
        var attack = FeatureRules.attacks(pc, kit, id -> null).getFirst();
        assertEquals(7, attack.toHit());
        assertEquals(7, attack.damageBonus());
        assertTrue(kit.getPassives().stream().anyMatch(p -> p.name().equals("a")));
        var mapper = JsonMappers.forCampaign(temp.getRoot().toPath());
        PlayerCharacter restored = mapper.readValue(mapper.writeValueAsBytes(pc), PlayerCharacter.class);
        assertEquals(2, UpgradeRules.bonus(restored, UpgradeTree.Reward::getBasicAttackDamage));
        assertEquals(0, UpgradeRules.available(restored));
        assertEquals(hp + 4, restored.getMaxHitPoints());
        pc.getSheet().setArmorClass(15); pc.getSheet().setSpeed(40);
        assertEquals(16, FeatureRules.armorClass(pc, kit, id -> null, 0).total());
        assertEquals(45, SheetValues.speed(pc, null, kit, id -> null));
        var token = new PlayerToken(restored);
        TokenSupport.refreshFromSheet(token, restored);
        assertEquals(hp + 4, TokenSupport.combatOf(token).getMaxHitPoints());
    }

    @Test public void invalidGrantOrScoreCapDoesNotPartiallyMutateCharacter() {
        var cls = cls(); var a = node(cls, "a", "berserker"); var r = a.getReward();
        r.setHitPoints(10); r.setSpellIds(List.of("missing"));
        var pc = pc(1); earn(pc, cls);
        int hp = pc.getMaxHitPoints();
        rejected(() -> UpgradeRules.unlock(pc, cls, "a", id -> null), "Unknown spell");
        assertEquals(hp, pc.getMaxHitPoints()); assertEquals(1, UpgradeRules.available(pc));
        r.setSpellIds(List.of()); r.setAbilityId("strength"); r.setAbilityIncrease(2);
        pc.getStats().setStrength(30);
        rejected(() -> UpgradeRules.unlock(pc, cls, "a", id -> null), "above 30");
        assertEquals(hp, pc.getMaxHitPoints()); assertEquals(1, UpgradeRules.available(pc));
    }

    private static Spell spell(String id, int level) {
        var spell = new Spell(); spell.setId(id); spell.setName(id); spell.setLevel(level);
        return spell;
    }

    private static PlayerCharacter.PlayerSpell knownSpell(String id) {
        var spell = new PlayerCharacter.PlayerSpell(); spell.setSpellId(id); spell.setRank(1); spell.setActive(true);
        return spell;
    }

    private static UpgradeTree.SpellAlternatives alternatives(String primary, String... ids) {
        var alternatives = new UpgradeTree.SpellAlternatives();
        alternatives.setSpellId(primary); alternatives.setAlternativeSpellIds(List.of(ids));
        return alternatives;
    }

    @Test public void casterStartersGrantRealBalancedSpellsAndAlternativesButNoncastersDoNot() {
        var repos = new CampaignRepositories(java.nio.file.Path.of("src", "main", "resources", "data", "default-campaign"));
        Map<String, Spell> spells = new HashMap<>();
        repos.spells().list().forEach(s -> spells.put(s.getId(), s));
        var classes = new ArrayList<>(repos.classes().list());
        var thirdCaster = new CharacterClass(); thirdCaster.setId("spellblade");
        thirdCaster.setSpellcasting(com.dnd.model.magic.SpellcastingType.THIRD); classes.add(thirdCaster);
        for (var cls : classes) {
            var tree = cls.getUpgradeTree(); UpgradeRules.validate(tree);
            boolean caster = cls.getSpellcasting() != com.dnd.model.magic.SpellcastingType.NONE;
            boolean slower = cls.getSpellcasting() == com.dnd.model.magic.SpellcastingType.HALF
                || cls.getSpellcasting() == com.dnd.model.magic.SpellcastingType.THIRD
                || cls.getSpellcasting() == com.dnd.model.magic.SpellcastingType.ENERGY;
            for (var n : tree.getNodes()) {
                var r = n.getReward();
                assertFalse(r.getFeatures().isEmpty());
                if (!caster) {
                    assertTrue(cls.getId(), r.getSpellIds().isEmpty());
                    assertTrue(r.getSpellAlternatives().isEmpty());
                } else {
                    assertEquals(1, r.getSpellIds().size());
                    assertEquals(1, r.getSpellAlternatives().size());
                    UpgradeRules.validateSpells(r, spells::get);
                    int expected = n.getMinimumLevel() == 2 ? 0 : n.getMinimumLevel() == 3 || slower ? 1 : 2;
                    assertEquals(cls.getId() + "/" + n.getId(), expected, spells.get(r.getSpellIds().getFirst()).getLevel());
                    assertFalse(r.getSpellAlternatives().getFirst().getAlternativeSpellIds().isEmpty());
                }
            }
            if (!caster) continue;
            var pc = pc(1); pc.setClassId(cls.getId());
            pc.setSpells(cls.getDefaultSpells().stream().map(UpgradeRulesTest::knownSpell).toList());
            for (int index : new int[]{1, 3, 6}) {
                earn(pc, cls);
                var n = tree.getNodes().stream().filter(candidate -> candidate.getId().equals("potency-" + index)).findFirst().orElseThrow();
                Map<String, String> selected = new HashMap<>();
                for (var choice : UpgradeRules.spellChoices(pc, n.getReward(), spells::get)) selected.put(choice.primary().getId(),
                    choice.alternatives().isEmpty() ? choice.primary().getId() : choice.alternatives().getFirst().getId());
                var slots = JsonMappers.create().valueToTree(pc.getSpellSlots());
                UpgradeRules.unlock(pc, cls, n.getId(), spells::get, selected);
                assertEquals(slots, JsonMappers.create().valueToTree(pc.getSpellSlots()));
            }
            assertEquals(4, pc.getLevel());
            assertEquals(0, UpgradeRules.available(pc));
        }
    }

    @Test public void anyClassCanLearnASpellAndOnlyPredefinedSameLevelAlternativesAreAllowed() throws Exception {
        var cls = cls(); var n = node(cls, "spell-node", "berserker");
        n.getReward().setSpellIds(List.of("primary")); n.getReward().setHitPoints(3);
        n.getReward().setSpellAlternatives(List.of(alternatives("primary", "known-option", "new-option")));
        Map<String, Spell> spells = Map.of("primary", spell("primary", 1), "known-option", spell("known-option", 1),
            "new-option", spell("new-option", 1), "not-predefined", spell("not-predefined", 1));
        var pc = pc(1); earn(pc, cls);
        var primary = knownSpell("primary"); primary.setRank(7); primary.setActive(false);
        pc.setSpells(new ArrayList<>(List.of(primary, knownSpell("known-option"))));
        var offers = UpgradeRules.spellChoices(pc, n.getReward(), spells::get);
        assertEquals(1, offers.size());
        assertEquals(List.of("new-option"), offers.getFirst().alternatives().stream().map(Spell::getId).toList());
        var mapper = JsonMappers.create(); var before = mapper.valueToTree(pc);
        rejected(() -> UpgradeRules.unlock(pc, cls, n.getId(), spells::get), "Choose a predefined");
        rejected(() -> UpgradeRules.unlock(pc, cls, n.getId(), spells::get, Map.of("primary", "known-option")), "predefined");
        rejected(() -> UpgradeRules.unlock(pc, cls, n.getId(), spells::get, Map.of("primary", "not-predefined")), "predefined");
        rejected(() -> UpgradeRules.unlock(pc, cls, n.getId(), spells::get, Map.of("primary", "primary")), "predefined");
        rejected(() -> UpgradeRules.unlock(pc, cls, n.getId(), spells::get, Map.of("unknown", "new-option")), "Choose a predefined");
        assertEquals(before, mapper.valueToTree(pc));
        UpgradeRules.unlock(pc, cls, n.getId(), spells::get, Map.of("primary", "new-option"));
        assertEquals(3, pc.getSpells().size());
        assertEquals(7, primary.getRank()); assertFalse(primary.isActive());
        assertEquals(1, pc.getSpells().getLast().getRank()); assertTrue(pc.getSpells().getLast().isActive());
        var saved = pc.getUpgrades().getUnlocks().getFirst().getReward();
        assertEquals(List.of("new-option"), saved.getSpellIds());
        assertTrue(saved.getSpellAlternatives().isEmpty());
        n.getReward().setSpellIds(List.of("not-predefined"));
        var restored = mapper.readValue(mapper.writeValueAsBytes(pc), PlayerCharacter.class);
        assertEquals(List.of("new-option"), restored.getUpgrades().getUnlocks().getFirst().getReward().getSpellIds());
        assertEquals(com.dnd.model.magic.SpellcastingType.NONE, cls.getSpellcasting());
    }

    @Test public void exhaustedSpellOptionsRequireAcknowledgmentButDoNotBlockOtherRewards() {
        var cls = cls(); var n = node(cls, "spell-node", "berserker");
        n.getReward().setHitPoints(5);
        n.getReward().setFeatures(List.of(new ClassFeature(2, "Spellwise", "Keep this feat even when every spell is known.")));
        n.getReward().setSpellIds(List.of("primary"));
        n.getReward().setSpellAlternatives(List.of(alternatives("primary", "alternative")));
        var pc = pc(1); earn(pc, cls);
        pc.setSpells(new ArrayList<>(List.of(knownSpell("primary"), knownSpell("alternative"))));
        Map<String, Spell> spells = Map.of("primary", spell("primary", 1), "alternative", spell("alternative", 1));
        assertTrue(UpgradeRules.spellChoices(pc, n.getReward(), spells::get).getFirst().alternatives().isEmpty());
        int hp = pc.getMaxHitPoints();
        rejected(() -> UpgradeRules.unlock(pc, cls, n.getId(), spells::get), "acknowledge");
        assertEquals(hp, pc.getMaxHitPoints()); assertEquals(1, UpgradeRules.available(pc));
        UpgradeRules.unlock(pc, cls, n.getId(), spells::get, Map.of("primary", "primary"));
        assertEquals(hp + 5, pc.getMaxHitPoints()); assertEquals(2, pc.getSpells().size());
        assertTrue(pc.getUpgrades().getUnlocks().getFirst().getDescription().contains("No new spell learned"));
        assertTrue(pc.getUpgrades().getUnlocks().getFirst().getReward().getSpellIds().isEmpty());
        assertTrue(FeatureRules.features(pc, cls).stream().anyMatch(f -> f.getName().equals("Spellwise")));
        assertEquals(0, UpgradeRules.available(pc));
    }

    @Test public void invalidAlternativeDefinitionsAndDuplicateSelectionsCannotPartiallyApplyRewards() throws Exception {
        var cls = cls(); var n = node(cls, "spell-node", "berserker"); var r = n.getReward();
        r.setSpellIds(List.of("primary", "second")); r.setHitPoints(10);
        r.setSpellAlternatives(List.of(alternatives("primary", "shared"), alternatives("second", "shared")));
        var pc = pc(1); earn(pc, cls); pc.setSpells(new ArrayList<>(List.of(knownSpell("primary"), knownSpell("second"))));
        Map<String, Spell> spells = new HashMap<>(Map.of("primary", spell("primary", 1), "second", spell("second", 1),
            "shared", spell("shared", 1)));
        var mapper = JsonMappers.create();
        var tree = mapper.readValue(mapper.writeValueAsBytes(cls), CharacterClass.class).getUpgradeTree();
        assertEquals(List.of("shared"), tree.getNodes().getFirst().getReward().getSpellAlternatives().getFirst().getAlternativeSpellIds());
        var before = mapper.valueToTree(pc);
        rejected(() -> UpgradeRules.unlock(pc, cls, n.getId(), spells::get, Map.of("primary", "shared", "second", "shared")), "different alternative");
        spells.get("shared").setLevel(2);
        rejected(() -> UpgradeRules.unlock(pc, cls, n.getId(), spells::get, Map.of("primary", "shared", "second", "shared")), "same spell level");
        spells.remove("shared");
        rejected(() -> UpgradeRules.unlock(pc, cls, n.getId(), spells::get), "Unknown spell");
        assertEquals(before, mapper.valueToTree(pc));
        r.setSpellAlternatives(List.of(alternatives("not-primary", "shared")));
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "primary spell");
        r.setSpellAlternatives(List.of(alternatives("primary", "primary")));
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "excluding the primary");
    }

    @Test public void anEarnedFeatHasNoSecondLevelGateButAutomaticClassTrainingStillDoes() {
        var cls = cls();
        var features = new ArrayList<>(cls.getFeatures());
        features.add(new ClassFeature(20, "Later automatic training", "Not available at level two."));
        cls.setFeatures(features);
        var a = node(cls, "a", "berserker");
        var feat = new ClassFeature(20, "Earned tree training", "This is granted by choosing its node.");
        a.getReward().setFeatures(List.of(feat));
        var pc = pc(1); earn(pc, cls); UpgradeRules.unlock(pc, cls, "a", id -> null);
        assertEquals(2, pc.getLevel());
        assertEquals(20, pc.getUpgrades().getUnlocks().getFirst().getReward().getFeatures().getFirst().getLevel());
        assertTrue(FeatureRules.features(pc, cls).stream().anyMatch(f -> f.getName().equals("Earned tree training")));
        assertFalse(FeatureRules.features(pc, cls).stream().anyMatch(f -> f.getName().equals("Later automatic training")));
        a.getReward().setFeatures(List.of()); cls.getUpgradeTree().getNodes().clear();
        assertTrue(FeatureRules.features(pc, cls).stream().anyMatch(f -> f.getName().equals("Earned tree training")));
    }

    @Test public void deletedDefinitionsDoNotRemoveRewardsOrRefundPoints() throws Exception {
        var cls = cls(); var a = node(cls, "a", "berserker"); a.getReward().setArmorClass(1);
        var pc = pc(1); earn(pc, cls); UpgradeRules.unlock(pc, cls, "a", id -> null);
        cls.getUpgradeTree().getNodes().clear();
        assertEquals(1, UpgradeRules.bonus(pc, UpgradeTree.Reward::getArmorClass));
        assertEquals(0, UpgradeRules.available(pc));
        CampaignRepositories repos = new CampaignRepositories(temp.getRoot().toPath());
        repos.players().save(pc); repos.classes().save(cls);
        var restored = new CampaignRepositories(temp.getRoot().toPath()).players().getById("hero");
        assertEquals(1, restored.getUpgrades().getUnlocks().size());
        assertEquals(1, UpgradeRules.bonus(restored, UpgradeTree.Reward::getArmorClass));
    }
}
