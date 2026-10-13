package com.dnd.ui.print;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.PlayerCharacter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small "Print character sheets" window: tick characters, press Print, and one PDF sheet per
 * character lands in the user's Downloads folder.
 */
public final class CharacterSheetPrintDialog {

    private static final String CSS_PATH = "/com/dnd/ui/styles/dnd-theme.css";
    private static final java.util.regex.Pattern ILLEGAL = java.util.regex.Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final java.util.Set<String> RESERVED = java.util.Set.of(
            "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static Stage openStage;

    private CharacterSheetPrintDialog() {
    }

    /** Opens the print panel for every player character in {@code repos}'s campaign. */
    public static void show(Window owner, CampaignRepositories repos) {
        show(owner, repos, null);
    }

    /** As {@link #show(Window, CampaignRepositories)}, printing {@code campaignName} in each sheet's header. */
    public static void show(Window owner, CampaignRepositories repos, String campaignName) {
        if (openStage != null && openStage.isShowing()) openStage.close();

        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("Print character sheets");

        List<PlayerCharacter> players = new ArrayList<>();
        String loadError = null;
        try {
            if (repos != null) players.addAll(repos.players().list());
        } catch (RuntimeException e) {
            loadError = "Could not read the players: " + e.getMessage();
        }

        Label title = new Label("Print character sheets");
        title.getStyleClass().add("subtitle-label");
        Label hint = new Label("Choose characters. One PDF per character is saved to " + downloadsFolder() + ".");
        hint.getStyleClass().add("body-label");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);

        Map<CheckBox, PlayerCharacter> boxes = new LinkedHashMap<>();
        VBox list = new VBox(6);
        list.setPadding(new Insets(8));
        for (PlayerCharacter pc : players) {
            if (pc == null) continue;
            CheckBox cb = checkBox(describe(pc));
            boxes.put(cb, pc);
            list.getChildren().add(cb);
        }
        if (boxes.isEmpty()) {
            Label none = new Label(loadError != null ? loadError : "This campaign has no player characters yet.");
            none.getStyleClass().add("muted-label");
            list.getChildren().add(none);
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(320, Math.max(80, boxes.size() * 30 + 16)));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label status = new Label();
        status.getStyleClass().add("body-label");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);

        Button openFolder = button("Open folder");
        openFolder.setVisible(false);
        openFolder.setManaged(false);
        openFolder.setOnAction(e -> openFolder(downloadsFolder()));

        Button selectAll = button("Select all");
        Runnable syncSelectLabel = () -> {
            boolean all = !boxes.isEmpty() && boxes.keySet().stream().allMatch(CheckBox::isSelected);
            selectAll.setText(all ? "Select none" : "Select all");
        };
        for (CheckBox cb : boxes.keySet()) cb.selectedProperty().addListener((o, a, b) -> syncSelectLabel.run());
        selectAll.setOnAction(e -> {
            boolean all = boxes.keySet().stream().allMatch(CheckBox::isSelected);
            for (CheckBox cb : boxes.keySet()) cb.setSelected(!all);
        });
        selectAll.setDisable(boxes.isEmpty());

        Button print = button("Print");
        print.setDefaultButton(true);
        print.setDisable(boxes.isEmpty());
        print.setOnAction(e -> {
            List<PlayerCharacter> chosen = new ArrayList<>();
            for (Map.Entry<CheckBox, PlayerCharacter> en : boxes.entrySet()) if (en.getKey().isSelected()) chosen.add(en.getValue());
            if (chosen.isEmpty()) {
                status.setText("Tick at least one character first.");
                return;
            }
            print.setDisable(true);
            selectAll.setDisable(true);
            openFolder.setVisible(false);
            openFolder.setManaged(false);
            status.setText("Printing " + chosen.size() + " sheet" + (chosen.size() == 1 ? "" : "s") + "...");
            Thread worker = new Thread(() -> {
                List<String> done = new ArrayList<>();
                List<String> failed = new ArrayList<>();
                Path dir = downloadsFolder();
                for (PlayerCharacter pc : chosen) {
                    try {
                        Path file = writeSheet(pc, repos, campaignName, dir);
                        done.add(file.getFileName().toString());
                    } catch (Exception ex) {
                        failed.add(describe(pc) + ": " + ex.getMessage());
                    }
                }
                Platform.runLater(() -> {
                    StringBuilder sb = new StringBuilder();
                    if (!done.isEmpty()) {
                        sb.append("Saved to ").append(dir).append(':');
                        for (String f : done) sb.append("\n  • ").append(f);
                    }
                    if (!failed.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append("Failed:");
                        for (String f : failed) sb.append("\n  • ").append(f);
                    }
                    status.setText(sb.toString());
                    openFolder.setVisible(!done.isEmpty());
                    openFolder.setManaged(!done.isEmpty());
                    print.setDisable(false);
                    selectAll.setDisable(false);
                    stage.sizeToScene();
                });
            }, "character-sheet-print");
            worker.setDaemon(true);
            worker.start();
        });

        Button close = button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox buttons = new HBox(8, selectAll, gap, openFolder, print, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, title, hint, scroll, buttons, status);
        root.getStyleClass().add("root");
        root.setPadding(new Insets(14));

        Scene scene = new Scene(root, 440, Region.USE_COMPUTED_SIZE);
        URL css = CharacterSheetPrintDialog.class.getResource(CSS_PATH);
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(360);
        stage.setOnHidden(e -> {
            if (openStage == stage) openStage = null;
        });
        openStage = stage;
        stage.show();
    }

    /** {@code <user.home>/Downloads}, created if it does not exist yet. */
    static Path downloadsFolder() {
        Path dir = Paths.get(System.getProperty("user.home"), "Downloads");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // reported when the PDF itself fails to save
        }
        return dir;
    }

    /**
     * Writes {@code pc}'s sheet into {@code dir} as "&lt;name&gt; - Character Sheet.pdf"; if that
     * file is locked (e.g. open in a PDF viewer) a numbered name is used instead.
     */
    public static Path writeSheet(PlayerCharacter pc, CampaignRepositories repos, String campaignName, Path dir) throws IOException {
        Files.createDirectories(dir);
        String base = fileBaseName(pc);
        IOException last = null;
        for (int n = 1; n <= 20; n++) {
            Path target = dir.resolve(base + (n == 1 ? "" : " (" + n + ")") + ".pdf");
            try {
                CharacterSheetPdf.write(pc, repos, campaignName, target);
                return target;
            } catch (IOException e) {
                last = e;
                if (!Files.exists(target)) throw e;
            }
        }
        throw last;
    }

    /** "&lt;Character Name&gt; - Character Sheet" with characters Windows forbids in file names replaced. */
    static String fileBaseName(PlayerCharacter pc) {
        String name = pc == null ? null : pc.getName();
        if (name == null || name.isBlank()) name = pc == null || pc.getId() == null ? "Character" : pc.getId();
        String clean = ILLEGAL.matcher(name).replaceAll("_").trim();
        while (clean.endsWith(".") || clean.endsWith(" ")) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty()) clean = "Character";
        if (RESERVED.contains(clean.toUpperCase(Locale.ROOT))) clean = "_" + clean;
        if (clean.length() > 120) clean = clean.substring(0, 120).trim();
        return clean + " - Character Sheet";
    }

    private static String describe(PlayerCharacter pc) {
        String name = pc.getName() == null || pc.getName().isBlank() ? pc.getId() : pc.getName();
        String player = pc.getPlayerName();
        String extra = " (level " + pc.getLevel() + ")";
        if (player != null && !player.isBlank() && !player.equalsIgnoreCase("unassigned")) extra += " · " + player;
        return name + extra;
    }

    private static Button button(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("dnd-button");
        return b;
    }

    /** Check box with a single deterministic toggle per click (see BaseScene#checkBox). */
    private static CheckBox checkBox(String text) {
        CheckBox cb = new CheckBox(text);
        cb.getStyleClass().add("dnd-check-box");
        cb.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            cb.requestFocus();
            cb.setSelected(!cb.isSelected());
            e.consume();
        });
        return cb;
    }

    private static void openFolder(Path dir) {
        Thread t = new Thread(() -> {
            boolean opened = false;
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(dir.toFile());
                    opened = true;
                }
            } catch (Throwable ignored) {
                // fall back to the platform's file manager below
            }
            if (opened) return;
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String cmd = os.contains("win") ? "explorer.exe" : os.contains("mac") ? "open" : "xdg-open";
            try {
                new ProcessBuilder(cmd, dir.toString()).start();
            } catch (IOException ignored) {
                // nothing else to try; the path is shown in the panel
            }
        }, "open-downloads");
        t.setDaemon(true);
        t.start();
    }
}
