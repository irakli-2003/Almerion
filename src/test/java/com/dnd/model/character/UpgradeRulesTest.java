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
        node.setPrerequisites(new ArrayList<>(List.of(prerequisites)));
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
            Map<String, Integer> depth = new HashMap<>();
            depth.put(UpgradeRules.ROOT, 0);
            for (var n : tree.getNodes()) {
                for (String parent : n.getPrerequisites()) children.merge(parent, 1, Integer::sum);
                assertFalse(n.getReward().getFeatures().isEmpty());
                assertFalse(n.getReward().getFeatures().getFirst().getDescription().isBlank());
            }
            for (int pass = 0; pass < 4; pass++) for (var n : tree.getNodes()) {
                if (n.getPrerequisites().stream().allMatch(depth::containsKey)) {
                    depth.put(n.getId(), 1 + n.getPrerequisites().stream().mapToInt(depth::get).max().orElseThrow());
                }
            }
            assertEquals(15, depth.size());
            assertEquals(3, depth.values().stream().mapToInt(Integer::intValue).max().orElseThrow());
            assertEquals(8, tree.getNodes().stream().filter(n -> !children.containsKey(n.getId())).count());
            assertTrue(children.values().stream().allMatch(count -> count >= 2));
            assertEquals(8, tree.getNodes().stream().filter(n -> n.getPrerequisites().size() > 1).count());
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
        cls.getUpgradeTree().getNodes().removeIf(n -> n.getId().endsWith("-7"));
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
        rejected(() -> UpgradeRules.unlock(pc, cls, "mastery", id -> null), "Prerequisites");
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

    @Test public void invalidBonusesDuplicateIdsAndLevelsAreRejected() {
        var cls = cls(); var a = node(cls, "a", "berserker");
        a.getReward().setArmorClass(-1);
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "bonuses");
        a.getReward().setArmorClass(0); a.setMinimumLevel(31);
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "minimum level");
        a.setMinimumLevel(2); node(cls, "a", "iron-fist");
        rejected(() -> UpgradeRules.validate(cls.getUpgradeTree()), "Duplicate node");
    }

    @Test public void minimumLevelIsEnforcedEvenWithBankedPoints() {
        var cls = cls(); var a = node(cls, "a", "berserker"); a.setMinimumLevel(6);
        var pc = pc(1); earn(pc, cls);
        rejected(() -> UpgradeRules.unlock(pc, cls, "a", id -> null), "Required level");
        assertEquals(1, UpgradeRules.available(pc));
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
