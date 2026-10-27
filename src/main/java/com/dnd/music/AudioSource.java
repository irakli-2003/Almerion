package com.dnd.music;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules for where music and sounds may come from: audio files on the GM's computer, or
 * direct links to an audio file (downloaded once, then played from disk). Links to
 * streaming pages (YouTube, Spotify, SoundCloud...) are refused - their terms don't allow
 * extracting or background-playing their audio.
 */
public final class AudioSource {

    /** Formats JavaFX Media can play. */
    public static final List<String> EXTENSIONS = List.of("mp3", "wav", "aif", "aiff", "m4a", "aac");

    public static final String PAGE_LINK_MESSAGE = "That link is a web page, not an audio file."
        + " Use a direct link to an .mp3/.wav/.m4a file, or add a file from your computer.";
    public static final String NOT_HTTP_MESSAGE = "That isn't a web link. Use a direct link that starts with"
        + " https:// and points to an .mp3/.wav/.m4a file, or add a file from your computer.";
    public static final String UNSUPPORTED_FILE_MESSAGE = "That file type can't be played."
        + " Use an MP3, WAV, AIFF or M4A (AAC) file.";
    public static final String YOUTUBE_MESSAGE = "YouTube links are no longer supported - replace with an audio file.";

    private static final List<String> STREAMING_HOSTS = List.of(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "spotify.com", "spotify.link", "soundcloud.com",
        "on.soundcloud.com", "deezer.com", "music.apple.com", "itunes.apple.com", "bandcamp.com", "tidal.com",
        "music.amazon.com", "pandora.com", "vimeo.com", "dailymotion.com", "twitch.tv",
        "tiktok.com", "mixcloud.com", "audiomack.com", "napster.com", "anghami.com");
    private static final Pattern TIME = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?");
    private static final Pattern CLOCK = Pattern.compile("(?:(\\d+):)?(\\d+):(\\d{1,2})");

    private AudioSource() {
    }

    /** True for http(s) links. */
    public static boolean isHttp(String url) {
        URI uri = uri(url);
        return uri != null && uri.getHost() != null
            && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
    }

    /** True for links to YouTube (what older versions of the app stored). */
    public static boolean isYouTube(String url) {
        if (url == null || url.isBlank()) return false;
        String raw = url.trim();
        if (raw.matches("[A-Za-z0-9_-]{11}")) return true;
        String host = host(raw.contains("://") ? raw : "https://" + raw);
        return host != null && (matches(host, "youtube.com") || matches(host, "youtu.be")
            || matches(host, "youtube-nocookie.com"));
    }

    /** True for links to streaming services and video sites, which are web pages rather than audio files. */
    public static boolean isStreamingPage(String url) {
        if (isYouTube(url)) return true;
        String host = host(url);
        if (host == null) return false;
        for (String h : STREAMING_HOSTS) if (matches(host, h)) return true;
        return false;
    }

    /** Null when the link can be downloaded as audio, otherwise the message explaining why not. */
    public static String validateLink(String url) {
        if (url == null || url.isBlank() || !isHttp(url.trim())) return NOT_HTTP_MESSAGE;
        if (isStreamingPage(url.trim())) return PAGE_LINK_MESSAGE;
        String ext = extension(uri(url.trim()).getPath());
        if (ext != null && isPageExtension(ext)) return PAGE_LINK_MESSAGE;
        return null;
    }

    private static boolean isPageExtension(String ext) {
        return List.of("html", "htm", "php", "asp", "aspx", "jsp").contains(ext);
    }

    public static boolean isSupportedFile(Path file) {
        String ext = extension(file == null ? null : file.getFileName().toString());
        return ext != null && EXTENSIONS.contains(ext);
    }

    /** Lower-case extension without the dot, or null. */
    public static String extension(String name) {
        if (name == null) return null;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String last = name.substring(slash + 1);
        int dot = last.lastIndexOf('.');
        if (dot <= 0 || dot == last.length() - 1) return null;
        return last.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * The audio format the first bytes of a file belong to ("mp3", "wav", "aiff", "m4a"),
     * or null when they don't look like audio JavaFX can play (e.g. an HTML page or OGG).
     */
    public static String sniff(byte[] head) {
        if (head == null || head.length < 4) return null;
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') return "mp3";
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
            // Frame sync: layer bits 00 mean an ADTS (AAC) stream, anything else an MPEG audio frame.
            if ((head[1] & 0x06) != 0) return "mp3";
            if ((head[1] & 0xF0) == 0xF0) return "aac";
        }
        if (head.length >= 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WAVE")) return "wav";
        if (head.length >= 12 && ascii(head, 0, "FORM") && (ascii(head, 8, "AIFF") || ascii(head, 8, "AIFC"))) return "aiff";
        if (head.length >= 8 && ascii(head, 4, "ftyp")) return "m4a";
        return null;
    }

    private static boolean ascii(byte[] data, int at, String text) {
        if (data.length < at + text.length()) return false;
        for (int i = 0; i < text.length(); i++) if (data[at + i] != text.charAt(i)) return false;
        return true;
    }

    /** Cache file name for a downloaded link: a hash of the link, so the same link is fetched once. */
    public static String cacheKey(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Resolves a library-relative path such as {@code music/rain.mp3} inside the campaign
     * folder; returns null for absolute paths or paths that would leave the folder.
     */
    public static Path resolve(Path campaignRoot, String relative) {
        if (campaignRoot == null || relative == null || relative.isBlank()) return null;
        try {
            Path rel = Path.of(relative.replace('\\', '/'));
            if (rel.isAbsolute() || rel.getRoot() != null) return null;
            Path base = campaignRoot.toAbsolutePath().normalize();
            Path full = base.resolve(rel).normalize();
            return full.startsWith(base) ? full : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Parses "90", "1:30", "1:02:05", "1m30s" or "1h2m5s" into seconds; -1 if it isn't a time. */
    public static int parseTime(String text) {
        if (text == null || text.isBlank()) return 0;
        String t = text.trim().toLowerCase(Locale.ROOT);
        Matcher c = CLOCK.matcher(t);
        if (c.matches()) {
            int h = c.group(1) == null ? 0 : Integer.parseInt(c.group(1));
            int m = Integer.parseInt(c.group(2));
            int s = Integer.parseInt(c.group(3));
            if (c.group(1) == null) return m * 60 + s;
            return h * 3600 + m * 60 + s;
        }
        Matcher m = TIME.matcher(t);
        if (!m.matches()) return -1;
        int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
        int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        int s = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return h * 3600 + min * 60 + s;
    }

    /** "1:30" style text for a number of seconds ("" for 0). */
    public static String formatTime(int seconds) {
        if (seconds <= 0) return "";
        if (seconds >= 3600) return String.format("%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static URI uri(String url) {
        if (url == null) return null;
        try {
            return URI.create(url.trim().replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String host(String url) {
        URI uri = uri(url);
        return uri == null || uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
