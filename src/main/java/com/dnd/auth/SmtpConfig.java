package com.dnd.auth;

import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SMTP credentials used to send verification/password-recovery emails.
 *
 * <p>This app has no backend server, so it sends email directly via SMTP
 * using whatever provider the installer has an account with (Gmail, Outlook,
 * SendGrid, a company relay, ...). Credentials live in a local, gitignored
 * config file at {@code data/auth/smtp-config.json} rather than in source
 * control. In-app setup protects the password with Windows DPAPI.
 * If the file doesn't exist yet, {@link #loadOrCreateTemplate()}
 * writes out a disabled placeholder so the person setting up the app knows
 * exactly what to fill in.</p>
 */
public class SmtpConfig {
    public static final String SERVICE_ADDRESS = "almerion.d20@gmail.com";
    public String host = "smtp.gmail.com";
    public int port = 587;
    public String username = SERVICE_ADDRESS;
    public String password = "";
    public String protectedPassword = "";
    public String fromAddress = "";
    public boolean useTls = true;
    /** Must be flipped to true (after filling in real credentials) for emails to actually send. */
    public boolean enabled = false;

    private static final Path FILE = com.dnd.data.AppHome.resolve("data", "auth", "smtp-config.json");

    public static SmtpConfig loadOrCreateTemplate() {
        try {
            if (Files.exists(FILE)) {
                ObjectMapper mapper = JsonMappers.create();
                SmtpConfig config = mapper.readValue(FILE.toFile(), SmtpConfig.class);
                if (config != null && migrateServiceAddress(config)) {
                    try { mapper.writeValue(FILE.toFile(), config); }
                    catch (IOException saveFailure) {
                        java.util.logging.Logger.getLogger(SmtpConfig.class.getName()).log(java.util.logging.Level.WARNING,
                            "Service address updated in memory, but SMTP configuration could not be saved", saveFailure);
                    }
                }
                return config;
            }
            SmtpConfig template = new SmtpConfig();
            Files.createDirectories(FILE.getParent());
            ObjectMapper mapper = JsonMappers.create();
            mapper.writeValue(FILE.toFile(), template);
            return template;
        } catch (IOException e) {
            // Fall back to a disabled config rather than crashing the app over a missing/
            // unwritable config file - the UI's fallback (showing the code on-screen) still works.
            return new SmtpConfig();
        }
    }

    public static Path configPath() {
        return FILE;
    }

    String smtpPassword() throws IOException {
        return protectedPassword == null || protectedPassword.isBlank()
            ? password : new String(java.util.Base64.getDecoder().decode(
                new com.dnd.github.WindowsDpapi().unprotect(protectedPassword)), java.nio.charset.StandardCharsets.UTF_8);
    }

    public void saveWithProtectedPassword() throws IOException {
        saveWithProtectedPassword(FILE, new com.dnd.github.WindowsDpapi());
    }

    void saveWithProtectedPassword(Path file, com.dnd.github.TokenStore.Protector protector) throws IOException {
        SmtpConfig stored = new SmtpConfig();
        stored.host = host;
        stored.port = port;
        stored.username = username;
        stored.fromAddress = fromAddress;
        stored.useTls = useTls;
        stored.enabled = enabled;
        stored.protectedPassword = protector.protect(java.util.Base64.getEncoder()
            .encodeToString(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "smtp-config-", ".tmp");
        try {
            JsonMappers.create().writeValue(temporary.toFile(), stored);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static boolean migrateServiceAddress(SmtpConfig config) {
        boolean changed = false;
        if ("dndmanagerd20@gmail.com".equalsIgnoreCase(config.username)) {
            config.username = SERVICE_ADDRESS;
            changed = true;
        }
        if ("dndmanagerd20@gmail.com".equalsIgnoreCase(config.fromAddress)) {
            config.fromAddress = SERVICE_ADDRESS;
            changed = true;
        }
        return changed;
    }
}
