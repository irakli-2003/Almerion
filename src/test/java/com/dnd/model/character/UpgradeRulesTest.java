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
