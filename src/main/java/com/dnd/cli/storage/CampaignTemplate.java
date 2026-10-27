package com.dnd.cli.storage;

import com.dnd.data.CampaignPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Seeds the files of a newly created campaign.
 *
 * <p>A <em>default</em> campaign is populated from the standard-rules dataset
 * bundled on the classpath under {@code /data/srd}, so it starts with the full
 * catalogue of races, classes, items, spells, monsters, beasts, NPCs, places,
 * battle maps and a sample party. A <em>blank</em> campaign gets the same set of
 * files with empty catalogues, ready for the DM to fill in.</p>
 */
public final class CampaignTemplate {
    /** Classpath folder holding the bundled standard-rules catalogues. */
    private static final String SRD_RESOURCE_ROOT = "/data/srd/";

    private CampaignTemplate() {
    }

    public static void writeTemplate(Path basePath, boolean blank) throws IOException {
        CampaignPaths paths = new CampaignPaths(basePath);

        Files.createDirectories(paths.worldDir());
        Files.createDirectories(paths.playersDir());
        Files.createDirectories(paths.storylineDir());

        write(paths.classesFile(), blank ? blankClassesJson() : standard("classes.json"));
        write(paths.racesFile(), blank ? blankRacesJson() : standard("races.json"));
        write(paths.itemsFile(), blank ? blankItemsJson() : standard("items.json"));
        write(paths.spellsFile(), blank ? blankSpellsJson() : standard("spells.json"));
        write(paths.placesFile(), blank ? blankPlacesJson() : standard("places.json"));
        write(paths.effectsFile(), blank ? blankEffectsJson() : standard("effects.json"));
        write(paths.damageTypesFile(), blank ? blankDamageTypesJson() : standard("damage-types.json"));
        write(paths.npcsFile(), blank ? blankNpcsJson() : standard("npcs.json"));
        write(paths.monstersFile(), blank ? blankMonstersJson() : standard("monsters.json"));
        write(paths.beastsFile(), blank ? blankBeastsJson() : standard("beasts.json"));
        write(paths.playersFile(), blank ? blankPlayersJson() : standard("players.json"));
        write(paths.languagesFile(), blank ? blankLanguagesJson() : standard("languages.json"));
        write(paths.alchemyIngredientsFile(),
            blank ? blankAlchemyIngredientsJson() : standard("alchemy-ingredients.json"));
        write(paths.booksFile(), blank ? blankBooksJson() : standard("books.json"));
        write(paths.diceFile(), blank ? blankDiceJson() : standard("dice.json"));
        write(paths.mapsFile(), blank ? blankMapsJson() : standard("maps.json"));
        write(paths.idRegistryFile(), blank ? blankIdRegistryJson() : standard("id-registry.json"));

        if (!blank) {
            writeStarterStoryline(paths.storylineDir());
        }
    }

    /**
     * Reads one catalogue of the bundled standard-rules dataset off the classpath.
     *
     * @throws IOException if the resource is missing, which means the build did not
     *                     package {@code src/main/resources/data/srd} - seeding a
     *                     campaign with a half-empty catalogue would be worse than
     *                     failing loudly here.
     */
    public static String standard(String fileName) throws IOException {
        try (InputStream stream = CampaignTemplate.class.getResourceAsStream(SRD_RESOURCE_ROOT + fileName)) {
            if (stream == null) {
                throw new IOException("Missing bundled standard-rules catalogue "
                    + SRD_RESOURCE_ROOT + fileName);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Drops a small worked example into the storyline folder so the arc timeline
     * has something to draw and the DM can see the note markers in context.
     */
    private static void writeStarterStoryline(Path storylineDir) throws IOException {
        Path chapter = storylineDir.resolve("Chapter 1 - Trouble in Greenhollow");
        Files.createDirectories(chapter);

        write(chapter.resolve("Session 01 - The Gilded Stag.txt"),
            "Session 01 - The Gilded Stag\n\n"
                + "[READ ALOUD]The common room smells of woodsmoke and barley. A dwarf behind the bar "
                + "is polishing the same tankard she was polishing when you walked in, and she has not "
                + "taken her eyes off you once.[/READ ALOUD]\n\n"
                + "[GM NOTE]Hilda knows about the caravan raids but will only talk after the party "
                + "buys a round, or passes a DC 12 Charisma (Persuasion) check.[/GM NOTE]\n\n"
                + "Battle map if the evening turns ugly: "
                + "[map:gilded-stag-ground|The Gilded Stag - Common Room]\n");

        write(chapter.resolve("Session 02 - The Roadside Camp.txt"),
            "Session 02 - The Roadside Camp\n\n"
                + "[READ ALOUD]Cart tracks leave the road here and cut into the trees. Somewhere ahead "
                + "you can smell woodsmoke that nobody bothered to hide.[/READ ALOUD]\n\n"
                + "[GM NOTE]Four bandits and a bandit captain. They surrender at half strength if the "
                + "captain falls.[/GM NOTE]\n\n"
                + "[map:bandit-camp-map|The Roadside Camp]\n");

        write(chapter.resolve("Session 03 - The Barrow Crypt.txt"),
            "Session 03 - The Barrow Crypt\n\n"
                + "[READ ALOUD]The barrow door has been pulled open from the inside.[/READ ALOUD]\n\n"
                + "[GM NOTE]The wight in the central tomb is the chapter's boss. Skeletons rise in the "
                + "side chambers on round two.[/GM NOTE]\n\n"
                + "[map:barrow-crypt-map|The Barrow Crypt]\n");

        Files.write(chapter.resolve(".storyline-order"), List.of(
            "Session 01 - The Gilded Stag.txt",
            "Session 02 - The Roadside Camp.txt",
            "Session 03 - The Barrow Crypt.txt"), StandardCharsets.UTF_8);
    }

    public static String blankClassesJson() {
        return "{\n  \"classes\": []\n}\n";
    }

    public static String blankRacesJson() {
        return "{\n  \"races\": []\n}\n";
    }

    public static String blankItemsJson() {
        return "{\n  \"items\": []\n}\n";
    }

    public static String blankSpellsJson() {
        return "{\n  \"spells\": []\n}\n";
    }

    public static String blankPlacesJson() {
        return "{\n  \"places\": []\n}\n";
    }

    public static String blankEffectsJson() {
        return "{\n  \"effects\": []\n}\n";
    }

    public static String blankDamageTypesJson() {
        return "{\n  \"damageTypes\": []\n}\n";
    }

    public static String blankNpcsJson() {
        return "{\n  \"npcs\": []\n}\n";
    }

    public static String blankMonstersJson() {
        return "{\n  \"monsters\": []\n}\n";
    }

    public static String blankBeastsJson() {
        return "{\n  \"beasts\": []\n}\n";
    }

    public static String blankPlayersJson() {
        return "{\n  \"players\": []\n}\n";
    }

    public static String blankLanguagesJson() {
        return "{\n  \"languages\": []\n}\n";
    }

    public static String blankAlchemyIngredientsJson() {
        return "{\n  \"ingredients\": []\n}\n";
    }

    public static String blankBooksJson() {
        return "{\n  \"books\": []\n}\n";
    }

    public static String blankDiceJson() {
        return "{\n  \"dice\": []\n}\n";
    }

    public static String blankMapsJson() {
        return "{\n  \"maps\": []\n}\n";
    }

    public static String blankIdRegistryJson() {
        return "{\n  \"entries\": []\n}\n";
    }
}
