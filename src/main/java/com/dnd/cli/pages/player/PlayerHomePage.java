package com.dnd.cli.pages.player;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.core.CommandSpec;
import com.dnd.cli.core.ConsoleIO;
import com.dnd.cli.core.Page;
import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.item.Item;
import com.dnd.model.magic.Spell;
import com.dnd.model.world.map.GameMap;
import com.dnd.model.world.map.GridCell;
import com.dnd.model.world.map.MapObject;
import com.dnd.model.world.map.PlayerToken;

import java.util.Arrays;
import java.util.List;

/**
 * Player mode "home base": everything a player can do outside of moving on a
 * map - view their own character sheet, inventory, and known spells/abilities
 * - plus the entry point into the interactive {@link PlayerMapPage}.
 *
 * <p>Deliberately exposes nothing belonging to other characters and none of
 * the DM-only tooling (create/edit/delete content, other players' data,
 * etc.) - only what the currently selected {@link PlayerCharacter} itself
 * can see and do.</p>
 */
import com.dnd.cli.pages.player.PlayerOnlineSessionPage;

public class PlayerHomePage implements Page {
    private final CliSession session;
    private final Page onlinePage;
    private Page parent;

    public PlayerHomePage(CliSession session, Page onlinePage, Page parent) {
        this.session = session;
        this.onlinePage = onlinePage;
        this.parent = parent;
    }

    public void setParent(Page parent) {
        this.parent = parent;
    }

    @Override
    public String getTitle() {
        PlayerCharacter pc = activeCharacter();
        return "Player: " + (pc != null ? PlayerModeSupport.displayName(pc) : "(no character selected)");
    }

    @Override
    public String getBody() {
        PlayerCharacter pc = activeCharacter();
        if (pc == null) {
            return "No character selected.";
        }
        CampaignRepositories repos = PlayerModeSupport.repositoriesFor(session);
        String className = PlayerModeSupport.resolveName(repos.classes().getById(pc.getClassId()));
        String raceName = PlayerModeSupport.resolveName(repos.races().getById(pc.getRaceId()));

        StringBuilder sb = new StringBuilder();
        sb.append("Level ").append(pc.getLevel()).append(' ').append(raceName).append(' ').append(className).append('\n');
        sb.append("Items: ").append(pc.getItems() == null ? 0 : pc.getItems().size());
        sb.append("   Spells/Abilities: ").append(pc.getSpells() == null ? 0 : pc.getSpells().size());
        return sb.toString();
    }

    @Override
    public List<CommandSpec> getCommands() {
        return Arrays.asList(
            new CommandSpec("stats",     "View your character's stats",   this::showStats),
            new CommandSpec("inventory", "View/inspect your inventory",   this::showInventory),
            new CommandSpec("abilities", "View your spells/abilities",    this::showAbilities),
            new CommandSpec("map",       "View and act on the map",       this::openMap),
            new CommandSpec("online",    "Join or view live session" + (session.isOnline() ? " [CONNECTED]" : ""), onlinePage)
        );
    }

    @Override
    public Page getParent() {
        return parent;
    }

    // ── Command handlers ──────────────────────────────────────────────────────

    private Page showStats(CliSession s) {
        ConsoleIO console = s.getConsole();
        PlayerCharacter pc = activeCharacter();
        if (pc == null) {
            console.println("No character selected.");
            return this;
        }
        CampaignRepositories repos = PlayerModeSupport.repositoriesFor(s);
        PlayerModeSupport.printStats(console, pc, repos);
        return this;
    }

    private Page showInventory(CliSession s) {
        ConsoleIO console = s.getConsole();
        PlayerCharacter pc = activeCharacter();
        if (pc == null) {
            console.println("No character selected.");
            return this;
        }
        CampaignRepositories repos = PlayerModeSupport.repositoriesFor(s);
        List<PlayerCharacter.PlayerItem> items = pc.getItems();
        if (items == null || items.isEmpty()) {
            console.println("Your inventory is empty.");
            return this;
        }

        console.println("Inventory:");
        for (int i = 0; i < items.size(); i++) {
            PlayerCharacter.PlayerItem pi = items.get(i);
            Item item = repos.items().getById(pi.getItemId());
            String name = item != null ? item.getName() : pi.getItemId();
            console.println("  " + (i + 1) + ". " + name + (pi.isEquipped() ? " (equipped)" : ""));
        }
        console.print("Number to inspect (blank to skip): ");
        String input = console.readLine().trim();
        if (input.isEmpty()) {
            return this;
        }
        int idx = parseIndex(input, items.size());
        if (idx < 0) {
            console.println("Invalid number.");
            return this;
        }

        Item item = repos.items().getById(items.get(idx).getItemId());
        if (item == null) {
            console.println("Item details not found.");
            return this;
        }
        console.println();
        console.println("Name: " + item.getName());
        console.println("Type: " + item.getType());
        console.println("Description: " + item.getDescription());
        console.println("Value: " + item.getValueGold() + " gold");
        console.println("Weight: " + item.getWeight());
        if (item.getDamage() != null) {
            console.println("Damage: " + item.getDamage());
        }
        if (item.getDurability() != null) {
            console.println("Durability: " + item.getDurability());
        }
        console.println("Pickable: " + item.isPickable());
        console.println();
        return this;
    }

    private Page showAbilities(CliSession s) {
        ConsoleIO console = s.getConsole();
        PlayerCharacter pc = activeCharacter();
        if (pc == null) {
            console.println("No character selected.");
            return this;
        }
        CampaignRepositories repos = PlayerModeSupport.repositoriesFor(s);
        List<PlayerCharacter.PlayerSpell> spells = pc.getSpells();
        if (spells == null || spells.isEmpty()) {
            console.println("You have no spells or abilities.");
            return this;
        }

        console.println("Spells / Abilities:");
        for (int i = 0; i < spells.size(); i++) {
            PlayerCharacter.PlayerSpell ps = spells.get(i);
            Spell spell = repos.spells().getById(ps.getSpellId());
            String name = spell != null ? spell.getName() : ps.getSpellId();
            console.println("  " + (i + 1) + ". " + name + " (Rank " + ps.getRank() + ")" + (ps.isActive() ? "" : " [inactive]"));
        }
        console.print("Number to inspect (blank to skip): ");
        String input = console.readLine().trim();
        if (input.isEmpty()) {
            return this;
        }
        int idx = parseIndex(input, spells.size());
        if (idx < 0) {
            console.println("Invalid number.");
            return this;
        }

        Spell spell = repos.spells().getById(spells.get(idx).getSpellId());
        if (spell == null) {
            console.println("Spell details not found.");
            return this;
        }
        console.println();
        console.println("Name: " + spell.getName());
        console.println("Description: " + spell.getDescription());
        console.println("Level: " + spell.getLevel());
        console.println("School: " + spell.getSchool());
        console.println("Mana cost: " + spell.getManaCost());
        console.println("Range: " + spell.getRange());
        console.println("Radius: " + spell.getRadius());
        console.println("Casting method: " + spell.getCastingMethod());
        if (spell.getDamage() != null) {
            console.println("Damage: " + spell.getDamage());
        }
        console.println();
        return this;
    }

    private Page openMap(CliSession s) {
        ConsoleIO console = s.getConsole();
        PlayerCharacter pc = activeCharacter();
        if (pc == null) {
            console.println("No character selected.");
            return this;
        }
        CampaignRepositories repos = PlayerModeSupport.repositoriesFor(s);

        for (GameMap map : repos.maps().list()) {
            PlayerToken token = findToken(map, pc.getId());
            if (token != null) {
                CharacterRace race = repos.races().getById(pc.getRaceId());
                int visionRadius = PlayerModeSupport.speedCells(race) + 2; // see a bit further than you can move in one turn
                return new PlayerMapPage(map, token, repos, visionRadius, this);
            }
        }
        console.println("Your character has not been placed on any map yet. Ask your GM to add you to one.");
        return this;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PlayerCharacter activeCharacter() {
        CampaignRepositories repos = PlayerModeSupport.repositoriesFor(session);
        return PlayerModeSupport.activeCharacter(session, repos);
    }

    private PlayerToken findToken(GameMap map, String characterId) {
        for (List<GridCell> row : map.getGrid()) {
            for (GridCell cell : row) {
                for (MapObject obj : cell.getOccupants()) {
                    if (obj instanceof PlayerToken) {
                        PlayerToken token = (PlayerToken) obj;
                        if (token.getCharacter() != null && characterId.equals(token.getCharacter().getId())) {
                            return token;
                        }
                    }
                }
            }
        }
        return null;
    }

    private int parseIndex(String input, int size) {
        try {
            int idx = Integer.parseInt(input) - 1;
            return idx >= 0 && idx < size ? idx : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

