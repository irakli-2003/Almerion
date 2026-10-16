package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.EntityInfoFormatter;
import com.dnd.data.MapLink;
import com.dnd.data.StorylineService;
import com.dnd.model.world.map.GameMap;
import com.dnd.ui.EntityCategory;
import com.dnd.ui.components.SessionTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Full-window editor for a Storyline session file.
 *
 * <p>Beyond plain text editing it provides a toolbar for the two things a DM does most
 * while prepping: pulling campaign data (NPCs, items, spells, ...) into the notes, and
 * separating prose that is read aloud to players from private DM notes. Those two kinds
 * of content are delimited with explicit markers so the same file can be shown either in
 * full (prep view) or as read-aloud text only (table view).</p>
 */
final class StorylineEditorWindow {

    static final String READ_ALOUD_OPEN = "[READ ALOUD]";
    static final String READ_ALOUD_CLOSE = "[/READ ALOUD]";
    static final String DM_NOTE_OPEN = "[DM NOTE]";
    static final String DM_NOTE_CLOSE = "[/DM NOTE]";

    /**
     * Remembers each file's last scroll offset (keyed by its absolute path, hashed since
     * {@link java.util.prefs.Preferences} keys are capped at 80 characters) so reopening a
     * long session file doesn't dump the DM back at the top every time.
     */
    private static final java.util.prefs.Preferences SCROLL_PREFS =
        java.util.prefs.Preferences.userNodeForPackage(StorylineEditorWindow.class).node("storyline-scroll");

    private final BaseScene owner;
    private final StorylineService service;
    private final CampaignRepositories repos;
    private final Path file;

    private org.fxmisc.richtext.CodeArea area;
    private final javafx.animation.PauseTransition rehighlight = new javafx.animation.PauseTransition(javafx.util.Duration.millis(150));
    private Label statusLabel;
    private Label statsLabel;
    private SessionTimer timer;
    private boolean dirty;
    private Stage editorStage;
    private Stage findStage;
    private TextField findField;
    private final StackPane playersHost = new StackPane();
    private ManagePlayersPanel playersPanel;

    StorylineEditorWindow(BaseScene owner, StorylineService service, CampaignRepositories repos, Path file) {
        this.owner = owner;
        this.service = service;
        this.repos = repos;
        this.file = file;
    }

    void show() {
        Stage stage = new Stage();
        com.dnd.ui.WindowOrder.adopt(stage);
        editorStage = stage;
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(file.getFileName().toString());

        area = new org.fxmisc.richtext.CodeArea(service.readText(file));
        // The initial load is not an edit the DM should be able to undo away.
        area.getUndoManager().forgetHistory();
        area.setWrapText(true);
        com.dnd.ui.components.EntityHoverCard.installMentions(area, repos);
        area.getStyleClass().add("storyline-editor");
        area.setMaxHeight(Double.MAX_VALUE);
        area.setMaxWidth(Double.MAX_VALUE);
        // Markers (music/sound cues, read-aloud, DM notes, map links) get their own colours.
        // Re-colouring waits for a short typing pause so long files stay responsive.
        rehighlight.setOnFinished(e -> highlight());
        highlight();
        area.textProperty().addListener((obs, o, n) -> {
            markDirty(true);
            updateStats(n);
            rehighlight.playFromStart();
        });

        statusLabel = new Label();
        statusLabel.getStyleClass().add("body-label");
        statsLabel = new Label();
        statsLabel.getStyleClass().add("body-label");
        timer = new SessionTimer();

        // Double-clicking a [map:...] marker jumps straight into the battle map. A single
        // click would fight with normal text navigation, and requiring the toolbar button
        // would make the links feel inert.
        area.setOnMouseClicked(e -> {
            if (e.getClickCount() < 2) return;
            MapLink link = MapLink.at(area.getText(), area.getCaretPosition());
            if (link != null) {
                openBattleMap(link);
                return;
            }
            com.dnd.music.MusicCue cue = com.dnd.music.MusicCue.at(area.getText(), area.getCaretPosition());
            if (cue != null) {
                // Double-click also selects the word under the pointer; drop that selection
                // so the click reads as "press the cue", not "select text".
                area.moveTo(area.getCaretPosition());
                playCue(cue);
            }
        });

        // Show a hand over cues and map links so they read as buttons.
        area.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
            boolean onLink = false;
            try {
                java.util.OptionalInt idx = area.hit(e.getX(), e.getY()).getCharacterIndex();
                onLink = idx.isPresent()
                    && (com.dnd.music.MusicCue.at(area.getText(), idx.getAsInt()) != null
                        || MapLink.at(area.getText(), idx.getAsInt()) != null);
            } catch (RuntimeException ignored) {
                // Pointer over empty space below the last paragraph.
            }
            area.setCursor(onLink ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.TEXT);
        });

        org.fxmisc.flowless.VirtualizedScrollPane<org.fxmisc.richtext.CodeArea> areaScroll =
            new org.fxmisc.flowless.VirtualizedScrollPane<>(area);
        areaScroll.setMaxHeight(Double.MAX_VALUE);
        areaScroll.setMaxWidth(Double.MAX_VALUE);

        playersHost.setVisible(false);
        playersHost.setManaged(false);
        HBox.setHgrow(areaScroll, Priority.ALWAYS);
        HBox center = new HBox(areaScroll, playersHost);
        VBox.setVgrow(center, Priority.ALWAYS);

        VBox layout = new VBox(0, buildToolbar(stage), center, buildStatusBar(stage));
        layout.getStyleClass().add("root");

        Scene scene = owner.themedScene(layout, 1000, 720);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN), this::save);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), this::openFind);
        stage.setScene(scene);
        stage.setMaximized(true);
        markDirty(false);
        updateStats(area.getText());

        stage.setOnCloseRequest(e -> {
            if (dirty && !confirmDiscard()) {
                e.consume();
                return;
            }
            persistScrollAndDisposeTimer();
        });
        // Restore where the DM left off, once the area has laid out its paragraphs.
        stage.setOnShown(e -> {
            int savedPar = SCROLL_PREFS.getInt(scrollKey(), 0);
            javafx.application.Platform.runLater(() -> {
                if (savedPar > 0 && savedPar < area.getParagraphs().size()) {
                    area.moveTo(savedPar, 0);
                    area.showParagraphAtTop(savedPar);
                }
            });
        });
        stage.showAndWait();
    }

    /**
     * Persists the current scroll position and releases the session timer. {@code Stage.close()}
     * (used by the "Close" and "Save &amp; Close" buttons) does not fire
     * {@code WINDOW_CLOSE_REQUEST}, so it never reached the {@code setOnCloseRequest} handler
     * below - scrolling was only ever remembered when the window was closed via the OS's own
     * close button. Every path that closes this window now calls this shared method instead.
     */
    private void persistScrollAndDisposeTimer() {
        try {
            SCROLL_PREFS.putInt(scrollKey(), area.getVisibleParagraphs().isEmpty() ? 0 : area.visibleParToAllParIndex(0));
        } catch (RuntimeException ignored) {
            // Nothing laid out yet; keep the previous position.
        }
        rehighlight.stop();
        timer.dispose();
    }

    private void highlight() {
        area.setStyleSpans(0, com.dnd.ui.components.StorylineHighlighter.spans(area.getText()));
    }

    /** Stable per-file key for {@link #SCROLL_PREFS}, short enough to satisfy its 80-char cap. */
    private String scrollKey() {
        return "p" + Integer.toHexString(file.toAbsolutePath().normalize().toString().hashCode());
    }

    // ---------------------------------------------------------------- toolbar

    private Node buildToolbar(Stage stage) {
        FlowPane bar = new FlowPane(6, 6);
        bar.setPadding(new Insets(8, 10, 8, 10));
        bar.getStyleClass().add("editor-toolbar");

        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setRowValignment(javafx.geometry.VPos.TOP);
        bar.getChildren().addAll(
            toolGroup("✍ INSERT",
                buildInsertInfoMenu(),
                buildInsertBlockMenu(),
                buildMapLinkMenu()),
            toolGroup("🎭 MARK TEXT",
                toolButton("Read-Aloud", "Wrap the selected text as prose to read to the players",
                    () -> wrapSelection(READ_ALOUD_OPEN, READ_ALOUD_CLOSE)),
                toolButton("DM Note", "Wrap the selected text as a private DM-only note",
                    () -> wrapSelection(DM_NOTE_OPEN, DM_NOTE_CLOSE))),
            toolGroup("¶ FORMAT",
                toolButton("Heading", "Turn the current line into a section heading", this::insertHeading),
                toolButton("Bullets", "Turn the selected lines into a bullet list", () -> prefixLines("- ")),
                toolButton("Checklist", "Turn the selected lines into a checklist", () -> prefixLines("[ ] ")),
                toolButton("Divider", "Insert a horizontal divider", () -> insertAtCaret("\n" + "-".repeat(60) + "\n"))),
            toolGroup("🔍 FIND & NOTES",
                toolButton("Find", "Find text in this file (Ctrl+F)", this::openFind),
                toolButton("Notes", "Campaign-wide scratch notes shared by every session file", this::openNotes)),
            toolGroup("🎲 AT THE TABLE",
                toolButton("Player View", "Show only the read-aloud passages, for reading at the table",
                    this::openPlayerView),
                toolButton("Open Map Link", "Open the battle map linked at the cursor", this::openMapLinkAtCaret),
                buildMusicMenu()),
            toolGroup("☻ PLAYERS",
                toolButton("Award XP", "Give a player character experience without leaving this file",
                    this::openAwardXp),
                toolButton("Manage Players", "Show or hide the panel with every player and this session's creatures:"
                    + " HP, mana, items and effects", this::toggleManagePlayers)),
            toolGroup("⏱ TIMER", timer),
            toolGroup("💾 FILE",
                toolButton("Save", "Save this file (Ctrl+S)", this::save),
                toolButton("Save & Close", "Save and close the editor",
                    () -> { save(); persistScrollAndDisposeTimer(); stage.close(); }))
        );
        return bar;
    }

    /** A captioned box holding related toolbar controls, so they read as one section. */
    private Node toolGroup(String caption, Node... controls) {
        Label label = new Label(caption);
        label.getStyleClass().add("tool-group-label");
        HBox row = new HBox(5, controls);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox group = new VBox(label, row);
        group.getStyleClass().add("tool-group");
        return group;
    }

    /**
     * "Insert Map" - drops a {@code [map:...]} marker for one of the campaign's maps at the
     * caret. Clicking that marker (or the toolbar button) later opens the battle map, which
     * is how a written encounter turns into a playable one.
     */
    private MenuButton buildMapLinkMenu() {
        MenuButton maps = new MenuButton("Insert Map");
        maps.getStyleClass().add("dnd-button");
        maps.setTooltip(new Tooltip("Insert a link to a battle map"));

        MenuItem placeholder = new MenuItem("Loading...");
        placeholder.setDisable(true);
        maps.getItems().add(placeholder);
        maps.setOnShowing(e -> {
            if (maps.getItems().size() != 1 || maps.getItems().get(0) != placeholder) return;
            List<MenuItem> items = new ArrayList<>();
            for (GameMap map : repos.maps().list()) {
                MenuItem item = new MenuItem(map.getName());
                item.setOnAction(ev -> insertAtCaret(MapLink.marker(map.getId(), map.getName())));
                items.add(item);
            }
            if (items.isEmpty()) {
                MenuItem empty = new MenuItem("No maps in this campaign yet.");
                empty.setDisable(true);
                items.add(empty);
            }
            maps.getItems().setAll(items);
        });
        return maps;
    }

    /**
     * "🎵 Music": opens the music panel, or inserts a [music: ...] / [sfx: ...] cue at the
     * caret. Double-clicking a cue in the text plays it during the session.
     */
    private MenuButton buildMusicMenu() {
        MenuButton music = new MenuButton("🎵 Music");
        music.getStyleClass().add("dnd-button");
        music.setTooltip(new Tooltip("Music panel, or insert a start/end pair of music or sound cues around the selection (double-click a start cue to play, an end cue to stop)"));

        MenuItem open = new MenuItem("Open music panel...");
        open.setOnAction(e -> MusicPanel.open(owner, owner.uiSession.campaignRoot()));
        Menu musicCues = new Menu("Insert music cue");
        Menu soundCues = new Menu("Insert sound cue");
        musicCues.getItems().add(new MenuItem("Loading..."));
        soundCues.getItems().add(new MenuItem("Loading..."));
        music.setOnShowing(e -> {
            com.dnd.music.MusicLibrary library = com.dnd.music.MusicLibrary.load(owner.uiSession.campaignRoot());
            List<MenuItem> playlists = new ArrayList<>();
            for (var playlist : library.sortedPlaylists()) {
                MenuItem item = new MenuItem(playlist.getName());
                item.setOnAction(ev -> insertCuePair(com.dnd.music.MusicCue.Kind.MUSIC, playlist.getName()));
                addToFolder(playlists, playlist.getFolder(), item);
            }
            MenuItem stop = new MenuItem("■ stop (fade out)");
            stop.setOnAction(ev -> insertAtCaret(com.dnd.music.MusicCue.marker(com.dnd.music.MusicCue.Kind.MUSIC, "stop")));
            playlists.add(new SeparatorMenuItem());
            playlists.add(stop);
            musicCues.getItems().setAll(playlists);

            List<MenuItem> sounds = new ArrayList<>();
            for (var sound : library.sortedSounds()) {
                MenuItem item = new MenuItem((sound.isAmbience() ? "∞ " : "") + sound.getName());
                item.setOnAction(ev -> insertCuePair(com.dnd.music.MusicCue.Kind.SFX, sound.getName()));
                addToFolder(sounds, sound.getFolder(), item);
            }
            if (!sounds.isEmpty()) {
                MenuItem stopSounds = new MenuItem("■ stop sounds & ambience");
                stopSounds.setOnAction(ev -> insertAtCaret(com.dnd.music.MusicCue.marker(com.dnd.music.MusicCue.Kind.SFX, "stop")));
                sounds.add(new SeparatorMenuItem());
                sounds.add(stopSounds);
            }
            if (sounds.isEmpty()) {
                MenuItem none = new MenuItem("No sounds yet - add them in the music panel.");
                none.setDisable(true);
                sounds.add(none);
            }
            soundCues.getItems().setAll(sounds);
        });
        music.getItems().addAll(open, new SeparatorMenuItem(), musicCues, soundCues);
        return music;
    }

    /**
     * Inserts an opening cue and its closing cue: around the selected scene text if there is
     * a selection, otherwise on their own lines with the caret between them.
     */
    private void insertCuePair(com.dnd.music.MusicCue.Kind kind, String name) {
        String open = com.dnd.music.MusicCue.marker(kind, name);
        String close = com.dnd.music.MusicCue.closingMarker(kind, name);
        int start = area.getSelection().getStart();
        int end = area.getSelection().getEnd();
        String body = area.getSelectedText();
        area.replaceText(start, end, open + "\n" + body + "\n" + close);
        area.moveTo(start + open.length() + 1 + body.length());
        area.requestFocus();
    }

    /** Adds {@code item} to {@code items}, inside a "📁 folder" submenu when it has a folder (folders listed first). */
    private static void addToFolder(List<MenuItem> items, String folder, MenuItem item) {
        if (folder == null) {
            items.add(item);
            return;
        }
        String label = "📁 " + folder;
        for (MenuItem existing : items) {
            if (existing instanceof Menu m && label.equals(m.getText())) {
                m.getItems().add(item);
                return;
            }
        }
        int at = 0;
        while (at < items.size() && items.get(at) instanceof Menu) at++;
        Menu menu = new Menu(label);
        menu.getItems().add(item);
        items.add(at, menu);
    }

    private void playCue(com.dnd.music.MusicCue cue) {
        com.dnd.music.MusicLibrary library = com.dnd.music.MusicLibrary.load(owner.uiSession.campaignRoot());
        com.dnd.ui.music.MusicPlayer player = com.dnd.ui.music.MusicPlayer.get();
        if (cue.closing() && cue.kind() == com.dnd.music.MusicCue.Kind.SFX && !cue.name().isEmpty()) {
            var sound = library.findSound(cue.name());
            if (sound != null && sound.isAmbience()) player.stopAmbience(); else player.stopSound();
            statusLabel.setText("■ " + cue.name() + " stopped.");
        } else if (cue.isStop() && cue.kind() == com.dnd.music.MusicCue.Kind.SFX) {
            player.stopSound();
            player.stopAmbience();
            statusLabel.setText("Sounds and ambience stopped.");
        } else if (cue.isStop()) {
            player.stop();
            statusLabel.setText("Music fading out.");
        } else if (cue.kind() == com.dnd.music.MusicCue.Kind.SFX) {
            var sound = library.findSound(cue.name());
            statusLabel.setText(sound != null && player.playSound(sound)
                ? (sound.isAmbience() ? "🌧 " : "🔊 ") + sound.getName()
                : "No sound called \"" + cue.name() + "\" - add it in the music panel.");
        } else {
            var playlist = library.findPlaylist(cue.name());
            statusLabel.setText(playlist != null && player.playPlaylist(library, playlist, -1)
                ? "🎵 " + playlist.getName() : "No playable playlist called \"" + cue.name() + "\" - add it in the music panel.");
        }
    }

    /** Opens the map linked at the caret, reporting clearly when there isn't one. */
    private void openMapLinkAtCaret() {
        MapLink link = MapLink.at(area.getText(), area.getCaretPosition());
        if (link == null) {
            statusLabel.setText("Put the cursor inside a [map:...] link first, or insert one with \"Insert Map\".");
            return;
        }
        openBattleMap(link);
    }

    private void openBattleMap(MapLink link) {
        BattleMapWindow window = new BattleMapWindow(owner, owner.uiSession, link.mapId());
        if (!window.mapExists()) {
            statusLabel.setText("The map \"" + link.label() + "\" is no longer in this campaign.");
            return;
        }
        statusLabel.setText("Opened battle map: " + link.label());
        window.show();
    }

    /**
     * "Insert Info" - a cascading menu of campaign catalogs. Hovering a category opens a
     * second menu beside it listing that category's entries; picking one inserts a formatted
     * information block at the caret. Both levels are scrollable, and each entry list has a
     * filter box because catalogs like items and spells get long.
     */
    private MenuButton buildInsertInfoMenu() {
        MenuButton insert = new MenuButton("Insert Info");
        insert.getStyleClass().add("dnd-button");
        insert.setTooltip(new Tooltip("Insert formatted information about a campaign entry"));

        EntityInfoFormatter formatter = new EntityInfoFormatter(repos);
        for (EntityCategory category : EntityInfoFormatter.insertableCategories()) {
            Menu categoryMenu = new Menu(EntityInfoFormatter.categoryLabel(category));
            // A Menu with no items never opens its submenu, so it must be non-empty from the
            // start. The placeholder is swapped for the real (catalog-reading) picker the
            // first time the submenu is shown, so opening the toolbar doesn't parse every
            // catalog in the campaign up front.
            MenuItem placeholder = new MenuItem("Loading...");
            placeholder.setDisable(true);
            categoryMenu.getItems().add(placeholder);
            categoryMenu.setOnShowing(e -> {
                if (categoryMenu.getItems().size() != 1 || categoryMenu.getItems().get(0) != placeholder) return;
                categoryMenu.getItems().setAll(buildEntryPicker(formatter, category, insert));
            });
            insert.getItems().add(categoryMenu);
        }
        return insert;
    }

    private CustomMenuItem buildEntryPicker(EntityInfoFormatter formatter, EntityCategory category, MenuButton owner) {
        List<Object> entries = new ArrayList<>(formatter.list(category));

        ListView<Object> list = new ListView<>();
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : EntityInfoFormatter.nameOf(item));
            }
        });
        list.getItems().setAll(entries);
        list.setPrefHeight(entries.isEmpty() ? 60 : Math.min(280, 26 * entries.size() + 8));
        list.setPrefWidth(240);

        TextField filter = new TextField();
        filter.setPromptText("Filter...");
        filter.getStyleClass().add("dnd-text-field");
        filter.textProperty().addListener((obs, o, needle) -> {
            String lower = needle == null ? "" : needle.toLowerCase();
            List<Object> filtered = new ArrayList<>();
            for (Object entry : entries) {
                if (EntityInfoFormatter.nameOf(entry).toLowerCase().contains(lower)) filtered.add(entry);
            }
            list.getItems().setAll(filtered);
        });

        Runnable insertSelected = () -> {
            Object selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            insertAtCaret("\n" + formatter.format(category, selected));
            owner.hide();
        };
        list.setOnMouseClicked(e -> insertSelected.run());
        list.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) insertSelected.run(); });

        VBox box = new VBox(4, filter, list);
        box.setPadding(new Insets(4));
        if (entries.isEmpty()) {
            box.getChildren().setAll(new Label("No entries in this catalog yet."));
        }

        CustomMenuItem item = new CustomMenuItem(box);
        // Clicking inside the filter box or the list must not dismiss the menu.
        item.setHideOnClick(false);
        return item;
    }

    /** Ready-made scaffolding blocks for common session-prep structures. */
    private MenuButton buildInsertBlockMenu() {
        MenuButton blocks = new MenuButton("Insert Block");
        blocks.getStyleClass().add("dnd-button");

        BiConsumer<String, String> add = (label, template) -> {
            MenuItem item = new MenuItem(label);
            item.setOnAction(e -> insertAtCaret(template));
            blocks.getItems().add(item);
        };

        add.accept("Scene", """

            === SCENE: <name> ===
            Location:
            Present:
            Goal:

            [READ ALOUD]
            <what the players see and hear>
            [/READ ALOUD]

            [DM NOTE]
            <what is really going on>
            [/DM NOTE]
            """);
        add.accept("Encounter", """

            === ENCOUNTER: <name> ===
            Enemies:
            Terrain / hazards:
            Tactics:
            Treasure:
            Scaling if the party struggles:
            """);
        add.accept("NPC beat", """

            --- NPC: <name> ---
            Wants:
            Secret:
            Voice / mannerism:
            Opening line:
            """);
        add.accept("Skill check", """

            [CHECK] <skill> DC <n>
              Success:
              Failure:
            """);
        add.accept("Loot", """

            [LOOT]
              Coins:
              Items:
            """);
        add.accept("Session recap", """

            === RECAP ===
            Previously:
            Open threads:
            Next session hooks:
            """);
        return blocks;
    }

    private Button toolButton(String label, String tooltip, Runnable action) {
        Button b = new Button(label);
        b.getStyleClass().add("dnd-button");
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private Node buildStatusBar(Stage stage) {
        Button close = new Button("Close");
        close.getStyleClass().add("dnd-button");
        close.setOnAction(e -> {
            if (!dirty || confirmDiscard()) {
                persistScrollAndDisposeTimer();
                stage.close();
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, statusLabel, spacer, statsLabel, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 8, 10));
        return bar;
    }

    // ------------------------------------------------------------ text edits

    private void insertAtCaret(String text) {
        int caret = area.getCaretPosition();
        area.insertText(caret, text);
        area.moveTo(caret + text.length());
        area.requestFocus();
    }

    /** Wraps the selection (or the caret position) in {@code open}/{@code close} markers. */
    private void wrapSelection(String open, String close) {
        String selected = area.getSelectedText();
        int start = area.getSelection().getStart();
        int end = area.getSelection().getEnd();
        String body = selected.isEmpty() ? "" : selected;
        String replacement = "\n" + open + "\n" + body + "\n" + close + "\n";
        area.replaceText(start, end, replacement);
        // Drop the caret onto the body line so the DM can just start typing.
        area.moveTo(start + open.length() + 2 + body.length());
        area.requestFocus();
    }

    private void insertHeading() {
        int caret = area.getCaretPosition();
        int lineStart = area.getText().lastIndexOf('\n', Math.max(0, caret - 1)) + 1;
        area.insertText(lineStart, "== ");
        area.requestFocus();
    }

    /** Prefixes every selected line (or the current line) with {@code prefix}. */
    private void prefixLines(String prefix) {
        String text = area.getText();
        int selStart = area.getSelection().getStart();
        int selEnd = area.getSelection().getEnd();
        int start = text.lastIndexOf('\n', Math.max(0, selStart - 1)) + 1;
        int end = text.indexOf('\n', selEnd);
        if (end < 0) end = text.length();
        if (end < start) end = start;

        String[] lines = text.substring(start, end).split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i].isBlank() ? lines[i] : prefix + lines[i]);
        }
        area.replaceText(start, end, sb.toString());
        area.requestFocus();
    }

    // ---------------------------------------------------------------- actions

    /**
     * Lets the DM hand out XP right from the session notes - e.g. right after writing "the
     * party defeats the ogre": opens the Manage Players panel with its Award XP form, where
     * the whole party (or just some players) get the amount in one click.
     */
    private void openAwardXp() {
        if (playersPanel == null || !playersHost.isVisible()) toggleManagePlayers();
        playersPanel.showAwardXp();
    }

    /** Shows or hides the right-hand Manage Players panel (cards for the party and this session's creatures). */
    private void toggleManagePlayers() {
        if (playersPanel == null) {
            playersPanel = new ManagePlayersPanel(owner, repos, file, statusLabel::setText);
            playersHost.getChildren().setAll(playersPanel.node());
        }
        boolean show = !playersHost.isVisible();
        if (show) playersPanel.showList();
        playersHost.setVisible(show);
        playersHost.setManaged(show);
    }

    /**
     * Opens (or re-focuses) the find bar: a small non-modal window owned by the editor so it
     * floats above it while the DM keeps reading. Enter / Shift+Enter step through matches,
     * Esc closes. The search field keeps keyboard focus; hits are only selected in the area.
     */
    private void openFind() {
        String selected = area.getSelectedText();
        boolean prefill = selected != null && !selected.isEmpty() && selected.indexOf('\n') < 0;
        if (findStage != null) {
            if (prefill) findField.setText(selected);
            findStage.show();
            findStage.toFront();
            findStage.requestFocus();
            findField.requestFocus();
            findField.selectAll();
            return;
        }

        findField = new TextField(prefill ? selected : "");
        findField.setPromptText("Find in this file");
        findField.setPrefColumnCount(22);
        CheckBox matchCase = new CheckBox("Match case");
        Label counter = new Label();
        counter.getStyleClass().add("body-label");
        counter.setMinWidth(80);

        Button prev = new Button("Previous");
        Button next = new Button("Next");
        Button close = new Button("Close");
        for (Button b : List.of(prev, next, close)) b.getStyleClass().add("dnd-button");

        Stage fs = new Stage();
        fs.initOwner(editorStage);
        fs.initModality(Modality.NONE);
        fs.setTitle("Find");
        fs.setResizable(false);

        Runnable findNext = () -> runFind(findField.getText(), true, false, matchCase.isSelected(), counter);
        Runnable findPrev = () -> runFind(findField.getText(), false, false, matchCase.isSelected(), counter);
        next.setOnAction(e -> findNext.run());
        prev.setOnAction(e -> findPrev.run());
        close.setOnAction(e -> fs.close());
        // Typing refines the current hit in place instead of jumping past it.
        findField.textProperty().addListener((obs, o, n) ->
            runFind(n, true, true, matchCase.isSelected(), counter));
        matchCase.selectedProperty().addListener((obs, o, n) ->
            runFind(findField.getText(), true, true, n, counter));
        findField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (e.isShiftDown()) findPrev.run(); else findNext.run();
                e.consume();
            }
        });

        HBox row1 = new HBox(6, findField, prev, next);
        row1.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row2 = new HBox(10, matchCase, counter, spacer, close);
        row2.setAlignment(Pos.CENTER_LEFT);
        VBox layout = new VBox(8, row1, row2);
        layout.setPadding(new Insets(10));
        layout.getStyleClass().add("root");

        Scene scene = owner.themedScene(layout, 430, 95);
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                fs.close();
                e.consume();
            }
        });
        fs.setScene(scene);
        fs.setOnHidden(e -> findStage = null);
        findStage = fs;
        fs.show();
        findField.requestFocus();
        findField.selectAll();
        if (!findField.getText().isEmpty()) runFind(findField.getText(), true, true, matchCase.isSelected(), counter);
    }

    /**
     * Runs one search step and updates the selection, counter and status bar.
     *
     * @param inclusive when true a match starting at the current selection start counts
     *                  (used while typing so the current hit is refined, not skipped)
     */
    private void runFind(String needle, boolean forward, boolean inclusive, boolean matchCase, Label counter) {
        if (needle == null || needle.isEmpty()) {
            counter.setText("");
            return;
        }
        String text = area.getText();
        int from;
        if (forward) from = inclusive ? area.getSelection().getStart() : area.getSelection().getEnd();
        else from = area.getSelection().getStart();
        int idx = findMatch(text, needle, from, forward, matchCase, true);
        List<Integer> all = findAll(text, needle, matchCase);
        if (idx < 0) {
            counter.setText("0 of 0");
            statusLabel.setText("No match for \"" + needle + "\".");
            return;
        }
        int ordinal = 1;
        for (int pos : all) if (pos < idx) ordinal++;
        counter.setText(ordinal + " of " + all.size());
        area.selectRange(idx, idx + needle.length());
        scrollIntoView(idx);
        statusLabel.setText("Found \"" + needle + "\" (" + ordinal + " of " + all.size() + ").");
    }

    /**
     * Scrolls the text area so the character at {@code index} is visible. The skin only
     * auto-scrolls to the caret while the area is focused, and the find bar deliberately
     * keeps focus, so the scroll offset is computed here from the character's bounds.
     */
    private void scrollIntoView(int index) {
        javafx.application.Platform.runLater(() -> {
            if (index > area.getLength()) return;
            int par = area.offsetToPosition(index, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
            area.showParagraphAtCenter(par);
        });
    }

    /**
     * Finds the next ({@code forward}) or previous match of {@code needle} in {@code text}.
     * Forward returns the first match starting at or after {@code from}; backward returns the
     * last match starting strictly before {@code from}. With {@code wrap} the search continues
     * from the other end of the text. Returns -1 when there is no match (or the needle is empty).
     */
    static int findMatch(String text, String needle, int from, boolean forward, boolean matchCase, boolean wrap) {
        if (text == null || needle == null || needle.isEmpty() || needle.length() > text.length()) return -1;
        int last = text.length() - needle.length();
        from = Math.max(0, Math.min(from, text.length()));
        if (forward) {
            for (int i = from; i <= last; i++) if (text.regionMatches(!matchCase, i, needle, 0, needle.length())) return i;
            if (wrap) {
                for (int i = 0; i < Math.min(from, last + 1); i++) {
                    if (text.regionMatches(!matchCase, i, needle, 0, needle.length())) return i;
                }
            }
        } else {
            for (int i = Math.min(from - 1, last); i >= 0; i--) {
                if (text.regionMatches(!matchCase, i, needle, 0, needle.length())) return i;
            }
            if (wrap) {
                for (int i = last; i >= Math.max(from, 0); i--) {
                    if (text.regionMatches(!matchCase, i, needle, 0, needle.length())) return i;
                }
            }
        }
        return -1;
    }

    /** Start offsets of every non-overlapping match, used for the "3 of 12" counter. */
    static List<Integer> findAll(String text, String needle, boolean matchCase) {
        List<Integer> out = new ArrayList<>();
        if (text == null || needle == null || needle.isEmpty()) return out;
        int i = 0;
        while (true) {
            int idx = findMatch(text, needle, i, true, matchCase, false);
            if (idx < 0) break;
            out.add(idx);
            i = idx + needle.length();
        }
        return out;
    }

    private void openNotes() {
        Path root = owner.uiSession.campaignRoot();
        if (root == null) root = service.getRoot().getParent();
        CampaignNotesWindow.open(owner, editorStage, root);
    }

    /** Opens a large-type window containing only the read-aloud passages of this file. */
    private void openPlayerView() {
        // Map markers are prep plumbing; the players should hear the map's name, not its id.
        String readAloud = com.dnd.music.MusicCue.stripMarkers(MapLink.stripMarkers(extractReadAloud(area.getText())));
        Stage stage = new Stage();
        com.dnd.ui.WindowOrder.adopt(stage);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Read to players - " + file.getFileName());

        TextArea view = new TextArea(readAloud.isBlank()
            ? "No read-aloud passages in this file yet.\n\nSelect some text and press \"Read-Aloud\" to mark it."
            : readAloud);
        view.setWrapText(true);
        view.setEditable(false);
        view.getStyleClass().addAll("storyline-editor", "player-view");
        VBox.setVgrow(view, Priority.ALWAYS);

        Button close = new Button("Close");
        close.getStyleClass().add("dnd-button");
        close.setOnAction(e -> stage.close());
        HBox buttons = new HBox(close);
        buttons.setPadding(new Insets(8, 10, 10, 10));

        VBox layout = new VBox(0, view, buttons);
        layout.getStyleClass().add("root");
        stage.setScene(owner.themedScene(layout, 800, 600));
        stage.setMaximized(true);
        stage.showAndWait();
    }

    /** Concatenates the contents of every {@code [READ ALOUD]...[/READ ALOUD]} block. */
    static String extractReadAloud(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        while (true) {
            int open = text.indexOf(READ_ALOUD_OPEN, cursor);
            if (open < 0) break;
            int bodyStart = open + READ_ALOUD_OPEN.length();
            int close = text.indexOf(READ_ALOUD_CLOSE, bodyStart);
            if (close < 0) {
                // Unclosed final block: take the rest of the file rather than dropping it.
                sb.append(text.substring(bodyStart).trim()).append("\n\n");
                break;
            }
            sb.append(text, bodyStart, close).append("\n\n");
            cursor = close + READ_ALOUD_CLOSE.length();
        }
        return sb.toString().trim();
    }

    private void updateStats(String text) {
        statsLabel.setText(describeStats(text));
    }

    /**
     * Summarises the file for the status bar: total prep length plus how long the
     * read-aloud passages take to speak, which is what actually eats table time.
     */
    static String describeStats(String text) {
        int total = countWords(text);
        int spoken = countWords(extractReadAloud(text));
        String summary = total + (total == 1 ? " word" : " words");
        if (spoken > 0) {
            summary += "  |  read-aloud: " + spoken + (spoken == 1 ? " word" : " words")
                    + " (~" + speakingMinutes(spoken) + ")";
        }
        return summary;
    }

    static int countWords(String text) {
        if (text == null) return 0;
        String stripped = text.replace(READ_ALOUD_OPEN, " ").replace(READ_ALOUD_CLOSE, " ")
                .replace(DM_NOTE_OPEN, " ").replace(DM_NOTE_CLOSE, " ");
        if (stripped.isBlank()) return 0;
        return stripped.trim().split("\\s+").length;
    }

    /** Roughly 130 words per minute is a comfortable pace for reading aloud at a table. */
    static String speakingMinutes(int words) {
        int seconds = (int) Math.round(words * 60.0 / 130.0);
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private void save() {
        service.writeText(file, area.getText());
        markDirty(false);
        statusLabel.setText("Saved " + file.getFileName() + ".");
    }

    private void markDirty(boolean value) {
        dirty = value;
        if (value) statusLabel.setText("Unsaved changes - Ctrl+S to save.");
        else if (statusLabel.getText() == null || statusLabel.getText().isEmpty()) statusLabel.setText("");
    }

    private boolean confirmDiscard() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
            "You have unsaved changes. Close without saving?", ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText(null);
        owner.styleDialog(alert);
        return alert.showAndWait().filter(bt -> bt == ButtonType.OK).isPresent();
    }
}
