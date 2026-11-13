package com.dnd.data;

import com.dnd.model.session.TrackedCreature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the creatures the DM added to one session file's "Manage Players" panel.
 * They live in a hidden sidecar next to the session file ({@code .<file>.roster.json}), so
 * each session keeps its own encounter and the sidecar follows the file when it is moved.
 */
public final class SessionRosterStore {

    static final String PREFIX = ".";
    static final String SUFFIX = ".roster.json";

    private static final ObjectMapper MAPPER = JsonMappers.create();

    private SessionRosterStore() {
    }

    public static Path rosterFile(Path sessionFile) {
        return sessionFile.resolveSibling(PREFIX + sessionFile.getFileName() + SUFFIX);
    }

    public static boolean isRosterFile(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
    }

    public static List<TrackedCreature> load(Path sessionFile) {
        rejectPrivateFile(sessionFile);
        Path file = rosterFile(sessionFile);
        if (!Files.isRegularFile(file)) return new ArrayList<>();
        try {
            List<TrackedCreature> list = MAPPER.readValue(file.toFile(), new TypeReference<List<TrackedCreature>>() { });
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void save(Path sessionFile, List<TrackedCreature> creatures) {
        rejectPrivateFile(sessionFile);
        try {
            OperationHistory.writeRoster(sessionFile, creatures, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void rejectPrivateFile(Path sessionFile) {
        try {
            if (Files.isRegularFile(sessionFile)) {
                try (var reader = Files.newBufferedReader(sessionFile)) {
                    String first = reader.readLine();
                    if (com.dnd.security.PrivateSessionFiles.isProtected(first)) {
                        throw new SecurityException("Private session rosters must be accessed through the encrypted storyline service.");
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
