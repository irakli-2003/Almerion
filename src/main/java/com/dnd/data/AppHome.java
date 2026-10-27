package com.dnd.data;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Where the app keeps its writable files (campaigns, accounts, profiles).
 *
 * <p>Run from source, this is the working directory, as before. In the installed Windows app
 * the install folder is read-only, so everything lives in {@code %APPDATA%\Almerion}
 * instead, and the campaigns bundled with the release are copied there on first start.
 * {@code -Ddnd.home=<folder>} overrides both.</p>
 */
public final class AppHome {
    private static final Logger LOGGER = Logger.getLogger(AppHome.class.getName());
    public static final String APP_FOLDER = com.dnd.boot.Launcher.APP_FOLDER;

    private AppHome() {}

    /** True when started from the installed app's launcher (jpackage sets this property). */
    public static boolean isInstalled() {
        return System.getProperty("jpackage.app-path") != null;
    }

    public static Path root() {
        String override = System.getProperty("dnd.home");
        if (override != null && !override.isBlank()) return Paths.get(override);
        if (!isInstalled()) return Paths.get("");
        return com.dnd.boot.Launcher.userDataFolder();
    }

    public static Path resolve(String first, String... more) {
        return root().resolve(Paths.get(first, more));
    }

    /**
     * Copies the campaigns bundled in the app's jar into the data folder, skipping any campaign
     * folder that already exists so the DM's own edits are never overwritten. Only runs for
     * the installed app (or when {@code dnd.home} is set).
     */
    public static void seedBundledData() {
        if (!isInstalled() && System.getProperty("dnd.home") == null) return;
        Path target = resolve("data");
        try {
            Path jar = Paths.get(AppHome.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(jar)) {
                seedFrom(jar.resolve("data"), target);
                return;
            }
            try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), Map.of())) {
                seedFrom(fs.getPath("/data"), target);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not copy the bundled campaigns into " + target, e);
        }
    }

    /** Copies {@code default-campaign} and each {@code custom-campaigns/<name>} that is missing. */
    static void seedFrom(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) return;
        copyIfMissing(source.resolve("default-campaign"), target.resolve("default-campaign"));
        Path custom = source.resolve("custom-campaigns");
        if (Files.isDirectory(custom)) {
            try (Stream<Path> campaigns = Files.list(custom)) {
                for (Path campaign : (Iterable<Path>) campaigns::iterator) {
                    if (!Files.isDirectory(campaign)) continue;
                    String name = campaign.getFileName().toString().replace("/", "");
                    copyIfMissing(campaign, target.resolve("custom-campaigns").resolve(name));
                }
            }
        }
    }

    private static void copyIfMissing(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from) || Files.exists(to)) return;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path dest = to;
                for (Path part : from.relativize(p)) dest = dest.resolve(part.toString().replace("/", ""));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
