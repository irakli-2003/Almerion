package com.dnd.ui.components;

import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Single entry point for image pickers. Every chooser opens in the project's {@code images}
 * folder (next to the app's working directory) so the DM always starts from the same place
 * where portraits, map backgrounds and textures are kept.
 */
public final class ImageFolders {

    public static final String[] IMAGE_PATTERNS =
        {"*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"};

    private ImageFolders() {}

    /** The shared images folder, created on demand. */
    public static Path imagesRoot() {
        Path root = com.dnd.data.AppHome.root().toAbsolutePath().resolve("images");
        try {
            Files.createDirectories(root);
        } catch (IOException ignored) {
            // Falls back to the chooser's default directory below if this can't be created.
        }
        return root;
    }

    /** A chooser titled {@code title} that opens in {@link #imagesRoot()} with image filters. */
    public static FileChooser imageChooser(String title) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        Path root = imagesRoot();
        if (Files.isDirectory(root)) fc.setInitialDirectory(root.toFile());
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Images", IMAGE_PATTERNS),
            new FileChooser.ExtensionFilter("All files", "*.*"));
        return fc;
    }
}
