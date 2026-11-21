package com.dnd.auth;

import com.dnd.data.AppHome;
import com.dnd.github.WindowsDpapi;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Optional;

public final class RememberedLogin {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;

    public RememberedLogin() {
        this(AppHome.resolve("data", "auth", "remembered-login.dpapi"));
    }

    RememberedLogin(Path file) {
        this.file = file;
    }

    public record Credentials(String identifier, String password) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    public boolean isSupported() { return WindowsDpapi.isWindows(); }

    public Optional<Credentials> load() throws IOException {
        if (!isSupported()) return Optional.empty();
        try {
            String encoded = new WindowsDpapi().unprotect(Files.readString(file));
            return Optional.of(JSON.readValue(Base64.getDecoder().decode(encoded), Credentials.class));
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid saved credentials", malformed);
        }
    }

    public void save(String identifier, String password) throws IOException {
        if (!isSupported()) throw new IOException("Windows credential protection is unavailable.");
        // The PowerShell helper accepts ASCII, so encode Unicode credentials before encrypting.
        String encoded = Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(new Credentials(identifier, password)));
        String encrypted = new WindowsDpapi().protect(encoded);
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "remembered-login-", ".tmp");
        try {
            Files.writeString(temp, encrypted);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }
}
