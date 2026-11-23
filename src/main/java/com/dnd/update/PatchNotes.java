package com.dnd.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Bundled release notes and per-version acknowledgements stored on this device. */
public final class PatchNotes {
    private final Path acknowledgements;

    public PatchNotes(Path acknowledgements) {
        this.acknowledgements = acknowledgements;
    }

    public Optional<String> pending(String version) throws IOException {
        validateVersion(version);
        if (Files.exists(acknowledgements.resolve(version))) return Optional.empty();
        try (InputStream input = PatchNotes.class.getResourceAsStream("/patch-notes/" + version + ".txt")) {
            if (input == null) throw new IOException("Missing bundled patch notes for version " + version);
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) throw new IOException("Empty bundled patch notes for version " + version);
            return Optional.of(text);
        }
    }

    public void acknowledge(String version) throws IOException {
        validateVersion(version);
        Files.createDirectories(acknowledgements);
        Files.writeString(acknowledgements.resolve(version), "acknowledged\n", StandardCharsets.UTF_8);
    }

    private static void validateVersion(String version) {
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid patch notes version: " + version);
        }
    }
}
