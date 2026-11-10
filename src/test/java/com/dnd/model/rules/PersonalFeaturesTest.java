package com.dnd.model.rules;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.JsonMappers;
import com.dnd.model.character.*;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.world.map.*;
import org.junit.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.Assert.*;

public class PersonalFeaturesTest {
    private PlayerCharacter bard() {
        PlayerCharacter pc = new PlayerCharacter("bard", "Balthazar", "bard", "human", 5,
            new CoreStats(10, 10, 10, 10, 10, 14), new ArrayList<>(), new ArrayList<>());
        pc.setPersonalFeatures(List.of(new ClassFeature(5, "Encore", "Personal support.")));
        return pc;
    }

    private FeatureRules.Kit bardKit(PlayerCharacter pc) {
        CharacterClass cls = new CharacterClass();
        cls.setId("bard"); cls.setFeatures(List.of(new ClassFeature(1, "Bardic Inspiration", "Inspire.")));
        return FeatureRules.kit(pc, cls, null);
    }

    private CombatState state(int hp) {
        CombatState state = new CombatState();
        state.setMaxHitPoints(30); state.setCurrentHitPoints(hp);
        return state;
    }

    @Test public void encoreConsumesOwnInspirationHealsOnlyProficiencyAndSpendsOneUse() {
        var pc = bard(); var kit = bardKit(pc);
        var user = state(30); var target = state(20);
        var inspiration = kit.action("bardic-inspiration");
        var encore = kit.action(FeatureRules.ENCORE);
        FeatureRules.useOnAlly(inspiration, user, "Balthazar", target, "Darius");
        assertEquals("Balthazar", target.getActiveEffects().getFirst().getSource());
        assertEquals(1, inspiration.usesLeft(user.getFeatureUses()));
        String result = FeatureRules.useOnAlly(encore, user, "Balthazar", target, "Darius");
        assertTrue(result, result.contains("3 HP"));
        assertEquals(23, target.getCurrentHitPoints());
        assertTrue(target.getActiveEffects().isEmpty());
        assertEquals(0, encore.usesLeft(user.getFeatureUses()));
        assertEquals("Inspiration is not refunded", 1, inspiration.usesLeft(user.getFeatureUses()));
        FeatureRules.useOnAlly(inspiration, user, "Balthazar", target, "Darius");
        assertTrue(FeatureRules.useOnAlly(encore, user, "Balthazar", target, "Darius").contains("spent"));
        assertEquals(23, target.getCurrentHitPoints());
        assertEquals(1, target.getActiveEffects().size());
        FeatureRules.recover(user.getFeatureUses(), false, 5);
        assertEquals(1, encore.usesLeft(user.getFeatureUses()));
        FeatureRules.useOnAlly(encore, user, "Balthazar", target, "Darius");
        assertEquals(26, target.getCurrentHitPoints());
    }

    @Test public void encoreRejectsWrongCasterExpiredMissingSelfDownedAndFullWithoutSpending() {
        var kit = bardKit(bard()); var encore = kit.action(FeatureRules.ENCORE);
        var user = state(30); var target = state(20);
        assertNotNull(FeatureRules.allyFeatureBlockedReason(encore, user, "Balthazar", target));
        FeatureRules.useOnAlly(kit.action("bardic-inspiration"), user, "Another bard", target, "Darius");
        assertNotNull(FeatureRules.allyFeatureBlockedReason(encore, user, "Balthazar", target));
        FeatureRules.useOnAlly(kit.action("bardic-inspiration"), state(30), "Balthazar", target, "Darius");
        assertEquals("Balthazar", target.getActiveEffects().getFirst().getSource());
        target.getActiveEffects().getFirst().setRemainingRounds(0);
        assertNotNull(FeatureRules.allyFeatureBlockedReason(encore, user, "Balthazar", target));
        target.getActiveEffects().getFirst().setRemainingRounds(100);
        assertNotNull(FeatureRules.allyFeatureBlockedReason(encore, user, "Balthazar", user));
        target.setCurrentHitPoints(0);
        assertTrue(FeatureRules.useOnAlly(encore, user, "Balthazar", target, "Darius").contains("conscious"));
        assertEquals(0, target.getCurrentHitPoints());
        target.setCurrentHitPoints(30);
        assertTrue(FeatureRules.useOnAlly(encore, user, "Balthazar", target, "Darius").contains("full HP"));
        target.setCurrentHitPoints(20); user.setCurrentHitPoints(0);
        assertTrue(FeatureRules.useOnAlly(encore, user, "Balthazar", target, "Darius").contains("conscious"));
        assertEquals(1, encore.usesLeft(user.getFeatureUses()));
        assertEquals(20, target.getCurrentHitPoints());
        assertEquals(1, target.getActiveEffects().size());
    }

    @Test public void encoreCapsHealingAndCannotBeUsedAsASelfHeal() {
        var kit = bardKit(bard()); var user = state(30); var target = state(29);
        var encore = kit.action(FeatureRules.ENCORE);
        FeatureRules.useOnAlly(kit.action("bardic-inspiration"), user, "Balthazar", target, "Darius");
        assertTrue(FeatureRules.useOnSelf(encore, user, "Balthazar", new Random()).contains("needs a target"));
        assertEquals(1, encore.usesLeft(user.getFeatureUses()));
        assertTrue(FeatureRules.useOnAlly(encore, user, "Balthazar", target, "Darius").contains("1 HP"));
        assertEquals(30, target.getCurrentHitPoints());
    }

    @Test public void guardianAddsTwoAcForOneRoundAndRestoresOnRestWithoutChangingDamage() {
        PlayerCharacter pc = new PlayerCharacter();
        pc.setClassId("barbarian"); pc.setLevel(5);
        pc.setPersonalFeatures(List.of(new ClassFeature(5, "Pit Guardian", "Protect."),
            new ClassFeature(5, "Brawler's Throw", "Throw.")));
        var kit = FeatureRules.kit(pc, null, null);
        var guard = kit.action(FeatureRules.BODYGUARD);
        assertNotNull(guard); assertEquals(5, guard.getRangeFeet(), 0.01);
        var user = state(30); var target = state(20);
        assertNotNull(FeatureRules.allyFeatureBlockedReason(guard, user, "Darius", user));
        FeatureRules.useOnAlly(guard, user, "Darius", target, "Balthazar");
        assertEquals(2, target.getActiveEffects().getFirst().getAcBonus());
        assertEquals(0, target.getActiveEffects().getFirst().getMeleeDamageBonus());
        assertEquals(20, target.getCurrentHitPoints());
        assertEquals(0, guard.usesLeft(user.getFeatureUses()));
        assertTrue(FeatureRules.useOnAlly(guard, user, "Darius", target, "Balthazar").contains("spent"));
        target.tickEffects("Balthazar");
        assertTrue(target.getActiveEffects().isEmpty());
        FeatureRules.recover(user.getFeatureUses(), false, 5);
        assertEquals(1, guard.usesLeft(user.getFeatureUses()));
        var toss = kit.action("brawlers-throw");
        assertFalse(toss.isLimited());
        assertEquals(FeatureRules.Kind.COUNTER, toss.getKind());
        FeatureRules.useOnSelf(toss, user, "Darius", new Random());
        assertEquals(30, user.getCurrentHitPoints());
        assertEquals(0, user.getCurrentMana());
        user.setCurrentHitPoints(0);
        assertTrue(FeatureRules.useOnSelf(toss, user, "Darius", new Random()).contains("conscious"));
    }

    @Test public void personalFeaturesRespectLevelAndSubclassAndPersistThroughJsonAndTokens() throws Exception {
        var pc = bard();
        pc.getPersonalFeatures().add(new ClassFeature(6, "Pit Guardian", "Not yet."));
        pc.getPersonalFeatures().add(new ClassFeature(1, "Brawler's Throw", "Other subclass.", "Other"));
        var mapper = JsonMappers.create();
        var restored = mapper.readValue(mapper.writeValueAsBytes(pc), PlayerCharacter.class);
        var kit = bardKit(restored);
        assertNotNull(kit.action(FeatureRules.ENCORE));
        assertNull(kit.action(FeatureRules.BODYGUARD));
        assertNull(kit.action("brawlers-throw"));
        var token = new PlayerToken(restored);
        assertEquals(3, token.getCharacter().getPersonalFeatures().size());
        assertEquals(1, FeatureRules.features(restored, new CharacterClass()).size());
        restored.setPersonalFeatures(null);
        assertTrue(restored.getPersonalFeatures().isEmpty());
    }

    @Test public void campaignGrantsArePersonalAndLeaveOtherBarbariansAndBardsUnchanged() {
        var repos = new CampaignRepositories(Path.of("src", "main", "resources", "data", "custom-campaigns", "zurabaach"));
        var darius = repos.players().getById("darius");
        var balthazar = repos.players().getById("player-balthazar");
        var dariusKit = FeatureRules.kit(darius, repos.classes().getById("barbarian"), null);
        assertNotNull(dariusKit.action("brawlers-throw"));
        assertNotNull(dariusKit.action(FeatureRules.BODYGUARD));
        assertNotNull(FeatureRules.kit(balthazar, repos.classes().getById("bard"), null).action(FeatureRules.ENCORE));
        assertNull(FeatureRules.kit(new PlayerCharacter(), repos.classes().getById("barbarian"), null).action(FeatureRules.BODYGUARD));
        assertEquals(2, darius.getPersonalFeatures().size());
        assertEquals(1, balthazar.getPersonalFeatures().size());
        assertEquals(1, darius.getSpells().size());
        assertEquals(11, balthazar.getSpells().size());
        var shared = com.dnd.lan.LanSheet.build(darius, repos, "Zurabaach", s -> s, JsonMappers.create());
        assertTrue(shared.path("classFeatures").toString().contains("Brawler's Throw"));
        assertTrue(shared.path("classFeatures").toString().contains("Pit Guardian"));
        assertTrue(shared.path("actions").toString().contains("Pit Guardian"));
        var sharedBard = com.dnd.lan.LanSheet.build(balthazar, repos, "Zurabaach", s -> s, JsonMappers.create());
        assertTrue(sharedBard.path("classFeatures").toString().contains("Encore"));
    }
}
