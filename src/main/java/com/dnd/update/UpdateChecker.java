package com.dnd.update;

import com.dnd.boot.Launcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * Looks for a newer release on GitHub and downloads its app jar into the update folder, where
 * {@link Launcher} picks it up on the next start. Releases must attach the app jar as
 * {@value Launcher#UPDATE_JAR}; the installer .exe is offered when the libraries changed.
 */
public final class UpdateChecker {
    public static final String LATEST_RELEASE = "https://api.github.com/repos/irakli-2003/DnD/releases/latest";

    public record Release(String version, String jarUrl, String installerUrl, String pageUrl) {}

    public enum Outcome { READY_AFTER_RESTART, NEEDS_INSTALLER }

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** The running app's version, or null when started from source (then updates are off). */
    public static String currentVersion() {
        return UpdateChecker.class.getPackage().getImplementationVersion();
    }

    public static boolean enabled() {
        return currentVersion() != null && System.getProperty("jpackage.app-path") != null
            && !"off".equalsIgnoreCase(System.getProperty("dnd.update"));
    }

    /** The latest release if it is newer than this app, otherwise null. */
    public Release findNewer() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DnD-Campaign-Manager")
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        Release latest = parse(mapper.readTree(response.body()));
        if (latest == null || Launcher.compareVersions(latest.version(), currentVersion()) <= 0) return null;
        return latest;
    }

    static Release parse(JsonNode json) {
        String tag = json.path("tag_name").asText("");
        if (tag.isBlank() || json.path("draft").asBoolean() || json.path("prerelease").asBoolean()) return null;
        String jar = null;
        String installer = null;
        for (JsonNode asset : json.path("assets")) {
            String name = asset.path("name").asText("");
            String url = asset.path("browser_download_url").asText(null);
            if (name.equals(Launcher.UPDATE_JAR)) jar = url;
            else if (name.toLowerCase().endsWith(".exe")) installer = url;
        }
        return new Release(tag.startsWith("v") ? tag.substring(1) : tag, jar, installer,
            json.path("html_url").asText(null));
    }

    /**
     * Downloads the release's app jar. If it needs libraries this installation doesn't have,
     * nothing changes and the full installer is needed instead.
     */
    public Outcome download(Release release) throws IOException, InterruptedException {
        if (release.jarUrl() == null) return Outcome.NEEDS_INSTALLER;
        Path dir = Launcher.updateDir();
        Files.createDirectories(dir);
        Path partial = dir.resolve(Launcher.UPDATE_JAR + ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(release.jarUrl()))
            .timeout(Duration.ofMinutes(5))
            .header("User-Agent", "DnD-Campaign-Manager")
            .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("Download failed (HTTP " + response.statusCode() + ").");
        try (InputStream in = response.body()) {
            Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
        }
        return install(partial, dir.resolve(Launcher.UPDATE_JAR), installedLibraries());
    }

    static Outcome install(Path downloaded, Path target, Path libDir) throws IOException {
        try {
            if (Launcher.versionOf(downloaded) == null) throw new IOException("The downloaded file is not an app update.");
            List<String> missing = libDir == null ? List.of() : Launcher.missingLibraries(downloaded, libDir);
            if (!missing.isEmpty()) return Outcome.NEEDS_INSTALLER;
            Files.move(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
            return Outcome.READY_AFTER_RESTART;
        } finally {
            Files.deleteIfExists(downloaded);
        }
    }

    /** The installed app's library folder (where the bundled jars are). */
    static Path installedLibraries() {
        try {
            Path lib = Paths.get(ObjectMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(lib) ? lib.getParent() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Starts the installed app again (the launcher then picks up the update). */
    public static boolean restart() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe == null) return false;
        try {
            new ProcessBuilder(exe).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
