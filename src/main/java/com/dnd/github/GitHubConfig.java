package com.dnd.github;

import com.dnd.data.AppHome;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The GitHub OAuth App used to link accounts with the device flow. {@code github.clientId} ships
 * in {@code /cloud.properties} (empty by default), can be overridden in
 * {@code data/auth/cloud.properties}, then by the {@code ALMERION_GITHUB_CLIENT_ID} environment
 * variable, then by {@code -Ddnd.github.clientId=...}. Client IDs are public, not secrets.
 */
public record GitHubConfig(String clientId) {
    public static final String PROPERTY = "github.clientId";

    public static GitHubConfig load() {
        Properties props = new Properties();
        try (InputStream in = GitHubConfig.class.getResourceAsStream("/cloud.properties")) {
            if (in != null) props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Fall through to the overrides.
        }
        Path override = AppHome.resolve("data", "auth", "cloud.properties");
        if (Files.exists(override)) {
            try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException ignored) {
                // A broken override shouldn't stop the app.
            }
        }
        return new GitHubConfig(resolve(props.getProperty(PROPERTY, ""),
            System.getenv("ALMERION_GITHUB_CLIENT_ID"), System.getProperty("dnd." + PROPERTY)));
    }

    /** Later, non-blank values win. */
    static String resolve(String... candidates) {
        String result = "";
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) result = candidate.trim();
        }
        return result;
    }

    /** True when accounts can be linked by signing in on github.com (otherwise a token is pasted). */
    public boolean deviceFlowAvailable() {
        return clientId != null && !clientId.isBlank();
    }
}
