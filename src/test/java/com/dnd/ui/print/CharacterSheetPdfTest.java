package com.dnd.ui.print;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.stats.CoreStats;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CharacterSheetPdfTest {

    private static final Path OUT = Paths.get("target", "test-pdf");
    private static CampaignRepositories repos;

    @BeforeClass
    public static void setUp() throws Exception {
        repos = new CampaignRepositories(Paths.get("src", "main", "resources", "data", "custom-campaigns", "zurabaach"));
        Files.createDirectories(OUT);
    }

    private static String render(PlayerCharacter pc, Path file, int minPages) throws Exception {
        Files.deleteIfExists(file);
        CharacterSheetPdf.write(pc, repos, "Zurabaach", file);
        assertTrue("PDF should exist: " + file, Files.isRegularFile(file));
        assertTrue("PDF should not be empty", Files.size(file) > 1000);
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            assertTrue("expected at least " + minPages + " pages", doc.getNumberOfPages() >= minPages);
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    public void torenSheetHasStatsSpellsAndItems() throws Exception {
        PlayerCharacter toren = repos.players().getById("player-toren");
        assertNotNull(toren);
        String text = render(toren, OUT.resolve("toren.pdf"), 2);

        assertTrue(text.contains(toren.getName()));
        assertTrue(text.contains("Artificer"));
        assertTrue(text.contains(String.valueOf(toren.getStats().getIntelligence())));
        assertTrue("spell name missing", text.contains("Web"));
        assertTrue("item name missing", text.contains("Coal Lump"));
        assertTrue(text.contains("Steam") || text.contains("energy") || text.contains("Energy"));
    }

    @Test
    public void secondPlayerSheetRenders() throws Exception {
        PlayerCharacter tasha = repos.players().getById("player-tasha");
        assertNotNull(tasha);
        String text = render(tasha, OUT.resolve("tasha.pdf"), 1);
        assertTrue(text.contains(tasha.getName()));
        assertTrue(text.contains("Cleric"));
    }

    @Test
    public void everyCampaignPlayerRenders() throws Exception {
        for (PlayerCharacter pc : repos.players().list()) {
            String text = render(pc, OUT.resolve("all-" + pc.getId() + ".pdf"), 1);
            assertTrue(pc.getId(), text.contains(pc.getName()));
        }
    }

    @Test
    public void georgianNameAndUnsupportedGlyphsDoNotCrash() throws Exception {
        PlayerCharacter pc = new PlayerCharacter("player-zurabi", "ზურაბი", "wizard", "human", 3,
                new CoreStats(8, 14, 12, 16, 10, 11), new ArrayList<>(), new ArrayList<>());
        pc.setDescription("მოგზაური ჯადოქარი — 漢字 \uD83D\uDE00 test");
        String text = render(pc, OUT.resolve("georgian.pdf"), 1);
        assertTrue("Georgian name should be extractable", text.contains("ზურაბი"));
        assertTrue(text.contains("მოგზაური"));
    }

    @Test
    public void fileNameIsSanitised() {
        PlayerCharacter pc = new PlayerCharacter();
        pc.setName("Bad:Name?<>|\"*/\\ ");
        assertEquals("Bad_Name________ - Character Sheet", CharacterSheetPrintDialog.fileBaseName(pc));
        pc.setName("ზურაბი");
        assertEquals("ზურაბი - Character Sheet", CharacterSheetPrintDialog.fileBaseName(pc));
    }
}
