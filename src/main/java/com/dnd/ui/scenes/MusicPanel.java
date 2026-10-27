package com.dnd.ui.scenes;

import com.dnd.music.Attribution;
import com.dnd.music.AudioFiles;
import com.dnd.music.AudioSource;
import com.dnd.music.MusicLibrary;
import com.dnd.ui.music.MusicPlayer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The DM's "🎵 Music" window: playlists of audio tracks, the combat playlist, playback
 * controls, a sound-effect pad and the credits of openly licensed audio. Audio files are
 * copied into the campaign folder; direct audio links are downloaded once into it.
 * One window per app - opening it again brings it forward.
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
    private Button starterButton;
    private Button removeUnsupportedButton;
    private HBox downloadRow;
    private ProgressBar downloadBar;
    private Label downloadCount;
    private AtomicBoolean downloadCancel;

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

        Label hint = owner.body("Add audio files from your computer (MP3, WAV, AIFF or M4A) or direct links to audio"
            + " files. Files are copied into the campaign folder, so backups and other computers have them too.\n"
            + "Only use music you own or that is licensed for your use, such as the starter pack.\n"
            + "In a session file, wrap a scene in [music: Playlist name]"
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
            if (downloadCancel != null) downloadCancel.set(true);
            if (openStage == stage) openStage = null;
        });

        stage.setScene(owner.themedScene(root, 1080, 640));
        openStage = stage;
        openRoot = campaignRoot;
        refreshPlaylists(null);
        refreshSounds();
        if (library.unsupportedCount() > 0) {
            message.setText("Some tracks or sounds still use YouTube links, which are no longer supported."
                + " Replace them with audio files, or press \"Remove unsupported\".");
        }
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
        downloadBar = new ProgressBar(0);
        downloadBar.setPrefWidth(260);
        downloadCount = owner.body("");
        Button cancel = tool("Cancel download", "Stop downloading; finished files are kept", () -> {
            if (downloadCancel != null) downloadCancel.set(true);
        });
        downloadRow = new HBox(8, owner.body("⬇"), downloadBar, downloadCount, cancel);
        downloadRow.setAlignment(Pos.CENTER_LEFT);
        downloadRow.setVisible(false);
        downloadRow.setManaged(false);
        return new VBox(2, nowPlaying, detail, message, downloadRow);
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
            message.setText("This playlist has no playable tracks yet. Add audio files or a direct link to an audio file.");
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
        playlistList.setPrefWidth(250);
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

        starterButton = tool("★ Starter pack", "Add free, openly licensed music, ambience and sound effects"
            + " (tavern, market, dungeon, combat, rain, river, thunder...) and download them into this campaign."
            + " Also downloads any other linked audio that isn't downloaded yet.", this::addStarterPack);
        Button credits = tool("Credits", "Show who made the music and sounds in this library and their licenses",
            this::showCredits);
        removeUnsupportedButton = tool("Remove unsupported", "Remove every track and sound that still uses a"
            + " YouTube link or another link that isn't an audio file", this::removeUnsupported);

        HBox row1 = new HBox(6, add, rename, folder, delete);
        return new VBox(8, owner.sectionLabel("Playlists"), playlistList, row1, new HBox(6, playBtn, starterButton),
            new HBox(6, credits, removeUnsupportedButton), owner.body("⚔ Combat playlist:"), combatBox);
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
        refreshUnsupported();
    }

    private void refreshUnsupported() {
        if (removeUnsupportedButton == null) return;
        boolean any = library.unsupportedCount() > 0;
        removeUnsupportedButton.setVisible(any);
        removeUnsupportedButton.setManaged(any);
    }

    private void removeUnsupported() {
        if (!confirm("Remove every track and sound that uses a YouTube link or another link that isn't an audio file?")) {
            return;
        }
        library.removeUnsupported();
        save();
        refreshPlaylists(playlistList.getSelectionModel().getSelectedItem());
        refreshSounds();
        message.setText("The unsupported tracks and sounds were removed.");
    }

    private void addStarterPack() {
        int added = library.addStarterPack();
        save();
        refreshFolders(folderBox.getValue());
        refreshPlaylists(playlistList.getSelectionModel().getSelectedItem());
        refreshSounds();
        List<MusicLibrary.Audio> pending = library.pendingDownloads();
        if (pending.isEmpty()) {
            message.setText(added == 0 ? "You already have everything from the starter pack."
                : "The starter pack was added.");
            return;
        }
        message.setText("Downloading the audio into the campaign folder. You can keep using the app meanwhile.");
        downloadAll(pending);
    }

    /** Downloads linked audio into the campaign's cache in the background, with progress. */
    private void downloadAll(List<MusicLibrary.Audio> pending) {
        AtomicBoolean cancel = new AtomicBoolean();
        downloadCancel = cancel;
        starterButton.setDisable(true);
        downloadBar.setProgress(0);
        downloadCount.setText("0 / " + pending.size());
        downloadRow.setVisible(true);
        downloadRow.setManaged(true);
        Thread t = new Thread(() -> {
            int failed = 0;
            int done = 0;
            for (MusicLibrary.Audio audio : pending) {
                if (cancel.get()) break;
                int finished = done;
                try {
                    AudioFiles.download(campaignRoot, audio.getUrl(), (bytes, total) -> {
                        double part = total > 0 ? Math.min(1, (double) bytes / total) : 0;
                        Platform.runLater(() -> downloadBar.setProgress((finished + part) / pending.size()));
                    });
                } catch (IOException e) {
                    failed++;
                }
                done++;
                int count = done;
                Platform.runLater(() -> {
                    downloadBar.setProgress((double) count / pending.size());
                    downloadCount.setText(count + " / " + pending.size());
                });
            }
            boolean cancelled = cancel.get();
            int failures = failed;
            Platform.runLater(() -> {
                downloadRow.setVisible(false);
                downloadRow.setManaged(false);
                starterButton.setDisable(false);
                if (downloadCancel == cancel) downloadCancel = null;
                trackList.refresh();
                refreshSounds();
                if (cancelled) {
                    message.setText("The download was stopped. Press \"Starter pack\" to download the rest.");
                } else if (failures > 0) {
                    message.setText("Some audio files couldn't be downloaded. Check the internet connection and"
                        + " press \"Starter pack\" to try again.");
                } else {
                    message.setText("All audio is downloaded and plays offline now.");
                }
            });
        }, "music-download-all");
        t.setDaemon(true);
        t.start();
    }

    private void showCredits() {
        List<Attribution> credits = library.credits();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Music credits");
        dialog.setHeaderText("Music and sounds in this library, with their authors and licenses."
            + "\nIf you stream or publish recordings of your sessions, include these credits.");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.initOwner(stage);
        owner.styleDialog(dialog);
        if (credits.isEmpty()) {
            dialog.getDialogPane().setContent(owner.body("No tracks or sounds in this library have credits yet."));
        } else {
            StringBuilder text = new StringBuilder();
            for (Attribution a : credits) text.append(a.creditLine()).append('\n');
            TextArea area = new TextArea(text.toString().trim());
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefColumnCount(70);
            area.setPrefRowCount(18);
            dialog.getDialogPane().setContent(area);
        }
        dialog.setResizable(true);
        dialog.showAndWait();
    }

    // ── Tracks ──────────────────────────────────────────────────────────────

    private VBox buildTrackColumn() {
        trackList = new ListView<>();
        trackList.getStyleClass().add("dnd-list-view");
        VBox.setVgrow(trackList, Priority.ALWAYS);
        trackList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(MusicLibrary.Track t, boolean empty) {
                super.updateItem(t, empty);
                setTooltip(null);
                setOpacity(1);
                if (empty || t == null) {
                    setText(null);
                    return;
                }
                if (t.unsupported()) {
                    setText("⚠ " + t.label());
                    setOpacity(0.5);
                    setTooltip(new Tooltip(t.unsupportedReason()));
                } else if (t.downloadable() && library.localFile(t) == null) {
                    setText("⬇ " + t.label());
                    setTooltip(new Tooltip("This track downloads the first time it plays."));
                } else {
                    setText("♪ " + t.label());
                    if (t.getAttribution() != null) setTooltip(new Tooltip(t.getAttribution().creditLine()));
                }
            }
        });
        trackList.setOnMouseClicked(e -> {
            MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
            MusicLibrary.Track t = trackList.getSelectionModel().getSelectedItem();
            int i = trackList.getSelectionModel().getSelectedIndex();
            if (e.getClickCount() != 2 || p == null || t == null || i < 0) return;
            if (t.unsupported()) message.setText(t.unsupportedReason());
            else play(p, i);
        });

        Button addFiles = tool("+ Add audio files...", "Copy MP3, WAV, AIFF or M4A files from your computer into"
            + " this playlist", this::addFiles);
        Button addFolder = tool("+ Add folder...", "Copy every audio file in a folder into this playlist",
            this::addFolder);
        Button addLink = tool("+ Add link...", "Add a direct link to an audio file; it is downloaded once", this::addLink);
        Button edit = tool("Edit", "Change the selected track's title, audio or start time", this::editTrack);
        Button remove = compact(owner.dangerBtn("Remove", () -> {
            MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
            MusicLibrary.Track t = trackList.getSelectionModel().getSelectedItem();
            if (p == null || t == null) return;
            p.getTracks().remove(t);
            save();
            refreshTracks();
            refreshUnsupported();
        }));
        Button up = tool("▲", "Move up", () -> moveTrack(-1));
        Button down = tool("▼", "Move down", () -> moveTrack(1));

        FlowPane buttons = new FlowPane(6, 6, addFiles, addFolder, addLink, edit, remove, up, down);
        VBox column = new VBox(8, owner.sectionLabel("Tracks  (double-click to play)"), trackList, buttons);
        HBox.setHgrow(column, Priority.ALWAYS);
        return column;
    }

    private void refreshTracks() {
        MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
        trackList.getItems().setAll(p == null ? List.of() : p.getTracks());
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

    private MusicLibrary.Playlist playlistForAdding() {
        MusicLibrary.Playlist p = playlistList.getSelectionModel().getSelectedItem();
        if (p == null) message.setText("Create or pick a playlist first.");
        return p;
    }

    private static FileChooser.ExtensionFilter audioFilter() {
        List<String> patterns = AudioSource.EXTENSIONS.stream().map(e -> "*." + e).toList();
        return new FileChooser.ExtensionFilter("Audio files (MP3, WAV, AIFF, M4A)", patterns);
    }

    private void addFiles() {
        MusicLibrary.Playlist p = playlistForAdding();
        if (p == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add audio files");
        chooser.getExtensionFilters().add(audioFilter());
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;
        importInto(p, files.stream().map(File::toPath).toList());
    }

    private void addFolder() {
        MusicLibrary.Playlist p = playlistForAdding();
        if (p == null) return;
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Add every audio file in a folder");
        File dir = chooser.showDialog(stage);
        if (dir == null) return;
        List<Path> files;
        try (Stream<Path> list = Files.list(dir.toPath())) {
            files = list.filter(Files::isRegularFile).filter(AudioSource::isSupportedFile).sorted().toList();
        } catch (IOException e) {
            message.setText("That folder couldn't be read.");
            return;
        }
        if (files.isEmpty()) {
            message.setText("That folder has no MP3, WAV, AIFF or M4A files.");
            return;
        }
        importInto(p, files);
    }

    /** Copies files into the campaign's music folder in the background and adds them as tracks. */
    private void importInto(MusicLibrary.Playlist playlist, List<Path> files) {
        message.setText("Copying the audio files into the campaign folder...");
        Thread t = new Thread(() -> {
            List<MusicLibrary.Track> added = new ArrayList<>();
            boolean failed = false;
            for (Path f : files) {
                try {
                    String rel = AudioFiles.importFile(campaignRoot, f);
                    added.add(MusicLibrary.Track.ofFile(stem(f), rel));
                } catch (IOException e) {
                    failed = true;
                }
            }
            boolean anyFailed = failed;
            Platform.runLater(() -> {
                playlist.getTracks().addAll(added);
                save();
                refreshTracks();
                message.setText(anyFailed ? "Some files couldn't be copied. Only MP3, WAV, AIFF and M4A files can be added."
                    : "The audio files were added.");
            });
        }, "music-import");
        t.setDaemon(true);
        t.start();
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void addLink() {
        MusicLibrary.Playlist p = playlistForAdding();
        if (p == null) return;
        MusicLibrary.Track track = trackDialog(new MusicLibrary.Track(), "Add a link to an audio file");
        if (track == null) return;
        p.getTracks().add(track);
        save();
        refreshTracks();
        prefetch(track);
    }

    private void editTrack() {
        MusicLibrary.Track t = trackList.getSelectionModel().getSelectedItem();
        if (t == null) return;
        if (trackDialog(t, "Edit track") == null) return;
        save();
        refreshTracks();
        refreshUnsupported();
        prefetch(t);
    }

    /** Downloads a newly linked track right away, so a bad link is reported now rather than mid-session. */
    private void prefetch(MusicLibrary.Audio audio) {
        if (!audio.downloadable() || library.localFile(audio) != null) return;
        message.setText(MusicPlayer.DOWNLOADING_MESSAGE);
        Thread t = new Thread(() -> {
            String result;
            try {
                AudioFiles.download(campaignRoot, audio.getUrl(), null);
                result = "The audio file was downloaded and plays offline now.";
            } catch (IOException e) {
                result = e.getMessage();
            }
            String text = result;
            Platform.runLater(() -> {
                message.setText(text);
                trackList.refresh();
            });
        }, "music-prefetch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Shared editor for a track's or sound's audio: a file from this computer (copied into the
     * campaign) or a direct link to an audio file.
     */
    private final class AudioPicker {
        final TextField url = new TextField();
        final Label file = new Label();
        final Label problem = new Label();
        final Button choose = new Button("Choose file...");
        String chosenFile;

        AudioPicker(MusicLibrary.Audio audio, Runnable onChange) {
            chosenFile = audio.getFile();
            url.setText(audio.getFile() == null && audio.getUrl() != null ? audio.getUrl() : "");
            url.setPromptText("https://example.com/music/tavern.mp3");
            url.setPrefColumnCount(36);
            problem.setWrapText(true);
            problem.setMaxWidth(420);
            problem.setStyle("-fx-text-fill: #f0c674;");
            choose.getStyleClass().add("dnd-button");
            compact(choose);
            choose.setOnAction(e -> {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Choose an audio file");
                chooser.getExtensionFilters().add(audioFilter());
                File f = chooser.showOpenDialog(stage);
                if (f == null) return;
                try {
                    chosenFile = AudioFiles.importFile(campaignRoot, f.toPath());
                    url.setText("");
                    onChange.run();
                } catch (IOException ex) {
                    onChange.run();
                    problem.setText(ex.getMessage());
                }
            });
            url.textProperty().addListener((o, a, b) -> {
                if (!b.isBlank()) chosenFile = null;
                onChange.run();
            });
        }

        void refresh() {
            file.setText(chosenFile == null ? "No file chosen." : chosenFile.substring(chosenFile.lastIndexOf('/') + 1));
            String text = url.getText().trim();
            if (chosenFile != null || text.isEmpty()) {
                problem.setText("");
            } else if (AudioSource.isYouTube(text)) {
                problem.setText(AudioSource.YOUTUBE_MESSAGE);
            } else {
                String invalid = AudioSource.validateLink(text);
                problem.setText(invalid == null ? "" : invalid);
            }
        }

        boolean valid() {
            String text = url.getText().trim();
            return chosenFile != null || (!text.isEmpty() && AudioSource.validateLink(text) == null);
        }

        void addTo(GridPane grid, int row) {
            grid.addRow(row, new Label("File:"), new HBox(8, choose, file));
            grid.addRow(row + 1, new Label("Or link:"), url);
            grid.add(problem, 1, row + 2);
        }

        String file() {
            return chosenFile;
        }

        String link() {
            return chosenFile == null ? url.getText().trim() : null;
        }
    }

    private MusicLibrary.Track trackDialog(MusicLibrary.Track track, String heading) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(heading);
        dialog.setHeaderText("Choose an audio file from your computer, or paste a direct link to an .mp3, .wav or"
            + " .m4a file.\nLinks to YouTube, Spotify and other streaming pages can't be used.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.initOwner(stage);
        owner.styleDialog(dialog);

        TextField title = new TextField(track.getTitle() == null ? "" : track.getTitle());
        title.setPromptText("Optional - the file name is used if left empty");
        TextField start = new TextField(AudioSource.formatTime(track.getStartSeconds()));
        start.setPromptText("0:00");
        start.setPrefColumnCount(8);
        AudioPicker[] picker = new AudioPicker[1];
        Runnable check = () -> {
            picker[0].refresh();
            boolean timeOk = AudioSource.parseTime(start.getText()) >= 0;
            if (!timeOk) picker[0].problem.setText("Write the start time like 1:30 or 90.");
            dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(!picker[0].valid() || !timeOk);
        };
        picker[0] = new AudioPicker(track, check);
        start.textProperty().addListener((o, a, b) -> check.run());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Title:"), title);
        picker[0].addTo(grid, 1);
        grid.addRow(4, new Label("Start at:"), start);
        dialog.getDialogPane().setContent(grid);
        check.run();

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        boolean audioChanged = !java.util.Objects.equals(picker[0].file(), track.getFile())
            || !java.util.Objects.equals(picker[0].link(), track.getUrl());
        track.setFile(picker[0].file());
        track.setUrl(picker[0].link());
        if (audioChanged) track.setAttribution(null);
        String name = title.getText().trim();
        if (name.isEmpty() && track.getFile() != null) name = stem(Path.of(track.getFile()));
        track.setTitle(name.isEmpty() ? null : name);
        track.setStartSeconds(Math.max(0, AudioSource.parseTime(start.getText())));
        return track;
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

        Button add = tool("+ Add sound", "Add a sound effect or looping ambience from an audio file", () -> {
            MusicLibrary.Sound s = new MusicLibrary.Sound();
            s.setFolder(currentFolder());
            s = soundDialog(s, "Add sound");
            if (s == null) return;
            library.getSounds().add(s);
            save();
            refreshFolders(folderBox.getValue());
            refreshSounds();
            prefetch(s);
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
            boolean unsupported = sound.unsupported();
            String tip = unsupported ? sound.unsupportedReason()
                : sound.isAmbience() ? "Click to start or stop this loop. Right-click to edit or remove it."
                : "Click to play. Right-click to edit or remove it.";
            Button b = tool((unsupported ? "⚠ " : on ? "◼ " : "") + sound.getName(), tip, () -> {
                if (sound.isAmbience() && sound.getId().equals(player.ambienceSoundId())) {
                    player.stopAmbience();
                } else if (!player.playSound(library, sound)) {
                    message.setText(unsupported ? sound.unsupportedReason()
                        : "This sound has no playable audio. Right-click it and choose an audio file.");
                }
                refreshSounds();
            });
            if (unsupported) b.setOpacity(0.5);
            if (on) b.setStyle(b.getStyle() + "-fx-background-color: #6b4a00; -fx-border-color: #f0d080;");
            MenuItem edit = new MenuItem("Edit...");
            edit.setOnAction(e -> {
                if (soundDialog(sound, "Edit sound") != null) {
                    save();
                    refreshFolders(folderBox.getValue());
                    refreshSounds();
                    refreshUnsupported();
                    prefetch(sound);
                }
            });
            MenuItem remove = new MenuItem("Remove");
            remove.setOnAction(e -> {
                if (sound.getId().equals(player.ambienceSoundId())) player.stopAmbience();
                library.getSounds().remove(sound);
                save();
                refreshSounds();
                refreshUnsupported();
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
        dialog.setHeaderText("Choose an audio file or paste a direct link to one. Use start and end to play only part"
            + " of it, or tick Ambience for a background loop (rain, river, wind...).");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.initOwner(stage);
        owner.styleDialog(dialog);

        TextField name = new TextField(sound.getName() == null ? "" : sound.getName());
        name.setPromptText("Thunder");
        Spinner<Integer> start = new Spinner<>(0, 36000, sound.getStartSeconds());
        Spinner<Integer> end = new Spinner<>(0, 36000, sound.getEndSeconds());
        start.setEditable(true);
        end.setEditable(true);
        CheckBox ambience = owner.checkBox("Ambience - loop under the music until stopped");
        ambience.setSelected(sound.isAmbience());
        ComboBox<String> folder = folderPicker(sound.getFolder());
        AudioPicker[] picker = new AudioPicker[1];
        Runnable check = () -> {
            picker[0].refresh();
            dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(name.getText().isBlank() || !picker[0].valid());
        };
        picker[0] = new AudioPicker(sound, check);
        Button preview = new Button("▶ Preview");
        preview.getStyleClass().add("dnd-button");
        compact(preview);
        preview.setOnAction(e -> {
            if (!picker[0].valid()) return;
            MusicLibrary.Sound test = new MusicLibrary.Sound("preview", picker[0].link(), start.getValue(), end.getValue());
            test.setFile(picker[0].file());
            if (!player.playSound(library, test)) message.setText("This sound has no playable audio.");
        });
        name.textProperty().addListener((o, a, b) -> check.run());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Name:"), name);
        picker[0].addTo(grid, 1);
        grid.addRow(4, new Label("Start (s):"), start);
        grid.addRow(5, new Label("End (s, 0 = to the end):"), end);
        grid.add(ambience, 1, 6);
        grid.addRow(7, new Label("Folder:"), folder);
        grid.add(preview, 1, 8);
        dialog.getDialogPane().setContent(grid);
        check.run();

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        boolean audioChanged = !java.util.Objects.equals(picker[0].file(), sound.getFile())
            || !java.util.Objects.equals(picker[0].link(), sound.getUrl());
        sound.setName(name.getText().trim());
        sound.setFile(picker[0].file());
        sound.setUrl(picker[0].link());
        if (audioChanged) sound.setAttribution(null);
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
