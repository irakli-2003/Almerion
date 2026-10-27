package com.dnd.ui.i18n;

import com.dnd.data.AppHome;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** App-wide preferences kept in {@code data/settings.json}. Unknown keys are preserved. */
public final class AppSettings {
    private static final Logger LOGGER = Logger.getLogger(AppSettings.class.getName());
    private static final String LANGUAGE = "language";

    private final Path file;
    private final Map<String, Object> values;

    private AppSettings(Path file, Map<String, Object> values) {
        this.file = file;
        this.values = values;
    }

    public static AppSettings load() {
        return load(AppHome.resolve("data", "settings.json"));
    }

    public static AppSettings load(Path file) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Object> read = new ObjectMapper().readValue(file.toFile(), new TypeReference<Map<String, Object>>() { });
                if (read != null) values.putAll(read);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Ignoring unreadable settings file " + file, e);
            }
        }
        return new AppSettings(file, values);
    }

    /** The UI language code; English unless the user picked something else. */
    public synchronized String getLanguage() {
        Object code = values.get(LANGUAGE);
        return code instanceof String s && !s.isBlank() ? Language.byCode(s).code() : Language.ENGLISH_CODE;
    }

    public synchronized void setLanguage(String code) {
        values.put(LANGUAGE, Language.byCode(code).code());
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(temp.toFile(), values);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save settings to " + file, e);
        }
    }
}
