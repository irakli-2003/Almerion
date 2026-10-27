package com.dnd.cli.pages;

import com.dnd.cli.core.CommandSpec;
import com.dnd.cli.core.Page;

import java.util.Arrays;
import java.util.List;

public class LandingPage implements Page {
    private final Page campaignSelectionPage;
    private final Page playerPage;

    public LandingPage(Page campaignSelectionPage, Page playerPage) {
        this.campaignSelectionPage = campaignSelectionPage;
        this.playerPage = playerPage;
    }

    @Override
    public String getTitle() {
        return "Almerion";
    }

    @Override
    public String getBody() {
        return "Are you a Game Master or a Player?";
    }

    @Override
    public List<CommandSpec> getCommands() {
        return Arrays.asList(
            new CommandSpec("dm", "Game Master mode", campaignSelectionPage),
            new CommandSpec("player", "Player mode", playerPage)
        );
    }

    @Override
    public Page getParent() {
        return null;
    }
}
