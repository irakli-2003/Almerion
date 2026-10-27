package com.dnd.music;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;

/**
 * Puts audio into the campaign folder so backups and moving to another computer carry it:
 * imported files go to {@code <campaign>/music/}, downloaded links to
 * {@code <campaign>/music/cache/<hash>.<ext>} (fetched once, then played offline).
 */
public final class AudioFiles {

    public static final String MUSIC_DIR = "music";
    public static final String CACHE_DIR = "music/cache";
    /** Largest download accepted - a long soundtrack is ~100 MB as MP3. */
    static final long MAX_BYTES = 300L * 1024 * 1024;

    public static final String DOWNLOAD_FAILED_MESSAGE = "The audio file couldn't be downloaded."
        + " Check the internet connection and the link, then try again.";
    public static final String TOO_LARGE_MESSAGE = "That audio file is too large (over 300 MB).";

    /** Receives download progress; {@code total} is -1 when the server doesn't say. */
    public interface Progress {
        void update(long done, long total);
    }

    /** A download or import that failed, with a message for the GM. */
    public static class AudioException extends IOException {
        public AudioException(String message) {
            super(message);
        }

        public AudioException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private AudioFiles() {
    }

    /**
     * Copies an audio file into {@code <campaign>/music/} (renaming it if the name is taken by a
     * different file) and returns its campaign-relative path, e.g. {@code music/rain.mp3}.
     */
    public static String importFile(Path campaignRoot, Path source) throws IOException {
        if (!AudioSource.isSupportedFile(source)) throw new AudioException(AudioSource.UNSUPPORTED_FILE_MESSAGE);
        Path dir = campaignRoot.resolve(MUSIC_DIR);
        Files.createDirectories(dir);
        Path absSource = source.toAbsolutePath().normalize();
        if (absSource.getParent() != null && absSource.getParent().equals(dir.toAbsolutePath().normalize())) {
            return MUSIC_DIR + "/" + absSource.getFileName();
        }
        String name = safeName(source.getFileName().toString());
        Path target = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        String stem = name.substring(0, dot);
        String ext = name.substring(dot);
        for (int i = 2; Files.exists(target); i++) {
            if (Files.size(target) == Files.size(source) && Files.mismatch(target, source) == -1) {
                return MUSIC_DIR + "/" + target.getFileName();
            }
            target = dir.resolve(stem + " (" + i + ")" + ext);
        }
        Files.copy(source, target);
        return MUSIC_DIR + "/" + target.getFileName();
    }

    private static String safeName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return cleaned.startsWith(".") ? "audio" + cleaned : cleaned;
    }

    /** The already-downloaded copy of a link in the campaign's cache, or null. */
    public static Path cached(Path campaignRoot, String url) {
        if (campaignRoot == null || url == null || url.isBlank()) return null;
        Path dir = campaignRoot.resolve(CACHE_DIR);
        if (!Files.isDirectory(dir)) return null;
        String key = AudioSource.cacheKey(url);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, key + ".*")) {
            for (Path f : files) {
                if (AudioSource.isSupportedFile(f) && Files.size(f) > 0) return f;
            }
        } catch (IOException ignored) {
            // Treated as not cached; the download will report a real problem.
        }
        return null;
    }

    /**
     * Downloads a direct audio link into the campaign's cache unless it is there already, and
     * returns the local file. Web pages (HTML, streaming sites) are refused with
     * {@link AudioSource#PAGE_LINK_MESSAGE}.
     */
    public static Path download(Path campaignRoot, String url, Progress progress) throws IOException {
        String invalid = AudioSource.validateLink(url);
        if (invalid != null) throw new AudioException(invalid);
        Path existing = cached(campaignRoot, url);
        if (existing != null) return existing;

        Path dir = campaignRoot.resolve(CACHE_DIR);
        Files.createDirectories(dir);
        String key = AudioSource.cacheKey(url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim().replace(" ", "%20")))
            .header("User-Agent", "Almerion/1.0 (tabletop campaign manager)")
            .header("Accept", "audio/*, application/octet-stream;q=0.5")
            .timeout(Duration.ofMinutes(10))
            .GET().build();
        HttpResponse<InputStream> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AudioException(DOWNLOAD_FAILED_MESSAGE, e);
        } catch (IOException | IllegalArgumentException e) {
            throw new AudioException(DOWNLOAD_FAILED_MESSAGE, e);
        }
        Path part = dir.resolve(key + ".part");
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) throw new AudioException(DOWNLOAD_FAILED_MESSAGE);
            String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (type.startsWith("text/") || type.contains("html") || type.contains("json") || type.contains("xml")) {
                throw new AudioException(AudioSource.PAGE_LINK_MESSAGE);
            }
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (total > MAX_BYTES) throw new AudioException(TOO_LARGE_MESSAGE);

            byte[] head = in.readNBytes(16);
            String format = AudioSource.sniff(head);
            if (format == null) {
                throw new AudioException(type.startsWith("audio/") ? AudioSource.UNSUPPORTED_FILE_MESSAGE
                    : AudioSource.PAGE_LINK_MESSAGE);
            }
            long done = head.length;
            try (OutputStream out = Files.newOutputStream(part)) {
                out.write(head);
                byte[] buffer = new byte[64 * 1024];
                long lastReport = 0;
                for (int n; (n = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                    done += n;
                    if (done > MAX_BYTES) throw new AudioException(TOO_LARGE_MESSAGE);
                    if (progress != null && done - lastReport >= 256 * 1024) {
                        lastReport = done;
                        progress.update(done, total);
                    }
                    if (Thread.currentThread().isInterrupted()) throw new AudioException(DOWNLOAD_FAILED_MESSAGE);
                }
            }
            if (total > 0 && done < total) throw new AudioException(DOWNLOAD_FAILED_MESSAGE);
            if (progress != null) progress.update(done, total);
            Path target = dir.resolve(key + "." + format);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (AudioException e) {
            Files.deleteIfExists(part);
            throw e;
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw new AudioException(DOWNLOAD_FAILED_MESSAGE, e);
        }
    }
}
