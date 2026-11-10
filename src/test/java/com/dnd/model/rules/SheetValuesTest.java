package com.dnd.model.rules;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.JsonMappers;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.SheetDetails;
import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.world.map.CombatState;
import com.dnd.model.world.map.TokenSupport;
import com.dnd.ui.print.CharacterSheetPdf;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SheetValuesTest {

    private static PlayerCharacter wizard() {
        return new PlayerCharacter("p", "Mira", "wizard", "human", 5,
            new CoreStats(8, 14, 12, 16, 10, 11), new ArrayList<>(), new ArrayList<>());
    }

    @Test
    public void blankOverridesAreCalculated() {
        PlayerCharacter pc = wizard();
        assertEquals(3, SheetValues.proficiencyBonus(pc));
        assertEquals(2, SheetValues.initiative(pc));
        assertEquals("int", SheetValues.spellcastingAbility(pc, null));
        assertEquals(8 + 3 + 3, SheetValues.spellSaveDc(pc, null));
        assertEquals(6, SheetValues.spellAttackBonus(pc, null));
        assertEquals("5d8", SheetValues.hitDice(pc, null));
        assertEquals(30, SheetValues.speed(pc, null, null, id -> null));
        assertEquals(12, FeatureRules.armorClass(pc, null, id -> null, 0).total());
    }

    @Test
    public void overridesWinEverywhere() {
        PlayerCharacter pc = wizard();
        SheetDetails s = pc.getSheet();
        s.setArmorClass(17);
        s.setInitiative(5);
        s.setSpeed(40);
        s.setProficiencyBonus(4);
        s.setPassivePerception(15);
        s.setSpellcastingAbility("Wisdom");
        s.setSpellSaveDc(16);
        s.setHitDice("5d6");
        s.setSavingThrowProficiencies(List.of("dex", "Constitution"));

        assertEquals(17, FeatureRules.armorClass(pc, null, id -> null, 0).total());
        assertEquals(19, FeatureRules.armorClass(pc, null, id -> null, 2).total());
        assertEquals(5, SheetValues.initiative(pc));
        assertEquals(40, SheetValues.speed(pc, null, null, id -> null));
        assertEquals(15, SheetValues.passivePerception(pc));
        assertEquals("wis", SheetValues.spellcastingAbility(pc, null));
        assertEquals(16, SheetValues.spellSaveDc(pc, null));
        assertEquals(4, SheetValues.spellAttackBonus(pc, null));
        assertEquals("5d6", SheetValues.hitDice(pc, null));
        assertTrue(SheetValues.saveProficient(pc, null, "dex"));
        assertTrue(SheetValues.saveProficient(pc, null, "con"));
        assertFalse(SheetValues.saveProficient(pc, null, "int"));
        assertEquals(1 + 4, SheetValues.savingThrow(pc, null, "con"));
    }

    @Test
    public void oldCharacterJsonLoadsWithEmptySheet() throws Exception {
        ObjectMapper mapper = JsonMappers.create();
        PlayerCharacter pc = mapper.readValue("{\"id\":\"p\",\"name\":\"Old\",\"level\":2}", PlayerCharacter.class);
        assertNull(pc.getSheet().getArmorClass());
        assertNull(pc.getSheet().getGold());

        pc.getSheet().setArmorClass(14);
        pc.getSheet().getExtraAttacks().add(new SheetDetails.Attack("Bite", "+4", "1d6 piercing"));
        PlayerCharacter back = mapper.readValue(mapper.writeValueAsString(pc), PlayerCharacter.class);
        assertEquals(Integer.valueOf(14), back.getSheet().getArmorClass());
        assertEquals("Bite", back.getSheet().getExtraAttacks().get(0).getName());
    }

    @Test
    public void goldIsSharedWithTheBattleMapPurse() {
        PlayerCharacter pc = wizard();
        CombatState state = new CombatState();
        state.setGold(7);
        TokenSupport.pullVitals(pc, state);
        assertEquals("unset sheet gold must not wipe the purse", 7, state.getGold());
        TokenSupport.pushVitals(state, pc);
        assertEquals(Integer.valueOf(7), pc.getSheet().getGold());
        pc.getSheet().setGold(30);
        TokenSupport.pullVitals(pc, state);
        assertEquals(30, state.getGold());
    }

    @Test
    public void pdfPrintsSheetDetails() throws Exception {
        CampaignRepositories repos = new CampaignRepositories(
            Paths.get("src", "main", "resources", "data", "custom-campaigns", "zurabaach"));
        PlayerCharacter pc = wizard();
        SheetDetails s = pc.getSheet();
        s.setArmorClass(23);
        s.setProficienciesAndLanguages("Common, Elvish\nThieves' tools");
        s.getExtraAttacks().add(new SheetDetails.Attack("Moonblade", "+9", "2d6 radiant"));
        s.setExtraFeatures("Lucky charm");
        s.setNotes("Owes the guild 40 gold");
        s.setGold(321);
        Path out = Paths.get("target", "test-pdf", "sheet-details.pdf");
        Files.createDirectories(out.getParent());
        CharacterSheetPdf.write(pc, repos, "Test", out);
        String text;
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            text = new PDFTextStripper().getText(doc);
        }
        for (String expected : List.of("23", "Thieves' tools", "Moonblade", "2d6 radiant", "Lucky charm",
            "Owes the guild 40 gold", "321")) {
            assertTrue("missing on sheet: " + expected, text.contains(expected));
        }
    }
}
