package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.combat.Ability;
import com.dnd.model.combat.CastResolver;
import com.dnd.model.combat.InitiativeTracker;
import com.dnd.model.item.Item;
import com.dnd.model.magic.Spell;
import com.dnd.model.rules.CombatRules;
import com.dnd.model.rules.FeatureRules;
import com.dnd.model.world.map.*;
import com.dnd.ui.ImageStore;
import com.dnd.ui.MapRenderer;
import com.dnd.ui.UiSession;
import com.dnd.ui.components.SessionTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * The battle playground: a live, editable view of one {@link GameMap} used to run an
 * encounter.
 *
 * <p>This is deliberately separate from the map editor. The editor is about building
 * terrain - layers, drawings, passability - while this window is about running a fight:
 * moving creatures, tracking hit points and mana, cycling turn order, and answering
 * "what can this thing actually do?" without leaving the table. Everything it changes is
 * stored on the map's tokens, so a fight can be paused and resumed.</p>
 */
public class BattleMapWindow {

    /** A grid square is five feet, the usual tabletop scale, used to convert spell ranges. */
    private static final double FEET_PER_CELL = 5.0;

    private final BaseScene owner;
    private final UiSession uiSession;
    private final CampaignRepositories repos;
    private final GameMap map;

    private final MapRenderer renderer;
    private final MapRenderer.Decorations decorations = new MapRenderer.Decorations();
    private final InitiativeTracker initiative = new InitiativeTracker();

    private Canvas canvas;
    private StackPane panelHost;
    private VBox rosterPanel;
    private ScrollPane detailScroll;
    private MapObject detailToken;
    private VBox detailBars;
    private Label statusLabel;
    private Label roundLabel;
    private SessionTimer timer;

    private MapObject dragging;
    private boolean dirty;
    /** When on, clicking any square flips it between floor and wall instead of selecting. */
    private boolean wallPaintMode;
    /** Non-null while the DM is painting a terrain type onto squares. */
    private TerrainType paintTerrain;
    private Button wallButton;
    /** Where the token being dragged started, so its movement cost can be measured. */
    private int dragOriginX = -1;
    private int dragOriginY = -1;

    /** The spell or ability the DM has picked up and is about to aim, if any. */
    private CastResolver.Castable armedCast;
    /** Who is casting {@link #armedCast}. */
    private MapObject armedCaster;
    /** The class/race feature behind {@link #armedCast} (Breath Weapon), spent once it lands. */
    private FeatureRules.Action armedCastFeature;

    /** A targeted feature (Lay on Hands, Bardic Inspiration) waiting for the DM to click someone. */
    private FeatureRules.Action armedFeature;
    private MapObject armedFeatureUser;
    /** Pool points to spend when {@link #armedFeature} is a healing pool. */
    private int armedFeaturePoints;

    /** A weapon attack waiting for the DM to click its target. */
    private FeatureRules.Attack armedAttack;
    private MapObject armedAttacker;

    /** Damage type the vitals Damage button deals; remembered between hits. */
    private String vitalsDamageType;

    private final java.util.Random dice = new java.util.Random();

    /** Type ("Player"/"NPC"/"Monster"/"Beast") of the token queued up to be placed by clicking the map. */
    private String armedTokenType;
    /** Catalogue id of the token queued up to be placed by clicking the map. */
    private String armedTokenId;

    /** Board snapshots at each turn start, for the rabbit's time rewind. */
    private final com.dnd.model.combat.TurnHistory history = new com.dnd.model.combat.TurnHistory();
    /** Right-hand quick-placement palette, shown/hidden from the toolbar. */
    private Node tokenPalette;
    private BorderPane rootPane;

    public BattleMapWindow(BaseScene owner, UiSession uiSession, String mapId) {
        this.owner = owner;
        this.uiSession = uiSession;
        this.repos = new CampaignRepositories(uiSession.campaignRoot());
        this.map = repos.maps().getById(mapId);
        this.renderer = new MapRenderer(uiSession.campaignRoot());
    }

    /** True when the requested map id didn't resolve, so callers can report it themselves. */
    public boolean mapExists() {
        return map != null;
    }

    /**
     * Fills in each player's walking speed from their race the first time they appear on a
     * battle map. Races are the only models in the campaign that carry a speed, so NPCs,
     * monsters and beasts keep the default and are adjusted by hand in the detail panel.
     */
    private void seedSpeeds() {
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                for (MapObject token : map.getCell(x, y).getOccupants()) {
                    seedSpeed(token);
                }
            }
        }
    }

    private void seedSpeed(MapObject token) {
        CombatState state = TokenSupport.combatOf(token);
        if (!state.isSpeedSeeded()) {
            state.setSpeedSeeded(true);
            dirty = true;
            if (token instanceof PlayerToken player && player.getCharacter() != null) {
                String raceId = player.getCharacter().getRaceId();
                var race = raceId == null || raceId.isBlank() ? null : repos.races().getById(raceId);
                if (race != null && race.getSpeed() > 0) state.setWalkSpeed(race.getSpeed());
            }
        }
        seedFeatures(token);
    }

    /**
     * Puts a player's class and race traits on their combat state every time the map opens,
     * so levelling up or changing armor between fights is picked up: innate resistances,
     * the drop-to-1-HP trait and Fast/Unarmored Movement speed.
     */
    private void seedFeatures(MapObject token) {
        if (!(token instanceof PlayerToken player) || player.getCharacter() == null) return;
        FeatureRules.Kit kit = kitOf(token);
        if (kit == null) return;
        CombatState state = TokenSupport.combatOf(token);
        int speedBefore = state.getWalkSpeed();
        FeatureRules.seed(state, kit, FeatureRules.worn(player.getCharacter(), repos.items()::getById));
        if (state.getWalkSpeed() != speedBefore) dirty = true;
    }

    /** Class and race features of a player token, or null for anyone else. */
    private FeatureRules.Kit kitOf(MapObject token) {
        if (!(token instanceof PlayerToken pt) || pt.getCharacter() == null) return null;
        var pc = pt.getCharacter();
        var cls = pc.getClassId() == null ? null : repos.classes().getById(pc.getClassId());
        var race = pc.getRaceId() == null ? null : repos.races().getById(pc.getRaceId());
        return FeatureRules.kit(pc, cls, race);
    }

    /** Current Armor Class with a short explanation of where it comes from. */
    private FeatureRules.ArmorClass armorClassOf(MapObject token) {
        CombatState state = TokenSupport.combatOf(token);
        if (token instanceof PlayerToken pt && pt.getCharacter() != null) {
            return FeatureRules.armorClass(pt.getCharacter(), kitOf(token), repos.items()::getById,
                state.effectAcBonus());
        }
        int sheet = TokenSupport.sheetArmorClassOf(token);
        int base = sheet > 0 ? sheet : 10;
        int total = base + state.effectAcBonus();
        return new FeatureRules.ArmorClass(total, sheet > 0 ? "from the stat block" : "no AC on the sheet, 10 assumed");
    }

    public void show() {
        if (map == null) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "That map no longer exists in this campaign.");
            owner.styleDialog(alert);
            alert.showAndWait();
            return;
        }
        map.ensureGridSize();
        syncFromSheets();
        seedSpeeds();
        rebuildInitiative();
        if (initiative.current() != null) history.record(map, initiative.current(), initiative.round());

        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle("Battle: " + map.getName());

        canvas = new Canvas(map.getWidth() * renderer.getCellSize(), map.getHeight() * renderer.getCellSize());
        wireCanvas();

        ScrollPane mapScroll = new ScrollPane(new Group(canvas));
        mapScroll.setStyle("-fx-background: #1a1a2e; -fx-background-color: #1a1a2e;");
        mapScroll.setPannable(false);

        panelHost = new StackPane();
        panelHost.setPrefWidth(340);
        panelHost.setMinWidth(340);
        panelHost.getStyleClass().add("battle-panel");

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        root.setTop(buildToolbar(stage));
        root.setLeft(panelHost);
        root.setCenter(mapScroll);
        root.setBottom(buildStatusBar());
        rootPane = root;

        showRoster();
        render();

        Scene scene = owner.themedScene(root, 1200, 800);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                if (wallPaintMode) {
                    setWallPaintMode(false);
                    return;
                }
                if (paintTerrain != null) {
                    stopTerrainPainting();
                    return;
                }
                if (armedCast != null) {
                    cancelCast();
                    return;
                }
                if (armedFeature != null || armedAttack != null) {
                    cancelFeatureAndAttack();
                    return;
                }
                if (armedTokenType != null) {
                    cancelTokenPlacement();
                    return;
                }
                decorations.selected = null;
                decorations.reachable = null;
                showRoster();
                render();
            }
        });
        stage.setScene(scene);
        stage.setMaximized(true);
        // onHidden covers both the window's X and the toolbar "Close" (stage.close() skips
        // close-request handlers).
        stage.setOnHidden(e -> {
            if (timer != null) timer.dispose();
            if (dirty) saveMap();
            com.dnd.ui.music.MusicPlayer.get().endCombat(musicLibrary(), null);
        });
        stage.show();
        com.dnd.ui.music.MusicPlayer.get().playMapMusic(musicLibrary(), map.getMusicPlaylistId());
    }

    // ── Toolbar ─────────────────────────────────────────────────────────────

    private Node buildToolbar(Stage stage) {
        FlowPane bar = new FlowPane(6, 6);
        bar.setPadding(new Insets(8, 10, 8, 10));
        bar.getStyleClass().add("editor-toolbar");

        roundLabel = new Label();
        roundLabel.getStyleClass().add("section-label");

        timer = new SessionTimer();

        wallButton = new Button("Walls: off");
        wallButton.getStyleClass().add("dnd-button");
        wallButton.setTooltip(new Tooltip(
            "Turn on, then click any square to make it a wall or a floor again. No token needs to be selected."));
        wallButton.setOnAction(e -> setWallPaintMode(!wallPaintMode));

        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setRowValignment(javafx.geometry.VPos.TOP);
        bar.getChildren().addAll(
            toolGroup("⚔ COMBAT",
            tool("Roll NPCs", "Roll initiative for every creature the DM runs, leaving player rolls alone", () -> {
                int rolled = initiative.rollNonPlayers();
                refreshAll();
                status(rolled == 0
                    ? "No non-player combatants to roll for."
                    : "Rolled initiative for " + rolled + " non-player combatant" + (rolled == 1 ? "" : "s") + ".");
                if (rolled > 0) startCombatMusic();
            }),
            tool("Enter Initiative...", "Type the initiative each combatant rolled at the table",
                this::openInitiativeEntryDialog),
            tool("Next Turn ▸", "Advance to the next living combatant", () -> {
                MapObject next = initiative.next();
                if (next != null) history.record(map, next, initiative.round());
                dirty = true;
                pushAllToSheets();
                refreshAll();
                if (next == null) {
                    status("Nothing left standing.");
                } else {
                    List<String> ticked = initiative.lastTurnLog();
                    logLines("Now acting: " + TokenSupport.nameOf(next)
                        + (ticked.isEmpty() ? "" : "  ·  " + ticked.get(0)), ticked);
                }
            }),
            tool("⟲ Rewind", "Turn time back to the previous turn of the selected creature (or the one acting now),"
                + " undoing all damage, effects, spells and movement since - the rabbit's pocket watch", this::rewindTime),
            roundLabel),
            toolGroup("♟ TOKENS",
            tool("＋ Add Token", "Show or hide the creature list: click an entry, then click boxes to place"
                + " as many as you like (Esc stops)", this::toggleTokenPalette),
            dangerTool("✖ Remove Selected", "Take the selected token off the map", this::removeSelected)),
            toolGroup("🗺 MAP",
            wallButton,
            buildTerrainMenu(),
            tool("Health Bars", "Show or hide health and mana bars on tokens", () -> {
                decorations.showHealthBars = !decorations.showHealthBars;
                render();
            }),
            tool("Zoom -", "Shrink the grid", () -> zoom(-8)),
            tool("Zoom +", "Enlarge the grid", () -> zoom(8))),
            toolGroup("🛏 PARTY",
            tool("Rest...", "Give every player on the map a short or long rest", this::openPartyRestDialog)),
            toolGroup("🎵 ATMOSPHERE",
            buildMusicMenu(),
            timer),
            toolGroup("💾 BATTLE",
            tool("Save", "Write the battle state back to the campaign", () -> {
                saveMap();
                status("Battle state saved.");
            }),
            tool("Close", "Close the battle map", stage::close))
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

    private Button dangerTool(String text, String tooltip, Runnable action) {
        Button b = tool(text, tooltip, action);
        b.getStyleClass().setAll("button", "danger-button");
        return b;
    }

    // ── Music ───────────────────────────────────────────────────────────────

    private CheckMenuItem combatMusicItem;

    private com.dnd.music.MusicLibrary musicLibrary() {
        return com.dnd.music.MusicLibrary.load(uiSession.campaignRoot());
    }

    private MenuButton buildMusicMenu() {
        MenuButton menu = new MenuButton("🎵 Music");
        menu.getStyleClass().add("dnd-button");
        menu.setTooltip(new Tooltip("Background music for this map and for combat"));

        MenuItem open = new MenuItem("Open music panel...");
        open.setOnAction(e -> MusicPanel.open(owner, uiSession.campaignRoot()));

        combatMusicItem = new CheckMenuItem("⚔ Combat music");
        combatMusicItem.setSelected(com.dnd.ui.music.MusicPlayer.get().inCombat());
        combatMusicItem.setOnAction(e -> {
            if (combatMusicItem.isSelected()) {
                if (!startCombatMusic()) {
                    combatMusicItem.setSelected(false);
                    status("Choose a combat playlist in the music panel first.");
                }
            } else {
                com.dnd.ui.music.MusicPlayer.get().endCombat(musicLibrary(), map.getMusicPlaylistId());
                status("Combat music off.");
            }
        });

        Menu mapMusic = new Menu("This map's music");
        MenuItem loading = new MenuItem("Loading...");
        mapMusic.getItems().add(loading);
        mapMusic.setOnShowing(e -> {
            ToggleGroup group = new ToggleGroup();
            List<MenuItem> items = new ArrayList<>();
            RadioMenuItem none = new RadioMenuItem("(none)");
            none.setToggleGroup(group);
            none.setSelected(map.getMusicPlaylistId() == null);
            none.setOnAction(ev -> setMapPlaylist(null));
            items.add(none);
            for (var playlist : musicLibrary().sortedPlaylists()) {
                RadioMenuItem item = new RadioMenuItem(playlist.getName());
                item.setToggleGroup(group);
                item.setSelected(playlist.getId().equals(map.getMusicPlaylistId()));
                item.setOnAction(ev -> setMapPlaylist(playlist));
                items.add(item);
            }
            mapMusic.getItems().setAll(items);
        });

        menu.getItems().addAll(open, new SeparatorMenuItem(), combatMusicItem, mapMusic);
        return menu;
    }

    private void setMapPlaylist(com.dnd.music.MusicLibrary.Playlist playlist) {
        map.setMusicPlaylistId(playlist == null ? null : playlist.getId());
        dirty = true;
        saveMap();
        if (playlist != null) {
            com.dnd.ui.music.MusicPlayer.get().playMapMusic(musicLibrary(), playlist.getId());
            status("\"" + playlist.getName() + "\" will play whenever this map is opened.");
        } else {
            status("This map no longer has its own music.");
        }
    }

    /** Switches to the combat playlist (if the campaign has one). Returns false when none is set. */
    private boolean startCombatMusic() {
        var player = com.dnd.ui.music.MusicPlayer.get();
        if (player.inCombat()) return true;
        boolean started = player.startCombat(musicLibrary());
        if (started) {
            if (combatMusicItem != null) combatMusicItem.setSelected(true);
            status("⚔ Combat music!");
        }
        return started;
    }

    private Button tool(String text, String tooltip, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("dnd-button");
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private Node buildStatusBar() {
        statusLabel = new Label("Click a token to inspect it. Drag it to move. Esc clears the selection.");
        statusLabel.getStyleClass().add("body-label");
        HBox bar = new HBox(statusLabel);
        bar.setPadding(new Insets(6, 12, 8, 12));
        bar.setStyle("-fx-background-color: #0f0f1e;");
        return bar;
    }

    private void status(String text) {
        if (statusLabel != null) statusLabel.setText(text);
    }

    private void zoom(double delta) {
        renderer.setCellSize(renderer.getCellSize() + delta);
        canvas.setWidth(map.getWidth() * renderer.getCellSize());
        canvas.setHeight(map.getHeight() * renderer.getCellSize());
        render();
    }

    // ── Canvas interaction ──────────────────────────────────────────────────

    private void wireCanvas() {
        canvas.setOnMousePressed(e -> {
            int cx = (int) (e.getX() / renderer.getCellSize());
            int cy = (int) (e.getY() / renderer.getCellSize());

            if (wallPaintMode) {
                toggleWallAt(cx, cy);
                return;
            }
            if (paintTerrain != null) {
                paintTerrainAt(cx, cy);
                return;
            }
            if (armedCast != null) {
                resolveCastAt(cx, cy);
                return;
            }
            if (armedFeature != null) {
                resolveFeatureAt(cx, cy);
                return;
            }
            if (armedAttack != null) {
                resolveAttackAt(cx, cy);
                return;
            }
            if (armedTokenType != null) {
                placeArmedTokenAt(cx, cy);
                return;
            }

            MapObject hit = tokenAt(e.getX(), e.getY());
            dragging = hit;
            decorations.selected = hit;
            decorations.rangeOrigin = null;
            if (hit != null && hit.getPosition() != null) {
                dragOriginX = hit.getPosition().getX();
                dragOriginY = hit.getPosition().getY();
                dragReachable = TokenSupport.isCreature(hit)
                    ? MovementCalculator.reachableFrom(map, hit, TokenSupport.combatOf(hit).movementRemaining())
                    : null;
            } else {
                clearDragOrigin();
            }
            updateReachable(hit);
            if (hit == null) showRoster();
            else showDetail(hit);
            render();
        });

        canvas.setOnMouseDragged(e -> {
            if (dragging == null || wallPaintMode) return;
            int cx = (int) (e.getX() / renderer.getCellSize());
            int cy = (int) (e.getY() / renderer.getCellSize());
            moveToken(dragging, cx, cy);
        });

        canvas.setOnMouseReleased(e -> {
            if (dragging != null) commitMovementCost(dragging);
            dragging = null;
        });
    }

    private MapObject tokenAt(double px, double py) {
        int cx = (int) (px / renderer.getCellSize());
        int cy = (int) (py / renderer.getCellSize());
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return null;
        List<MapObject> occupants = map.getCell(cx, cy).getOccupants();
        // The topmost occupant is the one drawn last and so the one the DM sees and means.
        return occupants.isEmpty() ? null : occupants.get(occupants.size() - 1);
    }

    private void moveToken(MapObject token, int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return;
        Position current = token.getPosition();
        if (current != null && current.getX() == cx && current.getY() == cy) return;

        // Creatures are held to their speed; scenery and loot are furniture the DM drags freely.
        if (TokenSupport.isCreature(token) && dragReachable != null
                && dragReachable[cy][cx] == MovementCalculator.UNREACHABLE) {
            status(TokenSupport.nameOf(token) + " can't reach that square this turn ("
                + TokenSupport.combatOf(token).movementRemaining() + " squares left).");
            return;
        }

        try {
            map.moveObject(token, cx, cy);
            dirty = true;
            // Mid-drag the overlay keeps showing the range measured from where the move
            // began, since the cost of this move isn't charged until the token is dropped.
            if (dragOriginX < 0) updateReachable(token);
            render();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // Walking into a wall is a normal thing to try, so it reports rather than throws.
            status("Can't move there: " + ex.getMessage());
        }
    }

    /**
     * Reachable costs measured from where this drag began rather than from where the token
     * currently sits, so dragging it around the map doesn't hand it extra movement. Taken
     * once when the drag starts and then held, because the token's own position changes as
     * it is dragged and would otherwise shift the origin under us.
     */
    private int[][] dragReachable;

    /** Charges the token for the ground it actually covered once the drag finishes. */
    private void commitMovementCost(MapObject token) {
        if (!TokenSupport.isCreature(token) || dragOriginX < 0 || dragReachable == null) {
            clearDragOrigin();
            return;
        }
        Position end = token.getPosition();
        if (end == null || (end.getX() == dragOriginX && end.getY() == dragOriginY)) {
            clearDragOrigin();
            return;
        }
        CombatState state = TokenSupport.combatOf(token);
        int spent = dragReachable[end.getY()][end.getX()];
        if (spent > 0) {
            state.setMovementUsed(state.getMovementUsed() + spent);
            status(TokenSupport.nameOf(token) + " moved " + spent + " square" + (spent == 1 ? "" : "s")
                + " - " + state.movementRemaining() + " left this turn.");
        }
        clearDragOrigin();
        updateReachable(token);
        render();
    }

    private void clearDragOrigin() {
        dragOriginX = dragOriginY = -1;
        dragReachable = null;
    }

    /** Recomputes the highlighted movement range for the selected creature. */
    private void updateReachable(MapObject token) {
        if (token == null || !TokenSupport.isCreature(token) || token.getPosition() == null) {
            decorations.reachable = null;
            return;
        }
        decorations.reachable = MovementCalculator.reachableFrom(
            map, token, TokenSupport.combatOf(token).movementRemaining());
    }

    private void removeSelected() {
        MapObject token = decorations.selected;
        if (token == null) {
            status("Select a token on the map first.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Remove " + TokenSupport.nameOf(token) + " from this map?");
        confirm.setHeaderText(null);
        owner.styleDialog(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        map.removeObject(token);
        initiative.remove(token);
        decorations.selected = null;
        dirty = true;
        showRoster();
        refreshAll();
        status("Removed " + TokenSupport.nameOf(token) + ".");
    }

    /**
     * Turns wall painting on or off.
     *
     * <p>Painting takes over the click handler, so the current selection is cleared on the
     * way in: leaving a token selected while every click edits the floor under it was the
     * confusing part of the old behaviour.</p>
     */
    private void setWallPaintMode(boolean on) {
        wallPaintMode = on;
        wallButton.setText(on ? "Walls: ON" : "Walls: off");
        if (on) {
            decorations.selected = null;
            decorations.reachable = null;
            showRoster();
            status("Wall painting on - click any square to toggle it. Click the button again or press Esc to stop.");
        } else {
            status("Wall painting off.");
        }
        render();
    }

    private void toggleWallAt(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return;
        GridCell cell = map.getCell(cx, cy);
        if (cell.isPassable() && !cell.getOccupants().isEmpty()) {
            status("Can't wall off a square with something standing on it.");
            return;
        }
        cell.setPassable(!cell.isPassable());
        dirty = true;
        render();
        status("(" + cx + ", " + cy + ") is now " + (cell.isPassable() ? "floor." : "a wall."));
    }

    // ── Terrain painting ────────────────────────────────────────────────────

    /**
     * One click on a terrain type enters painting mode, so a whole river or scree slope can
     * be laid down in one pass; the same menu offers "Stop painting" while it is active.
     */
    private MenuButton buildTerrainMenu() {
        MenuButton menu = new MenuButton("Terrain");
        menu.getStyleClass().add("dnd-button");
        menu.setTooltip(new Tooltip("Paint difficult, water or climbable ground onto the map"));
        menu.setOnShowing(e -> {
            menu.getItems().clear();
            if (paintTerrain != null) {
                MenuItem stop = new MenuItem("■ Stop painting " + paintTerrain.getLabel().toLowerCase());
                stop.setOnAction(a -> stopTerrainPainting());
                menu.getItems().addAll(stop, new SeparatorMenuItem());
            }
            for (TerrainType terrain : TerrainType.values()) {
                MenuItem item = new MenuItem((terrain == paintTerrain ? "● " : "") + terrain.getLabel());
                item.setOnAction(a -> startTerrainPainting(terrain));
                menu.getItems().add(item);
            }
        });
        menu.getItems().add(new MenuItem("…"));
        return menu;
    }

    private void startTerrainPainting(TerrainType choice) {
        paintTerrain = choice;
        setWallPaintMode(false);
        decorations.selected = null;
        decorations.reachable = null;
        showRoster();
        render();
        status("Painting " + choice.getLabel().toLowerCase() + " ground - click squares. Esc to stop."
            + (choice == TerrainType.NORMAL ? "" : " " + terrainHint(choice)));
    }

    private String terrainHint(TerrainType terrain) {
        if (terrain == TerrainType.WATER) return "Creatures without a swim speed cross it at half pace.";
        if (terrain == TerrainType.CLIMB) return "Creatures without a climb speed cross it at half pace.";
        if (terrain == TerrainType.DIFFICULT) return "Costs double movement to enter.";
        return "";
    }

    private void paintTerrainAt(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return;
        map.getCell(cx, cy).setTerrain(paintTerrain);
        dirty = true;
        render();
    }

    private void stopTerrainPainting() {
        paintTerrain = null;
        status("Terrain painting off.");
        render();
    }

    // ── Initiative entry ────────────────────────────────────────────────────

    /**
     * Lets the DM type the initiative each combatant rolled at the table.
     *
     * <p>Every combatant is listed, players and monsters alike, because the DM is the one
     * holding the keyboard and often reads out numbers for the whole table at once.</p>
     */
    private void openInitiativeEntryDialog() {
        rebuildInitiative();
        List<MapObject> combatants = initiative.order();
        if (combatants.isEmpty()) {
            status("No combatants on the map yet.");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Enter Initiative");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.setPadding(new Insets(16));

        List<Spinner<Integer>> spinners = new ArrayList<>();
        int row = 0;
        grid.addRow(row++, owner.body("Combatant"), owner.body("Initiative"));
        for (MapObject token : combatants) {
            Spinner<Integer> spinner = new Spinner<>(-20, 99, TokenSupport.combatOf(token).getInitiative());
            spinner.setEditable(true);
            spinner.setPrefWidth(90);
            spinners.add(spinner);
            grid.addRow(row++,
                owner.body(TokenSupport.nameOf(token) + "  (" + TokenSupport.kindOf(token) + ")"),
                spinner);
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(420, 40 + combatants.size() * 34));
        dialog.getDialogPane().setContent(scroll);
        owner.styleDialog(dialog);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        for (int i = 0; i < combatants.size(); i++) {
            // Committing the editor's text by hand: a Spinner the DM typed into but never
            // pressed Enter on still holds the old value in its own property otherwise.
            Spinner<Integer> spinner = spinners.get(i);
            commitSpinner(spinner);
            TokenSupport.combatOf(combatants.get(i)).setInitiative(spinner.getValue());
        }
        dirty = true;
        refreshAll();
        status("Initiative updated for " + combatants.size() + " combatant"
            + (combatants.size() == 1 ? "" : "s") + ".");
        if (!combatants.isEmpty()) startCombatMusic();
    }

    private void commitSpinner(Spinner<Integer> spinner) {
        try {
            spinner.getValueFactory().setValue(Integer.parseInt(spinner.getEditor().getText().trim()));
        } catch (NumberFormatException | NullPointerException ignored) {
            // Editor text that isn't a number just leaves the spinner's committed value alone.
        }
    }

    // ── Roster panel ────────────────────────────────────────────────────────

    private void rebuildInitiative() {
        List<MapObject> creatures = new ArrayList<>();
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                for (MapObject obj : map.getCell(x, y).getOccupants()) {
                    if (TokenSupport.isCreature(obj)) creatures.add(obj);
                }
            }
        }
        initiative.setCombatants(creatures);
    }

    private void refreshAll() {
        rebuildInitiative();
        decorations.currentTurn = initiative.current();
        if (roundLabel != null) roundLabel.setText("Round " + initiative.round());
        // Only rebuild the roster when it is the visible panel; otherwise this would throw
        // away a token detail sheet the DM is working in.
        if (panelHost != null && detailScroll == null) showRoster();
        render();
    }

    private void render() {
        decorations.currentTurn = initiative.current();
        if (roundLabel != null) roundLabel.setText("Round " + initiative.round());
        renderer.render(canvas, map, decorations);
    }

    private void showRoster() {
        detailScroll = null;
        detailToken = null;
        detailBars = null;
        rosterPanel = new VBox(10);
        rosterPanel.setPadding(new Insets(12));

        rosterPanel.getChildren().add(owner.sectionLabel("Turn Order"));
        rosterPanel.getChildren().add(buildTurnStack());

        rosterPanel.getChildren().add(new Separator());
        rosterPanel.getChildren().add(owner.sectionLabel("Combatants"));
        for (MapObject token : initiative.order()) {
            rosterPanel.getChildren().add(buildRosterRow(token));
        }
        if (initiative.order().isEmpty()) {
            rosterPanel.getChildren().add(owner.body("No creatures on this map yet. Use \"＋ Add Token\"."));
        }

        ScrollPane scroll = new ScrollPane(rosterPanel);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-pane");
        panelHost.getChildren().setAll(scroll);
    }

    /**
     * The "up next" stack: the acting creature plus the following few. Showing the whole
     * cycle would be meaningless because the order repeats forever.
     */
    private Node buildTurnStack() {
        VBox stack = new VBox(4);
        MapObject current = initiative.current();
        if (current == null) {
            stack.getChildren().add(owner.body("Nobody is standing."));
            return stack;
        }
        stack.getChildren().add(turnChip(current, true));
        for (MapObject next : initiative.upcoming(3)) {
            stack.getChildren().add(turnChip(next, false));
        }
        return stack;
    }

    private Node turnChip(MapObject token, boolean active) {
        Label label = new Label((active ? "▶ " : "   ") + TokenSupport.nameOf(token)
            + "  (" + TokenSupport.combatOf(token).getInitiative() + ")");
        label.getStyleClass().add(active ? "turn-chip-active" : "turn-chip");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setOnMouseClicked(e -> select(token));
        return label;
    }

    private Node buildRosterRow(MapObject token) {
        CombatState state = TokenSupport.combatOf(token);

        Label name = new Label(TokenSupport.nameOf(token));
        name.getStyleClass().add("body-label");
        Label kind = new Label(TokenSupport.kindOf(token));
        kind.getStyleClass().add("subtitle-label");

        VBox text = new VBox(1, name, kind, healthBar(state, 190));
        HBox row = new HBox(8, portrait(token, 34), text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4));
        row.getStyleClass().add("roster-row");
        if (state.isDead()) row.getStyleClass().add("roster-row-dead");
        row.setOnMouseClicked(e -> select(token));
        return row;
    }

    private void select(MapObject token) {
        decorations.selected = token;
        showDetail(token);
        render();
    }

    private Node healthBar(CombatState state, double width) {
        StackPane bar = new StackPane();
        Rectangle back = new Rectangle(width, 10, Color.web("#000000aa"));
        double fraction = state.getMaxHitPoints() > 0 ? state.healthFraction() : 0;
        Rectangle front = new Rectangle(Math.max(0, width * fraction), 10,
            fraction > 0.5 ? Color.web("#4caf50") : fraction > 0.25 ? Color.web("#d9a441") : Color.web("#c0392b"));
        StackPane.setAlignment(front, Pos.CENTER_LEFT);
        StackPane.setAlignment(back, Pos.CENTER_LEFT);

        Label text = new Label(state.getCurrentHitPoints() + " / " + state.getMaxHitPoints()
            + (state.isDead() ? "  DEAD" : state.isDowned() ? "  DYING (" + state.remainingDeathSaves() + ")" : ""));
        text.getStyleClass().add("bar-label");
        bar.getChildren().addAll(back, front, text);
        bar.setMaxWidth(width);
        return bar;
    }

    private Node portrait(MapObject token, double size) {
        String path = TokenSupport.imagePathOf(token);
        if (path != null && uiSession.campaignRoot() != null) {
            Image img = ImageStore.load(uiSession.campaignRoot(), path);
            if (img != null) {
                ImageView view = new ImageView(img);
                view.setFitWidth(size);
                view.setFitHeight(size);
                view.setPreserveRatio(false);
                Circle clip = new Circle(size / 2, size / 2, size / 2);
                view.setClip(clip);
                return view;
            }
        }
        StackPane fallback = new StackPane();
        Circle circle = new Circle(size / 2, MapRenderer.colorFor(token));
        Label initials = new Label(MapRenderer.abbrev(TokenSupport.nameOf(token)));
        initials.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        fallback.getChildren().addAll(circle, initials);
        return fallback;
    }

    // ── Detail panel ────────────────────────────────────────────────────────

    /**
     * Replaces the roster with everything about one creature. The "Back" button at the top
     * left returns to the roster, which is the only way back by design: the panel is a
     * drill-down, not a second window competing for screen space.
     */
    private void showDetail(MapObject token) {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(12));

        Button back = new Button("← Back");
        back.getStyleClass().add("dnd-button");
        back.setOnAction(e -> {
            decorations.rangeOrigin = null;
            showRoster();
            render();
        });
        HBox backRow = new HBox(back);
        backRow.setAlignment(Pos.CENTER_LEFT);
        panel.getChildren().add(backRow);

        CombatState state = TokenSupport.combatOf(token);

        Label name = new Label(TokenSupport.nameOf(token));
        name.getStyleClass().add("title-label");
        Label kind = new Label(TokenSupport.kindOf(token) + " · level " + TokenSupport.levelOf(token));
        kind.getStyleClass().add("subtitle-label");
        detailToken = token;
        FeatureRules.ArmorClass ac = armorClassOf(token);
        Label acLabel = new Label("🛡 AC " + ac.total());
        acLabel.getStyleClass().add("subtitle-label");
        acLabel.setTooltip(new Tooltip(ac.breakdown()));
        panel.getChildren().add(new HBox(10, portrait(token, 56), new VBox(2, name, kind, acLabel)));

        String description = TokenSupport.descriptionOf(token);
        if (description != null && !description.isBlank()) {
            Label desc = new Label(description);
            desc.getStyleClass().add("body-label");
            desc.setWrapText(true);
            panel.getChildren().add(desc);
        }

        panel.getChildren().addAll(new Separator(), owner.sectionLabel("Condition"));
        detailBars = new VBox(6);
        refreshDetailBars(state);
        panel.getChildren().add(detailBars);
        panel.getChildren().add(buildVitalsEditor(token, state));
        panel.getChildren().add(buildDeathSaveControls(token, state));
        panel.getChildren().add(buildMagicEditor(token, state));
        panel.getChildren().add(buildRestButtons(token, state));

        if (!state.getActiveEffects().isEmpty()) {
            panel.getChildren().addAll(new Separator(), owner.sectionLabel("Active Effects"));
            panel.getChildren().add(buildActiveEffectList(state));
        }

        FeatureRules.Kit kit = kitOf(token);
        if (kit != null) {
            panel.getChildren().addAll(new Separator(), owner.sectionLabel("Attacks"));
            panel.getChildren().add(buildAttackList(token, kit));
            panel.getChildren().addAll(new Separator(), owner.sectionLabel("Class & Race Features"));
            panel.getChildren().add(buildFeatureList(token, state, kit));
        }

        panel.getChildren().addAll(new Separator(), owner.sectionLabel("Movement"));
        panel.getChildren().add(buildMovementEditor(token, state));

        panel.getChildren().addAll(new Separator(), owner.sectionLabel("Purse & Stats"));
        panel.getChildren().add(buildPurseEditor(state));
        panel.getChildren().add(buildStatsGrid(token));

        if (TokenSupport.hasSettableLevel(token) || TokenSupport.xpOf(token) >= 0) {
            panel.getChildren().addAll(new Separator(), owner.sectionLabel("Level & XP"));
            panel.getChildren().add(buildLevelXpEditor(token));
        }

        List<Spell> spells = resolveSpells(token);
        if (!spells.isEmpty()) {
            panel.getChildren().addAll(new Separator(), owner.sectionLabel("Spells"));
            panel.getChildren().add(buildSpellList(token, spells));
        }

        List<Ability> abilities = TokenSupport.abilitiesOf(token);
        if (!abilities.isEmpty()) {
            panel.getChildren().addAll(new Separator(), owner.sectionLabel("Abilities"));
            panel.getChildren().add(buildAbilityList(token, abilities));
        }

        List<Item> items = resolveItems(token);
        if (!items.isEmpty()) {
            panel.getChildren().addAll(new Separator(), owner.sectionLabel("Items"));
            panel.getChildren().add(buildItemList(token, items));
        }

        detailScroll = new ScrollPane(panel);
        detailScroll.setFitToWidth(true);
        detailScroll.getStyleClass().add("scroll-pane");
        panelHost.getChildren().setAll(detailScroll);
    }

    private Node buildVitalsEditor(MapObject token, CombatState state) {
        Spinner<Integer> amount = new Spinner<>(1, 999, 5);
        amount.setPrefWidth(80);
        amount.setEditable(true);

        Button damage = new Button("Damage");
        damage.getStyleClass().add("danger-button");
        MenuButton damageType = new MenuButton(damageTypeLabel(vitalsDamageType));
        damageType.setTooltip(new Tooltip("Type of damage dealt by the Damage button - resistances halve it"));
        MenuItem untyped = new MenuItem("Untyped");
        untyped.setOnAction(e -> {
            vitalsDamageType = null;
            damageType.setText(damageTypeLabel(null));
        });
        damageType.getItems().add(untyped);
        for (var type : repos.damageTypes().list()) {
            MenuItem item = new MenuItem(type.getName() != null ? type.getName() : type.getId());
            item.setOnAction(e -> {
                vitalsDamageType = type.getId();
                damageType.setText(damageTypeLabel(type.getId()));
            });
            damageType.getItems().add(item);
        }
        damage.setOnAction(e -> {
            CombatRules.Hit landed = CombatRules.damage(state, amount.getValue(), vitalsDamageType);
            status(TokenSupport.nameOf(token) + " takes " + landed.dealt()
                + (vitalsDamageType == null ? "" : " " + vitalsDamageType) + " damage" + landed.note() + ".");
            afterVitalsChange(token);
        });

        Button heal = new Button("Heal");
        heal.getStyleClass().add("dnd-button");
        heal.setOnAction(e -> {
            state.heal(amount.getValue());
            afterVitalsChange(token);
        });

        Spinner<Integer> maxHp = new Spinner<>(0, 9999, state.getMaxHitPoints());
        maxHp.setPrefWidth(90);
        maxHp.setEditable(true);
        maxHp.valueProperty().addListener((obs, o, n) -> {
            state.setMaxHitPoints(n);
            afterVitalsChange(token);
        });

        Spinner<Integer> currentMana = new Spinner<>(0, 9999, state.getCurrentMana());
        currentMana.setPrefWidth(90);
        currentMana.setEditable(true);
        currentMana.valueProperty().addListener((obs, o, n) -> {
            state.setCurrentMana(n);
            afterVitalsChange(token);
        });

        Spinner<Integer> maxMana = new Spinner<>(0, 9999, state.getMaxMana());
        maxMana.setPrefWidth(90);
        maxMana.setEditable(true);
        maxMana.valueProperty().addListener((obs, o, n) -> {
            state.setMaxMana(n);
            afterVitalsChange(token);
        });

        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        grid.addRow(0, amount, damage, heal);
        grid.add(damageType, 0, 1, 3, 1);
        grid.addRow(2, owner.body("Max HP:"), maxHp);
        grid.addRow(3, owner.body("Mana:"), currentMana, maxMana);
        return grid;
    }

    private static String damageTypeLabel(String typeId) {
        return "Damage type: " + (typeId == null ? "untyped" : typeId);
    }

    private Node buildDeathSaveControls(MapObject token, CombatState state) {
        Button fail = new Button("Failed Save");
        fail.getStyleClass().add("danger-button");
        fail.setTooltip(new Tooltip("Count a failed death save; the third one kills"));
        fail.setOnAction(e -> {
            state.failDeathSave();
            afterVitalsChange(token);
        });

        Button succeed = new Button("Made Save");
        succeed.getStyleClass().add("dnd-button");
        succeed.setOnAction(e -> {
            state.succeedDeathSave();
            afterVitalsChange(token);
        });

        Button kill = new Button("Kill");
        kill.getStyleClass().add("danger-button");
        kill.setOnAction(e -> {
            state.setDead(true);
            afterVitalsChange(token);
        });

        Button revive = new Button("Revive");
        revive.getStyleClass().add("dnd-button");
        revive.setOnAction(e -> {
            state.revive();
            afterVitalsChange(token);
        });

        FlowPane row = new FlowPane(6, 6, fail, succeed, kill, revive);
        return row;
    }

    private Node buildPurseEditor(CombatState state) {
        Spinner<Integer> gold = new Spinner<>(0, 999999, state.getGold());
        gold.setPrefWidth(110);
        gold.setEditable(true);
        gold.valueProperty().addListener((obs, o, n) -> {
            state.setGold(n);
            dirty = true;
        });

        Spinner<Integer> init = new Spinner<>(-20, 99, state.getInitiative());
        init.setPrefWidth(90);
        init.setEditable(true);
        init.valueProperty().addListener((obs, o, n) -> {
            state.setInitiative(n);
            dirty = true;
            rebuildInitiative();
            render();
        });

        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        grid.addRow(0, owner.body("Coins:"), gold);
        grid.addRow(1, owner.body("Initiative:"), init);
        return grid;
    }

    /**
     * Speeds in feet, plus how much of this turn's movement is already spent.
     *
     * <p>A climb or swim speed of zero means the creature has no special mode for that
     * ground and crosses it at the difficult-terrain rate, which is the tabletop default
     * rather than a restriction.</p>
     */
    private Node buildMovementEditor(MapObject token, CombatState state) {
        Spinner<Integer> walk = speedSpinner(state.getWalkSpeed(), n -> {
            state.setWalkSpeed(n);
            afterMovementChange(token);
        });
        Spinner<Integer> climb = speedSpinner(state.getClimbSpeed(), n -> {
            state.setClimbSpeed(n);
            afterMovementChange(token);
        });
        Spinner<Integer> swim = speedSpinner(state.getSwimSpeed(), n -> {
            state.setSwimSpeed(n);
            afterMovementChange(token);
        });

        Label remaining = new Label(state.movementRemaining() + " of " + state.movementSquares() + " squares left");
        remaining.getStyleClass().add("body-label");

        Button reset = new Button("Reset Move");
        reset.getStyleClass().add("dnd-button");
        reset.setTooltip(new Tooltip("Give this creature its full movement back for the current turn"));
        reset.setOnAction(e -> {
            state.resetMovement();
            afterMovementChange(token);
            showDetail(token);
        });

        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        grid.addRow(0, owner.body("Walk (ft):"), walk);
        grid.addRow(1, owner.body("Climb (ft):"), climb);
        grid.addRow(2, owner.body("Swim (ft):"), swim);
        grid.addRow(3, remaining, reset);
        return grid;
    }

    private Spinner<Integer> speedSpinner(int value, java.util.function.IntConsumer onChange) {
        Spinner<Integer> spinner = new Spinner<>(0, 300, value, 5);
        spinner.setPrefWidth(90);
        spinner.setEditable(true);
        spinner.valueProperty().addListener((obs, o, n) -> onChange.accept(n));
        return spinner;
    }

    private void afterMovementChange(MapObject token) {
        dirty = true;
        if (decorations.selected == token) updateReachable(token);
        render();
    }

    private Node buildStatsGrid(MapObject token) {
        var stats = TokenSupport.statsOf(token);
        if (stats == null) return new Label();
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(3);
        addStat(grid, 0, "STR", stats.getStrength());
        addStat(grid, 1, "DEX", stats.getDexterity());
        addStat(grid, 2, "CON", stats.getConstitution());
        addStat(grid, 3, "INT", stats.getIntelligence());
        addStat(grid, 4, "WIS", stats.getWisdom());
        addStat(grid, 5, "CHA", stats.getCharisma());
        return grid;
    }

    private void addStat(GridPane grid, int index, String label, int value) {
        int modifier = TokenSupport.modifier(value);
        Label cell = new Label(label + " " + value + " (" + (modifier >= 0 ? "+" : "") + modifier + ")");
        cell.getStyleClass().add("body-label");
        grid.add(cell, index % 3, index / 3);
    }

    /**
     * Level and XP live on the character/NPC record itself, not on the map's per-encounter
     * {@link com.dnd.model.world.map.CombatState} - so unlike HP/mana/gold, changes here are
     * written straight back to the campaign roster (players.json/npcs.json) via {@link #repos},
     * in addition to the map's own copy, so they stick campaign-wide and not just in this fight.
     */
    private Node buildLevelXpEditor(MapObject token) {
        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        int row = 0;

        if (TokenSupport.hasSettableLevel(token)) {
            Spinner<Integer> level = new Spinner<>(1, 30, TokenSupport.levelOf(token));
            level.setPrefWidth(80);
            level.setEditable(true);
            level.valueProperty().addListener((obs, o, n) -> {
                TokenSupport.setLevelOf(token, n);
                saveTokenToRoster(token);
                dirty = true;
                showDetail(token);
                render();
            });
            grid.addRow(row++, owner.body("Level:"), level);
        }

        int xp = TokenSupport.xpOf(token);
        if (xp >= 0) {
            Spinner<Integer> xpSpinner = new Spinner<>(0, 9_999_999, xp);
            xpSpinner.setPrefWidth(110);
            xpSpinner.setEditable(true);
            xpSpinner.valueProperty().addListener((obs, o, n) -> {
                TokenSupport.addXpOf(token, n - o);
                saveTokenToRoster(token);
                dirty = true;
            });

            Spinner<Integer> award = new Spinner<>(1, 999999, 100, 50);
            award.setPrefWidth(100);
            award.setEditable(true);
            Button addXpBtn = new Button("Award XP");
            addXpBtn.getStyleClass().add("dnd-button");
            addXpBtn.setOnAction(e -> {
                TokenSupport.addXpOf(token, award.getValue());
                saveTokenToRoster(token);
                dirty = true;
                showDetail(token);
            });

            grid.addRow(row++, owner.body("XP:"), xpSpinner);
            grid.addRow(row, award, addXpBtn);
        }
        return grid;
    }

    /**
     * Persists the token's underlying model straight to its campaign repository so a level-up
     * or XP award made mid-fight is visible everywhere else (the DM's entity list, the player's
     * own profile) rather than being trapped in this map's saved snapshot until the map reloads.
     */
    private void saveTokenToRoster(MapObject token) {
        if (token instanceof PlayerToken t && t.getCharacter() != null) {
            repos.players().save(t.getCharacter());
        } else if (token instanceof NpcToken t && t.getNpc() != null) {
            repos.npcs().save(t.getNpc());
        }
    }

    /**
     * Spells and abilities preview their reach on hover so the DM can sweep the list and see
     * each option's coverage without committing, and arm themselves on click so the next
     * click on the map actually casts.
     */
    private Node buildSpellList(MapObject token, List<Spell> spells) {
        VBox box = new VBox(2);
        CombatState state = TokenSupport.combatOf(token);
        for (Spell spell : spells) {
            String label = spell.getName() + "  ·  lvl " + spell.getLevel()
                + (spell.getManaCost() > 0 ? "  ·  " + spell.getManaCost() + " mana" : "")
                + (spell.getRange() > 0 ? "  ·  " + spell.getRange() + " ft" : "")
                + (spell.getRadius() > 0 ? "  ·  " + spell.getRadius() + " ft blast" : "");
            box.getChildren().add(castRow(token, state, CastResolver.of(spell), label, spell.getDescription()));
        }
        return box;
    }

    private Node buildAbilityList(MapObject token, List<Ability> abilities) {
        VBox box = new VBox(2);
        CombatState state = TokenSupport.combatOf(token);
        for (Ability ability : abilities) {
            String label = ability.getName()
                + (ability.getRange() > 0 ? "  ·  " + (int) ability.getRange() + " ft" : "")
                + (ability.getRadius() > 0 ? "  ·  " + (int) ability.getRadius() + " ft blast" : "");
            CastResolver.Castable castable = CastResolver.of(ability, id -> repos.effects().getById(id));
            box.getChildren().add(castRow(token, state, castable, label, ability.getDescription()));
        }
        return box;
    }

    /**
     * One clickable spell or ability row: hover previews its reach, click arms it, and a
     * recharging entry says how long is left instead of pretending to be usable.
     */
    private Node castRow(MapObject token, CombatState state, CastResolver.Castable action,
                         String label, String description) {
        int cooldown = action == null ? 0 : state.cooldownFor(action.getId());
        Label row = new Label(label + (cooldown > 0 ? "  ·  recharging: " + cooldown + "" : ""));
        row.getStyleClass().add(cooldown > 0 ? "muted-label" : "hover-row");
        row.setMaxWidth(Double.MAX_VALUE);
        row.setWrapText(true);
        if (action == null) return row;

        boolean armed = armedCast != null && armedCaster == token
            && action.getId() != null && action.getId().equals(armedCast.getId());
        if (armed) row.getStyleClass().add("selected-row");

        String blocked = CastResolver.blockedReason(token, action);
        String tip = description == null || description.isBlank() ? "" : description + "\n\n";
        row.setTooltip(new Tooltip(tip + (blocked != null ? blocked : "Click to aim, then click a target.")));

        row.setOnMouseEntered(e -> previewRange(token, action.getRangeFeet() / FEET_PER_CELL,
            action.getRadiusFeet() / FEET_PER_CELL));
        row.setOnMouseExited(e -> {
            // Keep the reach on screen while the DM is aiming; it is the aiming guide.
            if (armedCast == null) clearRange();
        });
        row.setOnMouseClicked(e -> armCast(token, action));
        return row;
    }

    // ── Class & race features ───────────────────────────────────────────────

    private Button smallButton(String text, String styleClass) {
        Button b = new Button(text);
        b.getStyleClass().add(styleClass);
        b.setStyle("-fx-min-width: 0; -fx-padding: 3 10;");
        return b;
    }

    /**
     * Rage, Second Wind, Lay on Hands and friends, each with its uses left and a button that
     * actually does it; then the passive traits, resistances included, as reminders.
     */
    private Node buildFeatureList(MapObject token, CombatState state, FeatureRules.Kit kit) {
        VBox box = new VBox(4);
        String name = TokenSupport.nameOf(token);
        for (FeatureRules.Action action : kit.getActions()) {
            int left = action.usesLeft(state.getFeatureUses());
            Label label = new Label(action.getName() + "  ·  " + action.usesLabel(state.getFeatureUses()));
            label.getStyleClass().add(left > 0 ? "body-label" : "muted-label");
            label.setWrapText(true);
            label.setTooltip(new Tooltip(action.getDescription()));
            HBox.setHgrow(label, Priority.ALWAYS);
            label.setMaxWidth(Double.MAX_VALUE);
            HBox row = new HBox(6, label);
            row.setAlignment(Pos.CENTER_LEFT);

            boolean automatic = action.getId().equals(kit.getEnduranceFeature());
            if (automatic) {
                label.setText(label.getText() + "  ·  automatic");
            } else if (action.getKind() == FeatureRules.Kind.HEAL_POOL) {
                Spinner<Integer> points = new Spinner<>(1, Math.max(1, left), Math.max(1, Math.min(5, left)));
                points.setEditable(true);
                points.setPrefWidth(70);
                Button touch = smallButton("Heal…", "dnd-button");
                touch.setDisable(left <= 0);
                touch.setTooltip(new Tooltip("Pick the points, then click who is healed"));
                touch.setOnAction(e -> {
                    commitSpinner(points);
                    armFeature(token, action, points.getValue());
                });
                row.getChildren().addAll(points, touch);
            } else {
                boolean armed = armedFeature == action || (armedCastFeature != null
                    && armedCastFeature.getId().equals(action.getId()) && armedCaster == token);
                Button use = smallButton(action.needsTarget() ? "Target…" : "Use",
                    action.getKind() == FeatureRules.Kind.AREA_DAMAGE ? "danger-button" : "dnd-button");
                use.setDisable(left <= 0 || armed);
                use.setOnAction(e -> useFeature(token, state, action, name));
                row.getChildren().add(use);
            }
            box.getChildren().add(row);
        }

        List<String> resist = new ArrayList<>(state.getResistances());
        for (ActiveEffect effect : state.getActiveEffects()) {
            for (String r : effect.getResistances()) if (!resist.contains(r)) resist.add(r);
        }
        if (!resist.isEmpty()) {
            Label r = new Label("Resists: " + String.join(", ", resist) + " (damage halved)");
            r.getStyleClass().add("body-label");
            r.setWrapText(true);
            box.getChildren().add(r);
        }
        int rageBonus = state.meleeDamageBonus();
        if (rageBonus > 0) box.getChildren().add(owner.body("+" + rageBonus + " damage on Strength melee attacks"));

        for (FeatureRules.Passive passive : kit.getPassives()) {
            Label p = new Label("• " + passive.name());
            p.getStyleClass().add("muted-label");
            p.setWrapText(true);
            if (passive.description() != null && !passive.description().isBlank()) {
                p.setTooltip(new Tooltip(passive.description()));
            }
            box.getChildren().add(p);
        }
        return box;
    }

    private void useFeature(MapObject token, CombatState state, FeatureRules.Action action, String name) {
        switch (action.getKind()) {
            case ALLY_EFFECT, HEAL_POOL -> armFeature(token, action, 1);
            case AREA_DAMAGE -> {
                CastResolver.Castable castable = CastResolver.of(action.toAbility(), id -> repos.effects().getById(id));
                cancelFeatureAndAttackSilently();
                armCast(token, castable);
                if (armedCast == castable) {
                    armedCastFeature = action;
                    showDetail(token);
                }
            }
            default -> afterFeatureUse(token, FeatureRules.useOnSelf(action, state, name, dice));
        }
    }

    private void afterFeatureUse(MapObject token, String message) {
        dirty = true;
        pushToSheet(token);
        rebuildInitiative();
        showDetail(token);
        render();
        status(message);
    }

    /** Picks up a feature that needs a target so the next click on a creature uses it. */
    private void armFeature(MapObject user, FeatureRules.Action action, int points) {
        if (action.usesLeft(TokenSupport.combatOf(user).getFeatureUses()) <= 0) {
            status(action.getName() + " is spent until a " + action.getRecharge().label() + ".");
            return;
        }
        cancelCastSilently();
        armedFeature = action;
        armedFeatureUser = user;
        armedFeaturePoints = points;
        previewRange(user, Math.max(5, action.getRangeFeet()) / FEET_PER_CELL, 0);
        status(action.getName() + " - click who receives it, or press Esc to cancel.");
        showDetail(user);
    }

    private void resolveFeatureAt(int cx, int cy) {
        MapObject user = armedFeatureUser;
        FeatureRules.Action action = armedFeature;
        if (user == null || action == null) return;
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return;
        MapObject target = tokenAtCell(cx, cy);
        if (target == null || !TokenSupport.isCreature(target)) {
            status(action.getName() + " needs a creature.");
            return;
        }
        double reach = Math.max(5, action.getRangeFeet());
        double distance = distanceCells(user, cx, cy) * FEET_PER_CELL;
        if (distance > reach + 0.001) {
            status(action.getName() + " reaches " + (int) reach + " ft but they are " + (int) distance + " ft away.");
            return;
        }
        CombatState userState = TokenSupport.combatOf(user);
        CombatState targetState = TokenSupport.combatOf(target);
        String message = action.getKind() == FeatureRules.Kind.HEAL_POOL
            ? FeatureRules.useHealPool(action, userState, TokenSupport.nameOf(user), targetState,
                TokenSupport.nameOf(target), armedFeaturePoints)
            : FeatureRules.useOnAlly(action, userState, TokenSupport.nameOf(user), targetState,
                TokenSupport.nameOf(target));
        armedFeature = null;
        armedFeatureUser = null;
        clearRange();
        pushToSheet(target);
        refreshAll();
        afterFeatureUse(user, message);
    }

    private void cancelFeatureAndAttackSilently() {
        armedFeature = null;
        armedFeatureUser = null;
        armedAttack = null;
        armedAttacker = null;
    }

    private void cancelFeatureAndAttack() {
        cancelFeatureAndAttackSilently();
        clearRange();
        status("Cancelled.");
        if (detailToken != null) showDetail(detailToken);
    }

    // ── Weapon attacks ──────────────────────────────────────────────────────

    private Node buildAttackList(MapObject token, FeatureRules.Kit kit) {
        VBox box = new VBox(2);
        if (!(token instanceof PlayerToken pt) || pt.getCharacter() == null) return box;
        CombatState state = TokenSupport.combatOf(token);
        int rage = state.meleeDamageBonus();
        for (FeatureRules.Attack attack : FeatureRules.attacks(pt.getCharacter(), kit, repos.items()::getById)) {
            boolean raging = rage > 0 && attack.melee() && attack.strengthBased();
            Label row = new Label(attack.name() + "  ·  " + signed(attack.toHit()) + " to hit  ·  "
                + attack.damageLabel() + (raging ? " (+" + rage + " rage)" : "")
                + "  ·  " + (int) attack.reachFeet() + " ft");
            row.getStyleClass().add("hover-row");
            if (armedAttack != null && armedAttacker == token && armedAttack.id().equals(attack.id())) {
                row.getStyleClass().add("selected-row");
            }
            row.setMaxWidth(Double.MAX_VALUE);
            row.setWrapText(true);
            row.setTooltip(new Tooltip("Click, then click the target."));
            row.setOnMouseEntered(e -> previewRange(token, attack.reachFeet() / FEET_PER_CELL, 0));
            row.setOnMouseExited(e -> {
                if (armedAttack == null && armedCast == null && armedFeature == null) clearRange();
            });
            row.setOnMouseClicked(e -> armAttack(token, attack));
            box.getChildren().add(row);
        }
        if (kit.getExtraAttacks() > 0) {
            box.getChildren().add(owner.body("Extra Attack: " + (kit.getExtraAttacks() + 1)
                + " attacks per Attack action"));
        }
        return box;
    }

    private static String signed(int n) {
        return n >= 0 ? "+" + n : String.valueOf(n);
    }

    private void armAttack(MapObject attacker, FeatureRules.Attack attack) {
        cancelCastSilently();
        armedAttack = attack;
        armedAttacker = attacker;
        previewRange(attacker, attack.reachFeet() / FEET_PER_CELL, 0);
        status("Attacking with " + attack.name() + " - click the target, or press Esc to cancel.");
        showDetail(attacker);
    }

    private void resolveAttackAt(int cx, int cy) {
        MapObject attacker = armedAttacker;
        FeatureRules.Attack attack = armedAttack;
        if (attacker == null || attack == null) return;
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) return;
        MapObject target = tokenAtCell(cx, cy);
        if (target == null || !TokenSupport.isCreature(target) || target == attacker) {
            status(attack.name() + " needs a creature to hit.");
            return;
        }
        double distance = distanceCells(attacker, cx, cy) * FEET_PER_CELL;
        if (distance > attack.reachFeet() + 0.001) {
            status(attack.name() + " reaches " + (int) attack.reachFeet() + " ft but the target is "
                + (int) distance + " ft away.");
            return;
        }
        armedAttack = null;
        armedAttacker = null;
        clearRange();
        showAttackForm(attacker, target, attack);
    }

    /**
     * The DM rolls on the table; this shows what the roll needs to beat, takes the damage
     * dice they rolled and adds every flat bonus (ability, Rage, Fury of the Small) itself,
     * then lands the hit through the resistance rules.
     */
    private void showAttackForm(MapObject attacker, MapObject target, FeatureRules.Attack attack) {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(12));
        CombatState attackerState = TokenSupport.combatOf(attacker);
        CombatState targetState = TokenSupport.combatOf(target);
        FeatureRules.Kit kit = kitOf(attacker);

        Label title = new Label("⚔ " + TokenSupport.nameOf(attacker) + " attacks " + TokenSupport.nameOf(target));
        title.getStyleClass().add("title-label");
        title.setWrapText(true);
        panel.getChildren().add(title);

        int ac = armorClassOf(target).total();
        Label needs = new Label(attack.name() + ": roll d20 " + signed(attack.toHit()) + " against AC " + ac
            + " - a " + Math.max(2, Math.min(20, ac - attack.toHit())) + " or better on the die hits (20 always does).");
        needs.getStyleClass().add("body-label");
        needs.setWrapText(true);
        panel.getChildren().add(needs);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        int row = 0;
        TextField rolled = new TextField();
        rolled.setPrefWidth(80);
        if (attack.dice() != null) {
            rolled.setPromptText(attack.dice());
            rolled.getStyleClass().add("dnd-text-field");
            grid.addRow(row++, owner.body("Rolled " + attack.dice() + ":"), rolled);
        }
        CheckBox crit = new CheckBox("Critical hit (roll the weapon dice twice, enter the total)");
        crit.setWrapText(true);
        if (attack.dice() != null) grid.add(crit, 0, row++, 2, 1);

        TextField sneakRolled = new TextField();
        CheckBox sneak = new CheckBox();
        if (kit != null && kit.getSneakAttackDice() > 0 && attack.finesseOrRanged()) {
            sneak.setText("Sneak Attack - rolled " + kit.getSneakAttackDice() + "d6:");
            sneakRolled.setPrefWidth(80);
            sneakRolled.setPromptText(kit.getSneakAttackDice() + "d6");
            sneakRolled.disableProperty().bind(sneak.selectedProperty().not());
            grid.addRow(row++, sneak, sneakRolled);
        }

        int level = TokenSupport.levelOf(attacker);
        FeatureRules.Action fury = kit == null ? null : kit.action(FeatureRules.FURY_OF_THE_SMALL);
        CheckBox furyBox = new CheckBox();
        if (fury != null && fury.usesLeft(attackerState.getFeatureUses()) > 0) {
            furyBox.setText("Fury of the Small (+" + level + ", target is larger)");
            grid.add(furyBox, 0, row++, 2, 1);
        }
        panel.getChildren().add(grid);

        int rage = attack.melee() && attack.strengthBased() ? attackerState.meleeDamageBonus() : 0;
        List<String> flat = new ArrayList<>();
        flat.add(signed(attack.damageBonus()) + (attack.dice() == null ? " unarmed" : attack.strengthBased() ? " STR" : " DEX"));
        if (rage > 0) flat.add("+" + rage + " Rage");
        Label added = new Label("Added for you: " + String.join(", ", flat)
            + (attack.damageType() == null ? "" : "  ·  " + attack.damageType() + " damage"
            + (targetState.resists(attack.damageType()) ? " (target resists - halved)" : "")));
        added.getStyleClass().add("muted-label");
        added.setWrapText(true);
        panel.getChildren().add(added);

        Label error = new Label();
        error.getStyleClass().add("error-label");

        Button hit = smallButton("Hit", "danger-button");
        Button miss = smallButton("Miss", "dnd-button");
        Button cancel = smallButton("Cancel", "dnd-button");
        hit.setDefaultButton(true);
        hit.setOnAction(e -> {
            int diceTotal;
            int sneakTotal;
            try {
                diceTotal = parseRoll(rolled.getText(), attack.dice() != null);
                sneakTotal = sneak.isSelected() ? parseRoll(sneakRolled.getText(), true) : 0;
            } catch (NumberFormatException ex) {
                error.setText("Enter the rolled dice totals as whole numbers.");
                return;
            }
            int furyBonus = furyBox.isSelected() && fury != null
                && FeatureRules.spend(attackerState.getFeatureUses(), fury, 1) ? level : 0;
            int total = Math.max(0, diceTotal + sneakTotal + attack.damageBonus() + rage + furyBonus);
            CombatRules.Hit landed = CombatRules.damage(targetState, total, attack.damageType());
            List<String> detail = new ArrayList<>();
            if (attack.dice() != null) detail.add(attack.dice() + (crit.isSelected() ? " (crit)" : "") + ": " + diceTotal);
            if (sneakTotal > 0) detail.add("Sneak Attack: " + sneakTotal);
            detail.add("Ability: " + signed(attack.damageBonus()));
            if (rage > 0) detail.add("Rage: +" + rage);
            if (furyBonus > 0) detail.add("Fury of the Small: +" + furyBonus);
            if (landed.resisted()) detail.add("Resisted: " + total + " → " + landed.dealt());
            if (landed.endured()) detail.add(TokenSupport.nameOf(target) + " refuses to fall (1 HP).");
            dirty = true;
            pushToSheet(attacker);
            pushToSheet(target);
            refreshAll();
            showDetail(attacker);
            logLines(TokenSupport.nameOf(attacker) + " hits " + TokenSupport.nameOf(target) + " with "
                + attack.name() + " for " + landed.dealt()
                + (attack.damageType() == null ? "" : " " + attack.damageType()) + landed.note() + ".", detail);
        });
        miss.setOnAction(e -> {
            showDetail(attacker);
            status(TokenSupport.nameOf(attacker) + " misses " + TokenSupport.nameOf(target) + ".");
        });
        cancel.setOnAction(e -> showDetail(attacker));
        HBox buttons = new HBox(8, hit, miss, cancel);
        panel.getChildren().addAll(buttons, error);

        detailScroll = new ScrollPane(panel);
        detailScroll.setFitToWidth(true);
        detailScroll.getStyleClass().add("scroll-pane");
        panelHost.getChildren().setAll(detailScroll);
        javafx.application.Platform.runLater(rolled::requestFocus);
    }

    private static int parseRoll(String text, boolean required) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            if (required) throw new NumberFormatException();
            return 0;
        }
        int value = Integer.parseInt(t);
        if (value < 0) throw new NumberFormatException();
        return value;
    }

    // ── Casting ─────────────────────────────────────────────────────────────

    /**
     * Picks up a spell or ability so the next click on the map casts it, provided the caster
     * can actually afford it right now.
     */
    private void armCast(MapObject caster, CastResolver.Castable action) {
        String blocked = CastResolver.blockedReason(caster, action);
        if (blocked != null) {
            status(blocked);
            return;
        }
        cancelFeatureAndAttackSilently();
        armedCastFeature = null;
        armedCaster = caster;
        armedCast = action;
        previewRange(caster, action.getRangeFeet() / FEET_PER_CELL, action.getRadiusFeet() / FEET_PER_CELL);
        status("Aiming " + action.getName() + " - click "
            + (action.isArea() ? "the centre of the blast" : "a target")
            + ", or press Esc to cancel.");
        showDetail(caster);
    }

    private void cancelCast() {
        armedCast = null;
        armedCaster = null;
        armedCastFeature = null;
        clearRange();
        status("Cast cancelled.");
        if (detailToken != null) showDetail(detailToken);
    }

    /**
     * Resolves the armed cast against the square the DM clicked: the single creature
     * standing there for a targeted spell, or everything inside the blast for an area one.
     */
    private void resolveCastAt(int cx, int cy) {
        MapObject caster = armedCaster;
        CastResolver.Castable action = armedCast;
        if (caster == null || action == null) return;
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) {
            status("That is off the map.");
            return;
        }

        List<MapObject> targets = new ArrayList<>();
        if (action.isArea()) {
            targets.addAll(creaturesWithin(cx, cy, action.getRadiusFeet() / FEET_PER_CELL));
            if (targets.isEmpty()) status("Nothing is caught in the blast.");
        } else {
            MapObject target = tokenAtCell(cx, cy);
            if (target == null || !TokenSupport.isCreature(target)) {
                status(action.getName() + " needs a creature to target.");
                return;
            }
            targets.add(target);
        }

        int rolledDamage = 0;
        if (CastResolver.needsDamageRoll(action)) {
            Integer entered = promptRolledDamage(action);
            if (entered == null) {
                status("Cast cancelled.");
                return;
            }
            rolledDamage = entered;
        }

        double distanceFeet = distanceCells(caster, cx, cy) * FEET_PER_CELL;
        CastResolver.Outcome outcome = CastResolver.cast(caster, action, targets, distanceFeet, rolledDamage);
        if (!outcome.isSuccess()) {
            status(outcome.getMessage());
            return;
        }

        armedCast = null;
        armedCaster = null;
        if (armedCastFeature != null) {
            FeatureRules.spend(TokenSupport.combatOf(caster).getFeatureUses(), armedCastFeature, 1);
            armedCastFeature = null;
        }
        clearRange();
        dirty = true;
        pushToSheet(caster);
        for (MapObject target : targets) pushToSheet(target);
        refreshAll();
        showDetail(caster);
        logLines(outcome.getMessage(), outcome.getLog());
    }

    /**
     * Asks the DM for the damage they actually rolled on the table for this cast (one total,
     * applied to every target caught by it - the same physical roll a real Fireball gets).
     * Returns null if the DM cancels, which aborts the cast entirely.
     */
    private Integer promptRolledDamage(CastResolver.Castable action) {
        String formula = action.getDamage().formula(id -> repos.dice().getById(id));
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
        dialog.setTitle("Rolled damage");
        dialog.setHeaderText(action.getName() + (formula.isBlank() ? "" : " (" + formula + ")"));
        dialog.setContentText("Enter the damage rolled:");
        while (true) {
            var result = dialog.showAndWait();
            if (result.isEmpty()) return null;
            try {
                int value = Integer.parseInt(result.get().trim());
                if (value < 0) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException ex) {
                dialog.setContentText("Enter a whole number 0 or greater:");
                dialog.getEditor().clear();
            }
        }
    }

    /** Every creature standing within {@code radiusCells} of the aim point, blast centre included. */
    private List<MapObject> creaturesWithin(int cx, int cy, double radiusCells) {
        List<MapObject> found = new ArrayList<>();
        int reach = (int) Math.floor(radiusCells);
        for (int y = Math.max(0, cy - reach); y <= Math.min(map.getHeight() - 1, cy + reach); y++) {
            for (int x = Math.max(0, cx - reach); x <= Math.min(map.getWidth() - 1, cx + reach); x++) {
                if (Math.hypot(x - cx, y - cy) > radiusCells + 0.001) continue;
                for (MapObject occupant : map.getCell(x, y).getOccupants()) {
                    if (TokenSupport.isCreature(occupant) && !found.contains(occupant)) found.add(occupant);
                }
            }
        }
        return found;
    }

    /** Chebyshev distance in squares, matching how movement treats diagonals. */
    private double distanceCells(MapObject from, int cx, int cy) {
        Position at = from.getPosition();
        if (at == null) return 0;
        return Math.max(Math.abs(at.getX() - cx), Math.abs(at.getY() - cy));
    }

    private MapObject tokenAtCell(int cx, int cy) {
        List<MapObject> occupants = map.getCell(cx, cy).getOccupants();
        return occupants.isEmpty() ? null : occupants.get(occupants.size() - 1);
    }

    /** Puts a summary in the status bar and the detail behind a tooltip on it. */
    private void logLines(String summary, List<String> detail) {
        status(summary);
        if (statusLabel != null && !detail.isEmpty()) {
            statusLabel.setTooltip(new Tooltip(String.join("\n", detail)));
        }
    }

    /**
     * The effects currently running on a creature, each with the rounds it has left, so the
     * DM can see at a glance what is still burning, freezing or blessing.
     */
    private Node buildActiveEffectList(CombatState state) {
        VBox box = new VBox(2);
        for (ActiveEffect effect : state.getActiveEffects()) {
            Label row = new Label("• " + effect.label()
                + (effect.getDamagePerRound() > 0 ? "  ·  " + effect.getDamagePerRound() + "/round" : "")
                + (effect.getHealingPerRound() > 0 ? "  ·  heals " + effect.getHealingPerRound() + "/round" : ""));
            row.getStyleClass().add("body-label");
            row.setWrapText(true);
            String tip = effect.getDescription() == null || effect.getDescription().isBlank()
                ? effect.getSource() : effect.getDescription() + "\n\nFrom " + effect.getSource();
            if (tip != null) row.setTooltip(new Tooltip(tip));
            box.getChildren().add(row);
        }
        return box;
    }

    private Node buildItemList(MapObject token, List<Item> items) {
        VBox box = new VBox(2);
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        if (token instanceof PlayerToken pt && pt.getCharacter() != null && pt.getCharacter().getItems() != null) {
            for (var carried : pt.getCharacter().getItems()) {
                if (carried != null && carried.getItemId() != null) counts.merge(carried.getItemId(), carried.getQuantity(), Integer::sum);
            }
        }
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (Item item : items) {
            if (!shown.add(item.getId())) continue;
            int n = counts.getOrDefault(item.getId(), 1);
            Label row = new Label("• " + item.getName() + (n > 1 ? "  ×" + n : ""));
            row.getStyleClass().add("body-label");
            row.setWrapText(true);
            box.getChildren().add(row);
        }
        return box;
    }

    private void previewRange(MapObject token, double rangeCells, double radiusCells) {
        decorations.rangeOrigin = token;
        decorations.rangeCells = rangeCells;
        decorations.rangeRadiusCells = radiusCells;
        render();
    }

    private void clearRange() {
        decorations.rangeOrigin = null;
        decorations.rangeCells = 0;
        decorations.rangeRadiusCells = 0;
        render();
    }

    /**
     * Vitals change constantly during a fight, so only the bars are rebuilt. Recreating the
     * whole panel would tear the spinner the DM is mid-edit in out from under them.
     */
    private void refreshDetailBars(CombatState state) {
        if (detailBars == null) return;
        detailBars.getChildren().setAll(healthBar(state, 300));
    }

    private void afterVitalsChange(MapObject token) {
        dirty = true;
        pushToSheet(token);
        rebuildInitiative();
        if (detailToken == token && detailBars != null) {
            refreshDetailBars(TokenSupport.combatOf(token));
        } else {
            showDetail(token);
        }
        render();
    }

    private List<Spell> resolveSpells(MapObject token) {
        List<Spell> spells = new ArrayList<>();
        for (String id : TokenSupport.spellIdsOf(token)) {
            Spell spell = repos.spells().getById(id);
            if (spell != null) spells.add(spell);
        }
        // Crafted devices in the pack lend their action to whoever carries them.
        for (String id : TokenSupport.itemIdsOf(token)) {
            if (!(repos.items().getById(id) instanceof com.dnd.model.item.crafting.Device device)) continue;
            Spell spell = device.getSpellId() == null ? null : repos.spells().getById(device.getSpellId());
            if (spell != null && spells.stream().noneMatch(s -> s.getId().equals(spell.getId()))) spells.add(spell);
        }
        return spells;
    }

    private List<Item> resolveItems(MapObject token) {
        List<Item> items = new ArrayList<>();
        for (String id : TokenSupport.itemIdsOf(token)) {
            Item item = repos.items().getById(id);
            if (item != null) items.add(item);
        }
        return items;
    }

    // ── Adding tokens ───────────────────────────────────────────────────────

    /** Picks up a catalogue entry so the next click on an empty, passable box places it there. */
    private void armTokenPlacement(String type, String id) {
        armedTokenType = type;
        armedTokenId = id;
        status("Click an empty box on the map to place it, or press Esc to cancel.");
    }

    private void cancelTokenPlacement() {
        armedTokenType = null;
        armedTokenId = null;
        status("Placement cancelled.");
    }

    /** Resolves a queued-up token placement against the clicked box, if it is actually free. */
    private void placeArmedTokenAt(int cx, int cy) {
        String type = armedTokenType;
        String id = armedTokenId;
        if (type == null || id == null) return;
        if (cx < 0 || cy < 0 || cx >= map.getWidth() || cy >= map.getHeight()) {
            status("That is off the map.");
            return;
        }
        GridCell cell = map.getCell(cx, cy);
        if (!cell.isPassable() || cell.getOccupants().stream().anyMatch(TokenSupport::isCreature)) {
            status("That box isn't free - pick another one, or press Esc to cancel.");
            return;
        }
        armedTokenType = null;
        armedTokenId = null;
        addToken(type, id, cx, cy);
        // Monsters, NPCs and beasts stay "in hand" so a whole pack can be dropped with a few
        // clicks; a player character only exists once.
        if (!"Player".equals(type)) {
            armedTokenType = type;
            armedTokenId = id;
            status(statusLabel.getText() + "  ·  Click another box to place one more, Esc to stop.");
        }
    }

    private void addToken(String type, String id, int x, int y) {

        MapObject token = switch (type) {
            case "Player" -> {
                var pc = repos.players().getById(id);
                yield pc == null ? null : new PlayerToken(pc);
            }
            case "NPC" -> {
                var npc = repos.npcs().getById(id);
                yield npc == null ? null : new NpcToken(npc);
            }
            case "Monster" -> {
                var monster = repos.monsters().getById(id);
                yield monster == null ? null : new MonsterToken(monster);
            }
            case "Beast" -> {
                var beast = repos.beasts().getById(id);
                yield beast == null ? null : new BeastToken(beast);
            }
            default -> null;
        };
        if (token == null) {
            status("Could not find that entry in the campaign.");
            return;
        }
        try {
            map.placeObject(token, x, y);
        } catch (RuntimeException ex) {
            status("Could not place the token: " + ex.getMessage());
            return;
        }
        // Rolling straight away means a reinforcement slots into the running order instead of
        // sitting at initiative zero until the DM notices.
        CombatState state = TokenSupport.combatOf(token);
        state.setInitiative(TokenSupport.rollInitiative(token));
        seedSpeed(token);
        dirty = true;
        refreshAll();
        showRoster();
        status("Added " + TokenSupport.nameOf(token) + " at (" + x + "," + y + ").");
    }

    private void saveMap() {
        pushAllToSheets();
        repos.maps().save(map);
        dirty = false;
    }

    // ── Character sheet sync ────────────────────────────────────────────────

    private List<MapObject> allCreatures() {
        List<MapObject> out = new ArrayList<>();
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                for (MapObject obj : map.getCell(x, y).getOccupants()) {
                    if (TokenSupport.isCreature(obj)) out.add(obj);
                }
            }
        }
        return out;
    }

    /** Pulls the latest character sheets into every player token: the sheet is the truth. */
    private void syncFromSheets() {
        for (MapObject token : allCreatures()) {
            if (!(token instanceof PlayerToken pt) || pt.getCharacter() == null) continue;
            var sheet = repos.players().getById(pt.getCharacter().getId());
            if (sheet == null) continue;
            var cls = sheet.getClassId() == null ? null : repos.classes().getById(sheet.getClassId());
            boolean seeded = com.dnd.model.character.Progression.ensureSlots(sheet, cls);
            boolean hadVitals = sheet.getMaxHitPoints() > 0;
            TokenSupport.refreshFromSheet(pt, sheet);
            if (seeded || !hadVitals) repos.players().save(sheet);
            dirty = true;
        }
    }

    /** Writes one player token's live vitals, slots, effects, level and XP back to the sheet. */
    private void pushToSheet(MapObject token) {
        if (!(token instanceof PlayerToken pt) || pt.getCharacter() == null) return;
        var onToken = pt.getCharacter();
        var sheet = repos.players().getById(onToken.getId());
        if (sheet == null) return;
        CombatState state = TokenSupport.combatOf(token);
        TokenSupport.pushVitals(state, sheet);
        TokenSupport.pushVitals(state, onToken);
        sheet.setLevel(onToken.getLevel());
        sheet.setXp(onToken.getXp());
        sheet.setItems(onToken.getItems());
        repos.players().save(sheet);
    }

    private void pushAllToSheets() {
        for (MapObject token : allCreatures()) pushToSheet(token);
    }

    // ── Time rewind ─────────────────────────────────────────────────────────

    private void rewindTime() {
        MapObject actor = decorations.selected != null && TokenSupport.isCreature(decorations.selected)
            ? decorations.selected : initiative.current();
        if (actor == null) {
            status("Nobody to rewind for.");
            return;
        }
        var snapshot = history.previousTurnOf(actor);
        if (snapshot == null) {
            status("No earlier turn of " + TokenSupport.nameOf(actor) + " has been recorded yet.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Turn time back to the start of " + TokenSupport.nameOf(actor) + "'s turn in round "
                + snapshot.getRound() + "?\nEvery hit, spell, effect and step since then is undone.",
            ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("⟲ Rewind time");
        owner.styleDialog(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        history.restore(map, snapshot, initiative);
        cancelCastSilently();
        decorations.selected = null;
        decorations.reachable = null;
        dirty = true;
        pushAllToSheets();
        refreshAll();
        showRoster();
        status("⟲ Time rewinds... it is round " + snapshot.getRound() + ", "
            + TokenSupport.nameOf(actor) + "'s turn again.");
    }

    private void cancelCastSilently() {
        armedCast = null;
        armedCaster = null;
        armedCastFeature = null;
        armedFeature = null;
        armedFeatureUser = null;
        armedAttack = null;
        armedAttacker = null;
        clearRange();
    }

    // ── Spell slots & rests ─────────────────────────────────────────────────

    private Node buildMagicEditor(MapObject token, CombatState state) {
        VBox box = new VBox(6);
        com.dnd.model.magic.CastingResource resource = state.effectiveCastingResource();
        box.getChildren().add(owner.body("Casts with: " + resource));
        if (!state.getSpellSlots().isEmpty()) {
            GridPane grid = new GridPane();
            grid.setHgap(6);
            grid.setVgap(4);
            int row = 0;
            for (com.dnd.model.magic.SpellSlot slot : state.getSpellSlots()) {
                Spinner<Integer> current = new Spinner<>(0, Math.max(0, slot.getMax()), slot.getCurrent());
                current.setPrefWidth(80);
                current.setEditable(true);
                current.valueProperty().addListener((obs, o, n) -> {
                    slot.setCurrent(n);
                    dirty = true;
                    pushToSheet(token);
                });
                grid.addRow(row++, owner.body(com.dnd.model.magic.SpellSlots.rowLabel(slot.getLevel(), resource) + ":"),
                    current, owner.body("/ " + slot.getMax()));
            }
            box.getChildren().add(grid);
        }
        return box;
    }

    private Node buildRestButtons(MapObject token, CombatState state) {
        Button shortRest = new Button("Short Rest");
        shortRest.getStyleClass().add("dnd-button");
        shortRest.setTooltip(new Tooltip("Recover the HP rolled on hit dice, pact slots, half the missing mana and short-rest features; timed effects end"));
        shortRest.setOnAction(e -> {
            Integer hp = askNumber("Short rest", TokenSupport.nameOf(token) + " - HP recovered from hit dice rolled:", 0);
            if (hp == null) return;
            com.dnd.model.character.Progression.shortRest(state, isPactCaster(token), hp, TokenSupport.levelOf(token));
            afterVitalsChange(token);
            showDetail(token);
            status(TokenSupport.nameOf(token) + " takes a short rest.");
        });
        Button longRest = new Button("Long Rest");
        longRest.getStyleClass().add("dnd-button");
        longRest.setTooltip(new Tooltip("Full HP, mana, spell slots and feature uses; every effect and cooldown cleared"));
        longRest.setOnAction(e -> {
            com.dnd.model.character.Progression.longRest(state);
            afterVitalsChange(token);
            showDetail(token);
            status(TokenSupport.nameOf(token) + " takes a long rest.");
        });
        return new FlowPane(6, 6, shortRest, longRest);
    }

    private boolean isPactCaster(MapObject token) {
        if (!(token instanceof PlayerToken pt) || pt.getCharacter() == null || pt.getCharacter().getClassId() == null) {
            return false;
        }
        var cls = repos.classes().getById(pt.getCharacter().getClassId());
        return cls != null && cls.getSpellcasting() == com.dnd.model.magic.SpellcastingType.PACT;
    }

    private Integer askNumber(String title, String prompt, int initial) {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(String.valueOf(initial));
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(prompt);
        owner.styleDialog(dialog);
        while (true) {
            var result = dialog.showAndWait();
            if (result.isEmpty()) return null;
            try {
                int value = Integer.parseInt(result.get().trim());
                if (value < 0) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException ex) {
                dialog.setContentText("Enter a whole number 0 or greater:");
            }
        }
    }

    /** Short or long rest for every player on the map at once. */
    private void openPartyRestDialog() {
        List<PlayerToken> players = new ArrayList<>();
        for (MapObject token : allCreatures()) {
            if (token instanceof PlayerToken pt) players.add(pt);
        }
        if (players.isEmpty()) {
            status("No player characters on this map.");
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Party Rest");
        dialog.setHeaderText("Short rest: enter the HP each player rolled on their hit dice.\n"
            + "Long rest: everyone is restored to full.");
        ButtonType shortType = new ButtonType("Short Rest", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType longType = new ButtonType("Long Rest", javafx.scene.control.ButtonBar.ButtonData.APPLY);
        dialog.getDialogPane().getButtonTypes().addAll(shortType, longType, ButtonType.CANCEL);
        owner.styleDialog(dialog);
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        java.util.Map<PlayerToken, Spinner<Integer>> rolls = new java.util.LinkedHashMap<>();
        int row = 0;
        for (PlayerToken pt : players) {
            Spinner<Integer> hp = new Spinner<>(0, 999, 0);
            hp.setEditable(true);
            hp.setPrefWidth(90);
            rolls.put(pt, hp);
            grid.addRow(row++, new Label(TokenSupport.nameOf(pt)), hp);
        }
        dialog.getDialogPane().setContent(grid);
        ButtonType chosen = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (chosen == ButtonType.CANCEL) return;
        for (PlayerToken pt : players) {
            CombatState state = TokenSupport.combatOf(pt);
            if (chosen == longType) com.dnd.model.character.Progression.longRest(state);
            else com.dnd.model.character.Progression.shortRest(state, isPactCaster(pt), rolls.get(pt).getValue(),
                TokenSupport.levelOf(pt));
        }
        dirty = true;
        pushAllToSheets();
        refreshAll();
        status(chosen == longType ? "The party takes a long rest." : "The party takes a short rest.");
    }

    // ── Quick placement palette ─────────────────────────────────────────────

    private void toggleTokenPalette() {
        if (rootPane == null) return;
        if (rootPane.getRight() != null) {
            rootPane.setRight(null);
            return;
        }
        // Rebuilt on every open so creatures added to the campaign meanwhile show up.
        tokenPalette = buildTokenPalette();
        rootPane.setRight(tokenPalette);
    }

    /** Record for a palette row: "Monster", id, display name. */
    private record PaletteEntry(String type, String id, String name) {
        @Override
        public String toString() {
            return name + "   · " + type;
        }
    }

    private Node buildTokenPalette() {
        List<PaletteEntry> all = new ArrayList<>();
        repos.players().list().forEach(p -> all.add(new PaletteEntry("Player", p.getId(), p.getName())));
        repos.npcs().list().forEach(n -> all.add(new PaletteEntry("NPC", n.getId(), n.getName())));
        repos.monsters().list().forEach(m -> all.add(new PaletteEntry("Monster", m.getId(), m.getName())));
        repos.beasts().list().forEach(b -> all.add(new PaletteEntry("Beast", b.getId(), b.getName())));
        all.sort(java.util.Comparator.comparing(e -> e.name() == null ? "" : e.name().toLowerCase(java.util.Locale.ROOT)));

        javafx.scene.control.TextField search = new javafx.scene.control.TextField();
        search.setPromptText("Search creatures...");
        ToggleGroup kinds = new ToggleGroup();
        javafx.scene.layout.FlowPane kindRow = new javafx.scene.layout.FlowPane(4, 4);
        for (String k : List.of("All", "Player", "NPC", "Monster", "Beast")) {
            ToggleButton t = new ToggleButton(k);
            t.setUserData(k);
            t.setToggleGroup(kinds);
            t.getStyleClass().add("tool-toggle-button");
            kindRow.getChildren().add(t);
        }
        kinds.selectToggle(kinds.getToggles().get(0));
        kinds.selectedToggleProperty().addListener((o, old, v) -> { if (v == null) kinds.selectToggle(old); });
        ListView<PaletteEntry> list = new ListView<>();
        list.getStyleClass().add("dnd-list-view");
        VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);

        Runnable filter = () -> {
            String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(java.util.Locale.ROOT);
            list.getItems().setAll(all.stream()
                .filter(e -> {
                    String k = kinds.getSelectedToggle() == null ? "All" : (String) kinds.getSelectedToggle().getUserData();
                    return "All".equals(k) || e.type().equals(k);
                })
                .filter(e -> q.isEmpty() || (e.name() != null && e.name().toLowerCase(java.util.Locale.ROOT).contains(q)))
                .toList());
        };
        search.textProperty().addListener((o, a, b) -> filter.run());
        kinds.selectedToggleProperty().addListener((o, a, b) -> filter.run());
        filter.run();
        list.getSelectionModel().selectedItemProperty().addListener((o, a, entry) -> {
            if (entry != null) armTokenPlacement(entry.type(), entry.id());
        });
        // Clicking the already-selected entry again (e.g. after Esc) picks it back up.
        list.setOnMouseClicked(e -> {
            PaletteEntry entry = list.getSelectionModel().getSelectedItem();
            if (entry != null && armedTokenId == null) armTokenPlacement(entry.type(), entry.id());
        });

        Label hint = owner.body("Pick a creature, then click empty boxes to drop it. Esc stops.");
        hint.setWrapText(true);
        VBox box = new VBox(8, owner.sectionLabel("Add Token"), search, kindRow, list, hint);
        box.setPadding(new Insets(10));
        box.setPrefWidth(260);
        box.getStyleClass().add("battle-panel");
        return box;
    }
}
