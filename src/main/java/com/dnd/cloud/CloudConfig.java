package com.dnd.cloud;

import com.dnd.data.AppHome;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where the shared directory lives. Defaults ship inside the app ({@code /cloud.properties});
 * {@code data/auth/cloud.properties} next to the user's data and {@code -Ddnd.cloud.<key>}
 * system properties override them, and {@code -Ddnd.cloud=off} keeps everything local.
 */
public record CloudConfig(boolean enabled, String databaseUrl, String rootPath, String apiKey, String authUrl) {

    public static CloudConfig load() {
        Properties props = new Properties();
        try (InputStream in = CloudConfig.class.getResourceAsStream("/cloud.properties")) {
            if (in != null) props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Fall through to defaults.
        }
        Path override = AppHome.resolve("data", "auth", "cloud.properties");
        if (Files.exists(override)) {
            try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException ignored) {
                // A broken override shouldn't stop the app from starting.
            }
        }
        for (String key : new String[]{"databaseUrl", "rootPath", "apiKey", "authUrl", "enabled"}) {
            String sys = System.getProperty("dnd.cloud." + key);
            if (sys != null) props.setProperty(key, sys);
        }
        boolean enabled = !"off".equalsIgnoreCase(System.getProperty("dnd.cloud", ""))
            && !"false".equalsIgnoreCase(props.getProperty("enabled", "true"))
            && !props.getProperty("databaseUrl", "").isBlank();
        return new CloudConfig(enabled,
            props.getProperty("databaseUrl", "").trim(),
            props.getProperty("rootPath", "sessions/dnd-directory").trim(),
            props.getProperty("apiKey", "").trim(),
            props.getProperty("authUrl", "https://identitytoolkit.googleapis.com/v1").trim());
    }

    /** True when Firebase Authentication can send verification / reset emails. */
    public boolean emailLinksEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
