package com.dnd.cli.pages.player;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.core.ConsoleIO;
import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.stats.CoreStats;

/**
 * Small helpers shared by the player-mode pages: resolving the active
 * campaign's repositories and the currently selected {@link PlayerCharacter}
 * from the session-scoped active player character id
 * ({@link CliSession#getActivePlayerCharacterId()}), plus common
 * console output (e.g. the character sheet) usable both from
 * {@link PlayerHomePage} and {@link PlayerMapPage}.
 */
final class PlayerModeSupport {
    private PlayerModeSupport() {
    }

    /** @return repositories for the session's active campaign, or {@code null} if none is selected. */
    static CampaignRepositories repositoriesFor(CliSession session) {
        if (session.getCampaignContext() == null) {
            return null;
        }
        return new CampaignRepositories(session.getCampaignContext().getPath());
    }

    /** @return the currently selected {@link PlayerCharacter}, or {@code null} if none is available. */
    static PlayerCharacter activeCharacter(CliSession session, CampaignRepositories repositories) {
        if (repositories == null || session.getActivePlayerCharacterId() == null) {
            return null;
        }
        return repositories.players().getById(session.getActivePlayerCharacterId());
    }

    /** 5 ft. per grid square is the standard tabletop conversion. */
    static int speedCells(CharacterRace race) {
        int speedFeet = race != null ? race.getSpeed() : 30;
        return Math.max(speedFeet / 5, 1);
    }

    static String displayName(PlayerCharacter pc) {
        return pc.getName() != null && !pc.getName().isEmpty() ? pc.getName() : pc.getId();
    }

    static String resolveName(Object entity) {
        if (entity == null) {
            return "Unknown";
        }
        try {
            Object name = entity.getClass().getMethod("getName").invoke(entity);
            return name != null ? name.toString() : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /** Prints the full character sheet (name/class/race/level/stats/speed) for {@code pc}. */
    static void printStats(ConsoleIO console, PlayerCharacter pc, CampaignRepositories repos) {
        console.println();
        console.println("Name: " + displayName(pc));
        console.println("Class: " + resolveName(repos.classes().getById(pc.getClassId())));
        console.println("Race: " + resolveName(repos.races().getById(pc.getRaceId())));
        console.println("Level: " + pc.getLevel());

        CoreStats stats = pc.getStats();
        if (stats != null) {
            console.println("Strength: " + stats.getStrength());
            console.println("Dexterity: " + stats.getDexterity());
            console.println("Constitution: " + stats.getConstitution());
            console.println("Intelligence: " + stats.getIntelligence());
            console.println("Wisdom: " + stats.getWisdom());
            console.println("Charisma: " + stats.getCharisma());
        }

        CharacterRace race = repos.races().getById(pc.getRaceId());
        if (race != null) {
            console.println("Speed: " + race.getSpeed() + " ft. (" + speedCells(race) + " squares/turn)");
        }
        console.println();
    }
}


