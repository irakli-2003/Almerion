package com.dnd.ui.scenes;

import com.dnd.music.MusicLibrary;
import com.dnd.music.YouTubeLink;
import com.dnd.ui.music.MusicPlayer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * The DM's "🎵 Music" window: playlists of YouTube links, the combat playlist, playback
 * controls and a sound-effect pad. One window per app - opening it again brings it forward.
 */
public final class MusicPanel {

    private static Stage openStage;
    private static Path openRoot;

    private final BaseScene owner;
    private final Path campaignRoot;
    private final MusicLibrary library;
    private final MusicPlayer player = MusicPlayer.get();

    private ListView<MusicLibrary.Playlist> playlistList;
    private ListView<MusicLibrary.Track> trackList;
    private ComboBox<Object> combatBox;
    private FlowPane soundPad;
    private FlowPane ambiencePad;
    private Label nowPlaying;
    private Label detail;
    private Label message;
    private Button playPause;
    private Consumer<MusicPlayer.Status> listener;
    private Stage stage;
    private String shownAmbience;
    private ComboBox<String> folderBox;

    private static final String ALL_FOLDERS = "All folders";
    private static final String NO_FOLDER = "General (no folder)";

    private MusicPanel(BaseScene owner, Path campaignRoot) {
        this.owner = owner;
        this.campaignRoot = campaignRoot;
        this.library = MusicLibrary.load(campaignRoot);
    }

    /** Opens (or focuses) the music window for the given campaign. */
    public static void open(BaseScene owner, Path campaignRoot) {
        javafx.stage.Window from = com.dnd.ui.WindowOrder.current();
        if (openStage != null && openStage.isShowing() && campaignRoot.equals(openRoot)
                && (from == openStage || from == openStage.getOwner())) {
            openStage.setIconified(false);
            openStage.toFront();
            return;
        }
        // Opened from a different window: re-attach it there so closing it goes back to that window.
        if (openStage != null) openStage.close();
        new MusicPanel(owner, campaignRoot).show(from);
    }

    private void show(javafx.stage.Window from) {
        stage = new Stage();
        com.dnd.ui.WindowOrder.adopt(stage, from);
        stage.setTitle("🎵 Music");

        VBox root = new VBox(12);
        root.getStyleClass().add("root");
        root.setPadding(new Insets(14));
        root.getChildren().addAll(buildNowPlaying(), buildControls(), new Separator(), buildFolderRow());

        HBox columns = new HBox(14, buildPlaylistColumn(), buildTrackColumn(), buildSoundColumn());
        VBox.setVgrow(columns, Priority.ALWAYS);
        root.getChildren().add(columns);

        Label hint = owner.body("Paste any YouTube video or playlist link. Videos whose owners block playback"
            + " outside YouTube are skipped automatically.\nIn a session file, wrap a scene in [music: Playlist name]"
            + " ... [/music: Playlist name] or [sfx: Sound name] ... [/sfx: Sound name]; double-click the start"
            + " cue to play it and the end cue to stop it.");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        hint.setMaxWidth(Double.MAX_VALUE);
        root.getChildren().add(hint);

        listener = this::onStatus;
        player.addListener(listener);
        stage.setOnHidden(e -> {
            player.removeListener(listener);
            if (openStage == stage) openStage = null;
        });

        stage.setScene(owner.themedScene(root, 1080, 640));
        openStage = stage;
        openRoot = campaignRoot;
        refreshPlaylists(null);
        refreshSounds();
        stage.show();
    }

    // ── Folders ─────────────────────────────────────────────────────────────

    /** Folder filter, so themed sets (e.g. "Alice X Horror") don't drown in the general library. */
    private HBox buildFolderRow() {
        folderBox = new ComboBox<>();
        folderBox.setTooltip(new Tooltip("Show only the playlists and sounds in one folder"));
        refreshFolders(ALL_FOLDERS);
        folderBox.setOnAction(e -> {
            if (folderBox.getValue() == null) return;
            refreshPlaylists(null);
            refreshSounds();
        });
        HBox row = new HBox(8, owner.body("📁 Folder:"), folderBox);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    private void refreshFolders(String select) {
        java.util.List<String> names = new java.util.ArrayList<>(java.util.List.of(ALL_FOLDERS, NO_FOLDER));
        names.addAll(library.folders());
        folderBox.getItems().setAll(names);
        folderBox.setValue(names.stream().filter(n -> n.equalsIgnoreCase(select)).findFirst().orElse(ALL_FOLDERS));
    }

    private boolean shown(String folder) {
        String sel = folderBox == null ? null : folderBox.getValue();
        if (sel == null || ALL_FOLDERS.equals(sel)) return true;
        if (NO_FOLDER.equals(sel)) return folder == null;
        return sel.equalsIgnoreCase(folder);
    }

    /** The folder a newly created playlist or sound should go into: the one being viewed. */
    private String currentFolder() {
        String sel = folderBox == null ? null : folderBox.getValue();
        return sel == null || ALL_FOLDERS.equals(sel) || NO_FOLDER.equals(sel) ? null : sel;
    }

    /** Editable folder picker for dialogs: existing folders to choose from, or type a new one. */
    private ComboBox<String> folderPicker(String initial) {
        ComboBox<String> box = new ComboBox<>();
        box.setEditable(true);
        box.getItems().setAll(library.folders());
        box.setValue(initial == null ? "" : initial);
        box.setPromptText("none - e.g. Alice X Horror");
        return box;
    }

    // ── Now playing / controls ──────────────────────────────────────────────

    private VBox buildNowPlaying() {
        nowPlaying = owner.sectionLabel("Nothing playing");
        detail = owner.body("");
        message = owner.body("");
        message.setWrapText(true);
        message.setMaxWidth(Double.MAX_VALUE);
        message.setStyle("-fx-text-fill: #f0c674;");
        return new VBox(2, nowPlaying, detail, message);
    }

    private HBox buildControls() {
        Button prev = tool("⏮", "Previous track", player::previous);
        playPause = tool("▶ Play", "Play the selected playlist, or pause/resume", this::togglePlay);
        Button next = tool("⏭", "Next track", player::next);
        Button stop = tool("⏹ Stop", "Fade the music out", player::stop);

        Slider volume = new Slider(0, 100, library.getVolume());
        volume.setPrefWidth(160);
        volume.valueProperty().addListener((o, a, v) -> player.setVolume(v.intValue()));
        volume.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) {
                library.setVolume((int) volume.getValue());
                save();
            }
        });
        volume.setOnMouseReleased(e -> {
            library.setVolume((int) volume.getValue());
            save();
        });

        CheckBox shuffle = owner.checkBox("Shuffle");
        shuffle.setSelected(library.isShuffle());
        shuffle.selectedProperty().addListener((o, a, on) -> {
            library.setShuffle(on);
            player.setShuffle(on);
            save();
        });
        CheckBox loop = owner.checkBox("Loop");
        loop.setSelected(library.isLoop());
        loop.selectedProperty().addListener((o, a, on) -> {
            library.setLoop(on);
            player.setLoop(on);
            save();
        });

        Label volumeLabel = owner.body("Volume");
        for (Region r : new Region[]{volumeLabel, shuffle, loop}) r.setMinWidth(Region.USE_PREF_SIZE);
        HBox bar = new HBox(8, prev, playPause, next, stop, new Separator(Orientation.VERTICAL), volumeLabel, volume, shuffle, loop);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void togglePlay() {
        MusicPlayer.Status s = player.status();
        MusicLibrary.Playlist selected = playlistList.getSelectionModel().getSelectedItem();
        boolean selectedIsCurrent = selected != null && selected.getId().equals(player.currentPlaylistId());
        if (s.isPlaying() && (selected == null || selectedIsCurrent)) {
            player.pause();
        } else if (s.paused() && player.currentPlaylistId() != null && (selected == null || selectedIsCurrent)) {
            player.resume();
        } else if (selected != null) {
            play(selected, -1);
        } else {
            message.setText("Pick a playlist first (or create one with \"+ New\").");
        }
    }

    private void play(MusicLibrary.Playlist playlist, int trackIndex) {
        if (!player.playPlaylist(library, playlist, trackIndex)) {
            message.setText("\"" + playlist.getName() + "\" has no playable YouTube links yet - add some with \"+ Add link\".");
        }
    }

    private void onStatus(MusicPlayer.Status s) {
        switch (s.phase()) {
            case OFF -> nowPlaying.setText("Nothing playing");
            case DOWNLOADING, STARTING -> nowPlaying.setText("⏳ " + (s.message() == null ? "Starting..." : s.message()));
            case FAILED -> nowPlaying.setText("⚠ Music unavailable");
            case READY -> {
                String title = s.title() == null || s.title().isBlank() ? (s.playlistName() == null ? "Nothing playing" : "Loading...") : s.title();
                nowPlaying.setText((s.isPlaying() ? "♪ " : s.paused() ? "⏸ " : "") + title);
            }
        }
        StringBuilder d = new StringBuilder();
        if (s.playlistName() != null) d.append("Playlist: ").append(s.playlistName());
        if (s.combat()) d.append("  ⚔ combat");
        if (s.count() > 1 && s.index() >= 0) d.append("  ·  track ").append(s.index() + 1).append(" of ").append(s.count());
        if (s.duration() > 0) d.append("  ·  ").append(clock(s.time())).append(" / ").append(clock(s.duration()));
        detail.setText(d.toString());
        String note = s.phase() == MusicPlayer.Phase.FAILED ? s.message() : s.error();
        if (note != null) message.setText(note);
        playPause.setText(s.isPlaying() ? "⏸ Pause" : "▶ Play");
        if (!java.util.Objects.equals(shownAmbience, player.ambienceSoundId())) refreshSounds();
    }

    private static String clock(int seconds) {
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    // ── Playlists ───────────────────────────────────────────────────────────

    private VBox buildPlaylistColumn() {
        playlistList = new ListView<>();
        playlistList.getStyleClass().add("dnd-list-view");
        playlistList.setPrefWidth(240);
        VBox.setVgrow(playlistList, Priority.ALWAYS);
        playlistList.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> refreshTracks());
        playlistList.setOnMouseClicked(e -> {
            MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && p != null) play(p, -1);
        });

        Button add = tool("+ New", "Create a playlist (e.g. Tavern, Dungeon, Boss fight)", () -> {
            String name = ask("New playlist", "Playlist name:", "");
            if (name == null) return;
            MusicLibrary.Playlist p = new MusicLibrary.Playlist(name);
            p.setFolder(currentFolder());
            library.getPlaylists().add(p);
            save();
            refreshPlaylists(p);
        });
        Button folder = tool("📁", "Move the selected playlist to a folder", () -> {
            MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
            if (p == null) return;
            String name = askFolder("Move \"" + p.getName() + "\" to folder", p.getFolder());
            if (name == null) return;
            p.setFolder(name);
            save();
            refreshFolders(folderBox.getValue());
            refreshPlaylists(p);
        });
        Button rename = tool("Rename", "Rename the selected playlist", () -> {
            MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
            if (p == null) return;
            String name = ask("Rename playlist", "Playlist name:", p.getName());
            if (name == null) return;
            p.setName(name);
            save();
            refreshPlaylists(p);
        });
        Button delete = compact(owner.dangerBtn("Delete", () -> {
            MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
            if (p == null || !confirm("Delete the playlist \"" + p.getName() + "\"?")) return;
            library.getPlaylists().remove(p);
            if (p.getId().equals(library.getCombatPlaylistId())) library.setCombatPlaylistId(null);
            save();
            refreshPlaylists(null);
        }));
        Button playBtn = tool("▶ Play playlist", "Crossfade to the selected playlist", () -> {
            MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
            if (p != null) play(p, -1);
        });

        combatBox = new ComboBox<>();
        combatBox.setMaxWidth(Double.MAX_VALUE);
        combatBox.setTooltip(new Tooltip("Plays automatically when you roll or enter initiative on a battle map"));
        combatBox.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item instanceof MusicLibrary.Playlist p ? p.getName() : "(none)");
            }
        });
        combatBox.setButtonCell(combatBox.getCellFactory().call(null));
        combatBox.setOnAction(e -> {
            Object v = combatBox.getValue();
            String id = v instanceof MusicLibrary.Playlist p ? p.getId() : null;
            if (java.util.Objects.equals(id, library.getCombatPlaylistId())) return;
            library.setCombatPlaylistId(id);
            save();
        });

        Button starter = tool("★ Starter pack", "Add the popular YouTube playlists, ambience and sound effects that"
            + " this campaign doesn't have yet (tavern, market, dungeon, combat, rain, river, thunder...)", () -> {
            int added = library.addStarterPack();
            save();
            refreshPlaylists(playlistList.getSelectionModel().getSelectedItem());
            refreshSounds();
            message.setText(added == 0 ? "You already have everything from the starter pack."
                : "Added " + added + " playlists and sounds from the starter pack.");
        });

        HBox row1 = new HBox(6, add, rename, folder, delete);
        return new VBox(8, owner.sectionLabel("Playlists"), playlistList, row1, new HBox(6, playBtn, starter),
            owner.body("⚔ Combat playlist:"), combatBox);
    }

    private void refreshPlaylists(MusicLibrary.Playlist select) {
        playlistList.getItems().setAll(library.sortedPlaylists().stream().filter(p -> shown(p.getFolder())).toList());
        if (select != null && playlistList.getItems().contains(select)) playlistList.getSelectionModel().select(select);
        else if (!playlistList.getItems().isEmpty()) playlistList.getSelectionModel().select(0);

        combatBox.getItems().setAll("(none)");
        combatBox.getItems().addAll(library.sortedPlaylists());
        MusicLibrary.Playlist combat = library.findPlaylist(library.getCombatPlaylistId());
        combatBox.setValue(combat != null ? combat : "(none)");
        refreshTracks();
    }

    // ── Tracks ──────────────────────────────────────────────────────────────

    private VBox buildTrackColumn() {
        trackList = new ListView<>();
        trackList.getStyleClass().add("dnd-list-view");
        VBox.setVgrow(trackList, Priority.ALWAYS);
        trackList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(MusicLibrary.Track t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) {
                    setText(null);
                    return;
                }
                YouTubeLink link = t.link();
                setText((link == null ? "⚠ " : link.isPlaylist() ? "☰ " : "♪ ") + t.label());
            }
        });
        trackList.setOnMouseClicked(e -> {
            MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
            int i = trackList.getSelectionModel().getSelectedIndex();
            if (e.getClickCount() == 2 && p != null && i >= 0) play(p, i);
        });

        Button add = tool("+ Add link", "Add a YouTube video or playlist link", this::addTrack);
        Button edit = tool("Edit", "Change the selected link or its title", this::editTrack);
        Button remove = compact(owner.dangerBtn("Remove", () -> {
            MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
            MusicLibrary.Track t = trackList.getSelectionModel().getSelectedItem();
            if (p == null || t == null) return;
            p.getTracks().remove(t);
            save();
            refreshTracks();
        }));
        Button up = tool("▲", "Move up", () -> moveTrack(-1));
        Button down = tool("▼", "Move down", () -> moveTrack(1));

        VBox column = new VBox(8, owner.sectionLabel("Tracks  (double-click to play)"), trackList,
            new HBox(6, add, edit, remove, up, down));
        HBox.setHgrow(column, Priority.ALWAYS);
        return column;
    }

    private void refreshTracks() {
        MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
        trackList.getItems().setAll(p == null ? java.util.List.of() : p.getTracks());
    }

    private void moveTrack(int delta) {
        MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
        int i = trackList.getSelectionModel().getSelectedIndex();
        if (p == null || i < 0) return;
        int j = i + delta;
        if (j < 0 || j >= p.getTracks().size()) return;
        java.util.Collections.swap(p.getTracks(), i, j);
        save();
        refreshTracks();
        trackList.getSelectionModel().select(j);
    }

    private void addTrack() {
        MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
        if (p == null) {
            message.setText("Create or pick a playlist first.");
            return;
        }
        MusicLibrary.Track track = trackDialog(new MusicLibrary.Track(), "Add YouTube link");
        if (track == null) return;
        p.getTracks().add(track);
        save();
        refreshTracks();
        fillTitle(track);
    }

    private void editTrack() {
        MusicLibrary.Track t = trackList.getSelectionModel().getSelectedItem();
        if (t == null) return;
        if (trackDialog(t, "Edit link") == null) return;
        save();
        refreshTracks();
        fillTitle(t);
    }

    private MusicLibrary.Track trackDialog(MusicLibrary.Track track, String heading) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(heading);
        dialog.setHeaderText("Paste a YouTube link (a single video or a whole playlist)");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.initOwner(stage);
        owner.styleDialog(dialog);

        TextField url = new TextField(track.getUrl() == null ? "" : track.getUrl());
        url.setPromptText("https://www.youtube.com/watch?v=...");
        url.setPrefColumnCount(40);
        TextField title = new TextField(track.getTitle() == null ? "" : track.getTitle());
        title.setPromptText("Optional - filled in from YouTube if left empty");
        Label kind = new Label();
        Runnable check = () -> {
            YouTubeLink link = YouTubeLink.parse(url.getText());
            kind.setText(url.getText().isBlank() ? "" : link == null ? "⚠ Not a YouTube link"
                : link.isPlaylist() ? "☰ YouTube playlist - plays all of its videos" : "♪ Single video");
            dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(link == null);
        };
        url.textProperty().addListener((o, a, b) -> check.run());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Link:"), url);
        grid.addRow(1, new Label("Title:"), title);
        grid.add(kind, 1, 2);
        dialog.getDialogPane().setContent(grid);
        check.run();
        Platform.runLater(url::requestFocus);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        track.setUrl(url.getText().trim());
        track.setTitle(title.getText().isBlank() ? null : title.getText().trim());
        return track;
    }

    /** Looks up the video/playlist title from YouTube's public oEmbed endpoint when none was typed. */
    private void fillTitle(MusicLibrary.Track track) {
        if (track.getTitle() != null) return;
        String url = track.getUrl();
        Thread t = new Thread(() -> {
            String found = fetchTitle(url);
            if (found == null) return;
            Platform.runLater(() -> {
                if (track.getTitle() != null || !url.equals(track.getUrl())) return;
                track.setTitle(found);
                save();
                trackList.refresh();
            });
        }, "youtube-title");
        t.setDaemon(true);
        t.start();
    }

    static String fetchTitle(String url) {
        try {
            YouTubeLink link = YouTubeLink.parse(url);
            if (link == null) return null;
            String canonical = link.videoId() != null && !link.isPlaylist()
                ? "https://www.youtube.com/watch?v=" + link.videoId()
                : "https://www.youtube.com/playlist?list=" + link.playlistId();
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://www.youtube.com/oembed?format=json&url="
                + URLEncoder.encode(canonical, StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(8)).build();
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonNode json = new ObjectMapper().readTree(response.body());
            String title = json.path("title").asText(null);
            return title == null || title.isBlank() ? null : title;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Sound effects ───────────────────────────────────────────────────────

    private VBox buildSoundColumn() {
        soundPad = new FlowPane(6, 6);
        soundPad.setPrefWrapLength(240);
        ambiencePad = new FlowPane(6, 6);
        ambiencePad.setPrefWrapLength(240);
        VBox pads = new VBox(8, owner.body("∞ Ambience - loops under the music, click again to stop"), ambiencePad,
            new Separator(), owner.body("🔊 Effects - play once"), soundPad);
        ScrollPane scroll = new ScrollPane(pads);
        scroll.setFitToWidth(true);
        scroll.setPrefWidth(290);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Slider fxVolume = new Slider(0, 100, library.getEffectsVolume());
        fxVolume.valueProperty().addListener((o, a, v) -> player.setEffectsVolume(v.intValue()));
        fxVolume.setOnMouseReleased(e -> {
            library.setEffectsVolume((int) fxVolume.getValue());
            save();
        });

        Button add = tool("+ Add sound", "Add a sound effect or looping ambience from a YouTube clip", () -> {
            MusicLibrary.Sound s = new MusicLibrary.Sound();
            s.setFolder(currentFolder());
            s = soundDialog(s, "Add sound");
            if (s == null) return;
            library.getSounds().add(s);
            save();
            refreshFolders(folderBox.getValue());
            refreshSounds();
        });
        Button stopFx = tool("⏹ Stop sounds", "Stop the sound effect and the ambience", () -> {
            player.stopSound();
            player.stopAmbience();
        });

        return new VBox(8, owner.sectionLabel("Sounds"), scroll, new HBox(6, add, stopFx),
            owner.body("Sounds volume"), fxVolume);
    }

    private void refreshSounds() {
        shownAmbience = player.ambienceSoundId();
        soundPad.getChildren().clear();
        ambiencePad.getChildren().clear();
        for (MusicLibrary.Sound sound : library.sortedSounds()) {
            if (!shown(sound.getFolder())) continue;
            boolean on = sound.isAmbience() && sound.getId().equals(shownAmbience);
            Button b = tool((on ? "◼ " : "") + sound.getName(),
                (sound.isAmbience() ? "Click to start or stop this loop" : "Click to play")
                    + " · right-click to edit or remove", () -> {
                    if (sound.isAmbience() && sound.getId().equals(player.ambienceSoundId())) {
                        player.stopAmbience();
                    } else if (!player.playSound(sound)) {
                        message.setText("\"" + sound.getName() + "\" needs a single-video YouTube link.");
                    }
                    refreshSounds();
                });
            if (on) b.setStyle(b.getStyle() + "-fx-background-color: #6b4a00; -fx-border-color: #f0d080;");
            MenuItem edit = new MenuItem("Edit...");
            edit.setOnAction(e -> {
                if (soundDialog(sound, "Edit sound") != null) {
                    save();
                    refreshFolders(folderBox.getValue());
                    refreshSounds();
                }
            });
            MenuItem remove = new MenuItem("Remove");
            remove.setOnAction(e -> {
                if (sound.getId().equals(player.ambienceSoundId())) player.stopAmbience();
                library.getSounds().remove(sound);
                save();
                refreshSounds();
            });
            b.setContextMenu(new ContextMenu(edit, remove));
            (sound.isAmbience() ? ambiencePad : soundPad).getChildren().add(b);
        }
        if (ambiencePad.getChildren().isEmpty()) ambiencePad.getChildren().add(owner.body("None yet - rain, river, wind..."));
        if (soundPad.getChildren().isEmpty()) soundPad.getChildren().add(owner.body("None yet - thunder, a door creak, a dragon roar..."));
    }

    private MusicLibrary.Sound soundDialog(MusicLibrary.Sound sound, String heading) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(heading);
        dialog.setHeaderText("A clip from a YouTube video. Use start/end to cut out just the sound you want,"
            + " or tick Ambience for a background loop (rain, river, wind...).");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.initOwner(stage);
        owner.styleDialog(dialog);

        TextField name = new TextField(sound.getName() == null ? "" : sound.getName());
        name.setPromptText("Thunder");
        TextField url = new TextField(sound.getUrl() == null ? "" : sound.getUrl());
        url.setPromptText("https://www.youtube.com/watch?v=...");
        url.setPrefColumnCount(36);
        Spinner<Integer> start = new Spinner<>(0, 36000, sound.getStartSeconds());
        Spinner<Integer> end = new Spinner<>(0, 36000, sound.getEndSeconds());
        start.setEditable(true);
        end.setEditable(true);
        CheckBox ambience = owner.checkBox("Ambience - loop under the music until stopped");
        ambience.setSelected(sound.isAmbience());
        ComboBox<String> folder = folderPicker(sound.getFolder());
        Button preview = new Button("▶ Preview");
        preview.getStyleClass().add("dnd-button");
        compact(preview);
        preview.setOnAction(e -> player.playSound(new MusicLibrary.Sound("preview", url.getText(), start.getValue(), end.getValue())));
        Runnable check = () -> {
            YouTubeLink link = YouTubeLink.parse(url.getText());
            dialog.getDialogPane().lookupButton(ButtonType.OK)
                .setDisable(name.getText().isBlank() || link == null || link.videoId() == null);
        };
        name.textProperty().addListener((o, a, b) -> check.run());
        url.textProperty().addListener((o, a, b) -> check.run());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Name:"), name);
        grid.addRow(1, new Label("Link:"), url);
        grid.addRow(2, new Label("Start (s):"), start);
        grid.addRow(3, new Label("End (s, 0 = to the end):"), end);
        grid.add(ambience, 1, 4);
        grid.addRow(5, new Label("Folder:"), folder);
        grid.add(preview, 1, 6);
        dialog.getDialogPane().setContent(grid);
        check.run();

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        sound.setName(name.getText().trim());
        sound.setUrl(url.getText().trim());
        sound.setStartSeconds(start.getValue());
        sound.setEndSeconds(end.getValue());
        sound.setAmbience(ambience.isSelected());
        sound.setFolder(folder.getEditor().getText());
        return sound;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void save() {
        try {
            library.save(campaignRoot);
        } catch (RuntimeException e) {
            message.setText("Couldn't save music.json: " + e.getMessage());
        }
    }

    private Button tool(String text, String tooltip, Runnable action) {
        Button b = owner.btn(text, action);
        b.setTooltip(new Tooltip(tooltip));
        return compact(b);
    }

    private static Button compact(Button b) {
        b.setStyle("-fx-min-width: 0; -fx-padding: 6 12 6 12;");
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    /** Asks for a folder (existing or new). Returns "" to clear the folder, null when cancelled. */
    private String askFolder(String title, String initial) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText("Pick a folder, type a new one, or leave it empty for the general library.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.initOwner(stage);
        owner.styleDialog(dialog);
        ComboBox<String> box = folderPicker(initial);
        dialog.getDialogPane().setContent(new HBox(8, new Label("Folder:"), box));
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        return box.getEditor().getText() == null ? "" : box.getEditor().getText().trim();
    }

    private String ask(String title, String prompt, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(prompt);
        dialog.initOwner(stage);
        owner.styleDialog(dialog);
        String value = dialog.showAndWait().orElse(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean confirm(String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage);
        owner.styleDialog(alert);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
}
