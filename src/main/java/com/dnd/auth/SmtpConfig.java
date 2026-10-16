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
 * control. If the file doesn't exist yet, {@link #loadOrCreateTemplate()}
 * writes out a disabled placeholder so the person setting up the app knows
 * exactly what to fill in.</p>
 */
public class SmtpConfig {
    public String host = "smtp.gmail.com";
    public int port = 587;
    public String username = "";
    public String password = "";
    public String fromAddress = "";
    public boolean useTls = true;
    /** Must be flipped to true (after filling in real credentials) for emails to actually send. */
    public boolean enabled = false;

    private static final Path FILE = com.dnd.data.AppHome.resolve("data", "auth", "smtp-config.json");

    public static SmtpConfig loadOrCreateTemplate() {
        try {
            if (Files.exists(FILE)) {
                ObjectMapper mapper = JsonMappers.create();
                return mapper.readValue(FILE.toFile(), SmtpConfig.class);
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
}
