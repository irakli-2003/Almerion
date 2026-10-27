package com.dnd.cli.pages;

import com.dnd.cli.core.CliSession;
import com.dnd.cli.core.CommandSpec;
import com.dnd.cli.core.Page;

import java.util.Arrays;
import java.util.List;

import com.dnd.cli.pages.OnlineSessionPage;

public class DmMenuPage implements Page {
    private final Page createPage;
    private final Page editPage;
    private final Page deletePage;
    private final Page openPage;
    private final Page onlinePage;
    private final CliSession session;
    private Page parent;

    public DmMenuPage(Page createPage, Page editPage, Page deletePage, Page openPage, Page onlinePage, CliSession session, Page parent) {
        this.createPage = createPage;
        this.editPage = editPage;
        this.deletePage = deletePage;
        this.openPage = openPage;
        this.onlinePage = onlinePage;
        this.session = session;
        this.parent = parent;
    }

    public void setParent(Page parent) {
        this.parent = parent;
    }

    @Override
    public String getTitle() {
        return "Game Master";
    }

    @Override
    public String getBody() {
        String campaignName = session.getCampaignContext() == null ? "(no campaign selected)" : session.getCampaignContext().getName();
        return "Campaign: " + campaignName + "\nChoose an option:";
    }

    @Override
    public List<CommandSpec> getCommands() {
        return Arrays.asList(
            new CommandSpec("create", "Create new content", createPage),
            new CommandSpec("edit", "Edit existing content", editPage),
            new CommandSpec("delete", "Delete existing content", deletePage),
            new CommandSpec("open", "Open existing content", openPage),
            new CommandSpec("online", "Manage online session" + (session.isOnline() ? " [LIVE]" : ""), onlinePage)
        );
    }

    @Override
    public Page getParent() {
        return parent;
    }
}
