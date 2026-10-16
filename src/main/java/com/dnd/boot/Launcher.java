package com.dnd.boot;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Entry point of the installed app. Starts a downloaded update of the app jar when one is
 * newer than the installed jar, so new versions don't need the installer; otherwise starts
 * the installed app as usual. Kept free of any other app class on purpose: everything else is
 * loaded from whichever jar wins.
 */
public final class Launcher {
    public static final String APP_MAIN = "com.dnd.cli.CliApp";
    public static final String UPDATE_JAR = "dnd-campaign-manager.jar";

    private Launcher() {}

    public static void main(String[] args) throws Throwable {
        Path installedJar = ownJar();
        Path update = updateDir().resolve(UPDATE_JAR);
        ClassLoader loader = Launcher.class.getClassLoader();
        if (installedJar != null && shouldUse(update, installedJar)) {
            try {
                loader = updatedLoader(update, installedJar);
            } catch (IOException e) {
                System.err.println("Ignoring the downloaded update: " + e);
            }
        }
        Thread.currentThread().setContextClassLoader(loader);
        Method main = Class.forName(APP_MAIN, true, loader).getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }

    /** Where downloaded updates live: next to the user's data, which is always writable. */
    public static Path updateDir() {
        String home = System.getProperty("dnd.home");
        if (home != null && !home.isBlank()) return Paths.get(home, "app");
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank()
            ? Paths.get(appData) : Paths.get(System.getProperty("user.home"));
        return base.resolve("DnD Campaign Manager").resolve("app");
    }

    public static boolean shouldUse(Path update, Path installedJar) {
        if (!Files.isRegularFile(update)) return false;
        try {
            String newVersion = versionOf(update);
            String installed = versionOf(installedJar);
            return newVersion != null && compareVersions(newVersion, installed) > 0
                && missingLibraries(update, installedJar.getParent()).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static ClassLoader updatedLoader(Path update, Path installedJar) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(update.toUri().toURL());
        try (Stream<Path> files = Files.list(installedJar.getParent())) {
            for (Path p : (Iterable<Path>) files.sorted()::iterator) {
                if (p.toString().endsWith(".jar") && !p.equals(installedJar)) urls.add(p.toUri().toURL());
            }
        }
        return new URLClassLoader("dnd-update", urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static Path ownJar() {
        try {
            Path p = Paths.get(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String versionOf(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            var manifest = jf.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        }
    }

    /** Libraries the jar's manifest lists that aren't in {@code libDir} (then the full installer is needed). */
    public static List<String> missingLibraries(Path jar, Path libDir) throws IOException {
        List<String> missing = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            var manifest = jf.getManifest();
            String cp = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (cp == null) return missing;
            for (String lib : cp.trim().split("\\s+")) {
                if (!lib.isEmpty() && !Files.isRegularFile(libDir.resolve(lib))) missing.add(lib);
            }
        }
        return missing;
    }

    /** Compares dotted versions such as {@code 1.10.0} and {@code v1.2}; null counts as oldest. */
    public static int compareVersions(String a, String b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        String[] x = clean(a).split("\\.");
        String[] y = clean(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(part(x, i), part(y, i));
            if (c != 0) return c;
        }
        return 0;
    }

    private static String clean(String v) {
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int dash = v.indexOf('-');
        return dash >= 0 ? v.substring(0, dash) : v;
    }

    private static int part(String[] parts, int i) {
        if (i >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[i].replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
