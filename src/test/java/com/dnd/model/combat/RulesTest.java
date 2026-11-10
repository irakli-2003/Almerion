package com.dnd.model.combat;

import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.Progression;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.creature.Monster;
import com.dnd.model.magic.CastingResource;
import com.dnd.model.magic.Spell;
import com.dnd.model.magic.SpellSlot;
import com.dnd.model.magic.SpellSlots;
import com.dnd.model.magic.SpellcastingType;
import com.dnd.model.world.Dice;
import com.dnd.model.world.map.ActiveEffect;
import com.dnd.model.world.map.CombatState;
import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.MonsterToken;
import com.dnd.model.world.map.PlayerToken;
import com.dnd.model.world.map.TokenSupport;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class RulesTest {

    // ── Spell slot tables ───────────────────────────────────────────────────

    @Test
    public void fullCasterTableMatchesFifthEdition() {
        assertArrayEquals(new int[]{2, 0, 0, 0, 0, 0, 0, 0, 0}, SpellSlots.maxSlots(SpellcastingType.FULL, 1));
        assertArrayEquals(new int[]{4, 3, 2, 0, 0, 0, 0, 0, 0}, SpellSlots.maxSlots(SpellcastingType.FULL, 5));
        assertArrayEquals(new int[]{4, 3, 3, 3, 3, 2, 2, 1, 1}, SpellSlots.maxSlots(SpellcastingType.FULL, 20));
    }

    @Test
    public void halfAndThirdCastersLagBehind() {
        assertArrayEquals(new int[9], SpellSlots.maxSlots(SpellcastingType.HALF, 1));
        assertArrayEquals(new int[]{4, 2, 0, 0, 0, 0, 0, 0, 0}, SpellSlots.maxSlots(SpellcastingType.HALF, 5));
        assertArrayEquals(new int[9], SpellSlots.maxSlots(SpellcastingType.THIRD, 2));
        assertArrayEquals(new int[]{2, 0, 0, 0, 0, 0, 0, 0, 0}, SpellSlots.maxSlots(SpellcastingType.THIRD, 3));
        assertArrayEquals(new int[9], SpellSlots.maxSlots(SpellcastingType.NONE, 10));
    }

    @Test
    public void pactMagicHasFewSameLevelSlots() {
        assertArrayEquals(new int[]{0, 0, 2, 0, 0, 0, 0, 0, 0}, SpellSlots.maxSlots(SpellcastingType.PACT, 5));
        assertArrayEquals(new int[]{0, 0, 0, 0, 4, 0, 0, 0, 0}, SpellSlots.maxSlots(SpellcastingType.PACT, 20));
    }

    @Test
    public void withMaxKeepsSpentSlotsSpent() {
        List<SpellSlot> slots = SpellSlots.fresh(new int[]{4, 2, 0, 0, 0, 0, 0, 0, 0});
        SpellSlots.spend(slots, 1);
        List<SpellSlot> grown = SpellSlots.withMax(slots, new int[]{4, 3, 0, 0, 0, 0, 0, 0, 0});
        assertEquals(3, SpellSlots.find(grown, 1).getCurrent());
        assertEquals(3, SpellSlots.find(grown, 2).getCurrent());
    }

    // ── Casting costs ───────────────────────────────────────────────────────

    private static Spell spell(String id, int level, int manaCost) {
        Spell spell = new Spell();
        spell.setId(id);
        spell.setName(id);
        spell.setLevel(level);
        spell.setManaCost(manaCost);
        spell.setRange(60);
        return spell;
    }

    private static MonsterToken caster(int hp) {
        Monster monster = new Monster();
        monster.setId("caster");
        monster.setName("Caster");
        MonsterToken token = new MonsterToken(monster);
        CombatState state = TokenSupport.combatOf(token);
        state.setMaxHitPoints(hp);
        state.setCurrentHitPoints(hp);
        return token;
    }

    @Test
    public void levelledSpellSpendsLowestAvailableSlot() {
        MonsterToken token = caster(20);
        CombatState state = TokenSupport.combatOf(token);
        state.setSpellSlots(new ArrayList<>(List.of(new SpellSlot(1, 2, 0), new SpellSlot(2, 1, 1))));
        CastResolver.Outcome outcome = CastResolver.cast(token, CastResolver.of(spell("bolt", 1, 5)), List.of(), 10, 0);
        assertTrue(outcome.getMessage(), outcome.isSuccess());
        assertEquals(0, SpellSlots.find(state.getSpellSlots(), 2).getCurrent());
        assertNotNull(CastResolver.blockedReason(token, CastResolver.of(spell("bolt", 1, 5))));
    }

    @Test
    public void cantripsAreFreeForSlotCasters() {
        MonsterToken token = caster(20);
        CombatState state = TokenSupport.combatOf(token);
        state.setSpellSlots(new ArrayList<>(List.of(new SpellSlot(1, 2, 0))));
        assertTrue(CastResolver.cast(token, CastResolver.of(spell("spark", 0, 3)), List.of(), 10, 0).isSuccess());
    }

    @Test
    public void manaCastersStillPayMana() {
        MonsterToken token = caster(20);
        CombatState state = TokenSupport.combatOf(token);
        state.setMaxMana(10);
        state.setCurrentMana(10);
        assertTrue(CastResolver.cast(token, CastResolver.of(spell("bolt", 1, 4)), List.of(), 10, 0).isSuccess());
        assertEquals(6, state.getCurrentMana());
    }

    @Test
    public void bloodCastersPayInHitPoints() {
        MonsterToken token = caster(100);
        CombatState state = TokenSupport.combatOf(token);
        state.setCastingResource(CastingResource.HIT_POINTS);
        assertTrue(CastResolver.cast(token, CastResolver.of(spell("raaz", 0, 10)), List.of(), 5, 0).isSuccess());
        assertEquals(90, state.getCurrentHitPoints());
        state.setCurrentHitPoints(10);
        assertNotNull(CastResolver.blockedReason(token, CastResolver.of(spell("raaz", 0, 10))));
    }

    @Test
    public void abilityHpCostIsCharged() {
        MonsterToken token = caster(30);
        Ability ability = new Ability();
        ability.setId("rune");
        ability.setName("Rune");
        ability.setHpCost(2);
        ability.setRange(5);
        assertTrue(CastResolver.cast(token, CastResolver.of(ability, id -> null), List.of(), 5, 0).isSuccess());
        assertEquals(28, TokenSupport.combatOf(token).getCurrentHitPoints());
    }

    // ── Ammunition ──────────────────────────────────────────────────────────

    @Test
    public void revolverShotSpendsManaAndOneCartridgeFromTheStack() {
        PlayerCharacter pc = sheet();
        PlayerCharacter.PlayerItem ammo = new PlayerCharacter.PlayerItem(
            "sixfold-cartridge", new PlayerCharacter.ItemCondition(100), false);
        ammo.setQuantity(2);
        pc.getItems().add(ammo);
        PlayerToken token = new PlayerToken(pc);
        TokenSupport.refreshFromSheet(token, pc);
        CombatState state = TokenSupport.combatOf(token);
        state.setCastingResource(CastingResource.MANA);
        state.setMaxMana(6);
        state.setCurrentMana(6);

        com.dnd.model.item.books.Book cartridge = new com.dnd.model.item.books.Book();
        cartridge.setId("sixfold-cartridge");
        cartridge.setName("Sixfold Cartridge");
        Spell shot = spell("gravity-round", 0, 1);
        shot.setRequiredConsumables(new ArrayList<>(List.of(cartridge)));

        assertTrue(CastResolver.cast(token, CastResolver.of(shot), List.of(), 5, 0).isSuccess());
        assertEquals(5, state.getCurrentMana());
        assertEquals(1, ammo.getQuantity());
        assertTrue(CastResolver.cast(token, CastResolver.of(shot), List.of(), 5, 0).isSuccess());
        assertTrue(pc.getItems().stream().noneMatch(i -> "sixfold-cartridge".equals(i.getItemId())));
        assertNotNull("no bullets left", CastResolver.blockedReason(token, CastResolver.of(shot)));

        Progression.longRest(state);
        assertEquals(6, state.getCurrentMana());
        assertNotNull("rest restores mana but not bullets", CastResolver.blockedReason(token, CastResolver.of(shot)));
    }

    // ── Sheet sync ──────────────────────────────────────────────────────────

    private static PlayerCharacter sheet() {
        PlayerCharacter pc = new PlayerCharacter("pc", "Hero", "wizard", "human", 5,
            new CoreStats(10, 12, 14, 16, 10, 10), new ArrayList<>(), new ArrayList<>());
        pc.setMaxHitPoints(40);
        pc.setCurrentHitPoints(25);
        pc.setSpellSlots(SpellSlots.fresh(new int[]{4, 3, 2, 0, 0, 0, 0, 0, 0}));
        pc.addEffect(new ActiveEffect("frost", "Frost", 3, 1, 0, "Ice"));
        return pc;
    }

    @Test
    public void tokenPullsFromSheetAndPushesBack() {
        PlayerCharacter pc = sheet();
        PlayerToken token = new PlayerToken(pc);
        TokenSupport.refreshFromSheet(token, pc);
        CombatState state = TokenSupport.combatOf(token);
        assertEquals(25, state.getCurrentHitPoints());
        assertEquals(1, state.getActiveEffects().size());
        assertEquals(3, state.getSpellSlots().size());

        state.applyDamage(5);
        SpellSlots.spend(state.getSpellSlots(), 3);
        PlayerCharacter persisted = sheet();
        TokenSupport.pushVitals(state, persisted);
        assertEquals(20, persisted.getCurrentHitPoints());
        assertEquals(1, SpellSlots.find(persisted.getSpellSlots(), 3).getCurrent());
    }

    // ── Level up and rests ──────────────────────────────────────────────────

    private static CharacterClass wizard() {
        CharacterClass cls = new CharacterClass();
        cls.setId("wizard");
        cls.setName("Wizard");
        cls.setHitDie(new Dice("d6", "d6", 6));
        cls.setSpellcasting(SpellcastingType.FULL);
        return cls;
    }

    @Test
    public void levelUpNeedsHundredXpAndResetsIt() {
        PlayerCharacter pc = sheet();
        pc.setXp(99);
        assertFalse(Progression.canLevelUp(pc));
        pc.setXp(100);
        assertTrue(Progression.canLevelUp(pc));

        Progression.LevelUpPlan plan = Progression.plan(pc, wizard());
        assertEquals(6, plan.toLevel);
        assertEquals(6, plan.hitPointGain); // d6 average 4 + CON 14 (+2)
        assertArrayEquals(new int[]{4, 3, 3, 0, 0, 0, 0, 0, 0}, plan.slotsAfter);

        Progression.apply(pc, plan);
        assertEquals(6, pc.getLevel());
        assertEquals(0, pc.getXp());
        assertEquals(46, pc.getMaxHitPoints());
        assertEquals(31, pc.getCurrentHitPoints());
        assertEquals(3, SpellSlots.find(pc.getSpellSlots(), 3).getMax());
    }

    @Test
    public void longRestRestoresEverything() {
        PlayerCharacter pc = sheet();
        SpellSlots.spend(pc.getSpellSlots(), 1);
        pc.getCooldowns().put("bolt", 2);
        Progression.longRest(pc);
        assertEquals(40, pc.getCurrentHitPoints());
        assertEquals(4, SpellSlots.find(pc.getSpellSlots(), 1).getCurrent());
        assertTrue(pc.getActiveEffects().isEmpty());
        assertTrue(pc.getCooldowns().isEmpty());
    }

    @Test
    public void shortRestHealsRolledAmountAndEndsTimedEffects() {
        PlayerCharacter pc = sheet();
        SpellSlots.spend(pc.getSpellSlots(), 1);
        Progression.shortRest(pc, wizard(), 7);
        assertEquals(32, pc.getCurrentHitPoints());
        assertEquals(3, SpellSlots.find(pc.getSpellSlots(), 1).getCurrent());
        assertTrue(pc.getActiveEffects().isEmpty());
    }

    // ── Time rewind ─────────────────────────────────────────────────────────

    @Test
    public void rewindCanUseMostRecentTurnWhenCasterIsNotCurrentlyActing() {
        GameMap map = new GameMap("m", "M", 5, 5);
        MonsterToken rabbit = caster(10);
        MonsterToken orc = caster(20);
        map.placeObject(rabbit, 0, 0);
        map.placeObject(orc, 4, 4);
        TurnHistory history = new TurnHistory();
        history.record(map, rabbit, 1);
        history.record(map, orc, 1);
        assertNull(history.previousTurnOf(rabbit, true));
        assertEquals(1, history.previousTurnOf(rabbit, false).getRound());
    }

    @Test
    public void successfulChecksPreserveStatePositionNewTokensAndRemovedTokens() {
        GameMap map = new GameMap("m", "M", 6, 6);
        MonsterToken rabbit = caster(10);
        MonsterToken unchanged = caster(20);
        MonsterToken removed = caster(20);
        map.placeObject(rabbit, 0, 0);
        map.placeObject(unchanged, 4, 4);
        map.placeObject(removed, 5, 5);
        TurnHistory history = new TurnHistory();
        history.record(map, rabbit, 1);
        TurnHistory.Snapshot snapshot = history.previousTurnOf(rabbit, false);
        TokenSupport.combatOf(rabbit).applyDamage(5);
        CombatState kept = TokenSupport.combatOf(unchanged);
        kept.applyDamage(7);
        kept.setMaxMana(10);
        kept.setCurrentMana(2);
        kept.addEffect(new ActiveEffect("burn", "Burn", 3, 2, 0, "GM"));
        map.moveObject(unchanged, 1, 1);
        map.removeObject(removed);
        MonsterToken newcomer = caster(9);
        MonsterToken erased = caster(8);
        map.placeObject(newcomer, 3, 3);
        map.placeObject(erased, 2, 2);
        history.restore(map, snapshot, null, java.util.Set.of(unchanged, removed, newcomer));
        assertEquals(10, TokenSupport.combatOf(rabbit).getCurrentHitPoints());
        assertSame(kept, TokenSupport.combatOf(unchanged));
        assertEquals(13, kept.getCurrentHitPoints());
        assertEquals(2, kept.getCurrentMana());
        assertEquals(1, kept.getActiveEffects().size());
        assertTrue(map.getCell(1, 1).getOccupants().contains(unchanged));
        assertTrue(map.getCell(5, 5).getOccupants().isEmpty());
        assertTrue(map.getCell(3, 3).getOccupants().contains(newcomer));
        assertTrue(map.getCell(2, 2).getOccupants().isEmpty());
    }

    @Test
    public void rewindRestoresPositionsAndVitals() {
        GameMap map = new GameMap("m", "M", 5, 5);
        MonsterToken rabbit = caster(10);
        MonsterToken orc = caster(20);
        map.placeObject(rabbit, 0, 0);
        map.placeObject(orc, 4, 4);
        InitiativeTracker tracker = new InitiativeTracker();
        TurnHistory history = new TurnHistory();
        history.record(map, rabbit, 1);
        history.record(map, orc, 1);

        TokenSupport.combatOf(rabbit).applyDamage(7);
        map.moveObject(orc, 1, 1);
        history.record(map, rabbit, 2);

        TurnHistory.Snapshot previous = history.previousTurnOf(rabbit);
        assertNotNull(previous);
        assertEquals(1, previous.getRound());
        history.restore(map, previous, tracker);

        assertEquals(10, TokenSupport.combatOf(rabbit).getCurrentHitPoints());
        assertTrue(map.getCell(4, 4).getOccupants().contains(orc));
        assertFalse(map.getCell(1, 1).getOccupants().contains(orc));
        assertEquals(1, tracker.round());
        assertNull(history.previousTurnOf(rabbit));
    }
}
