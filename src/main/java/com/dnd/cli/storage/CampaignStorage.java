package com.dnd.cli.storage;

import com.dnd.cli.core.CampaignContext;
import com.dnd.data.DataAccessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages campaign directories on disk. Every public method that touches the
 * filesystem surfaces failures as the unchecked {@link DataAccessException}
 * (consistent with {@link com.dnd.data.JsonRepository} and
 * {@link com.dnd.data.IdHandler}), instead of a mix of checked IOException and
 * silently-swallowed failures.
 */
public class CampaignStorage {
    private static final Logger LOGGER = Logger.getLogger(CampaignStorage.class.getName());

    private final Path dataRoot;
    private final Path defaultCampaignRoot;
    private final Path customCampaignRoot;

    public CampaignStorage() {
        this(resolveDefaultRoot());
    }

    public CampaignStorage(Path dataRoot) {
        this.dataRoot = dataRoot;
        this.defaultCampaignRoot = dataRoot.resolve("default-campaign");
        this.customCampaignRoot = dataRoot.resolve("custom-campaigns");
    }

    private static Path resolveDefaultRoot() {
        Path resources = com.dnd.data.AppHome.resolve("src", "main", "resources", "data");
        if (Files.exists(resources)) {
            return resources;
        }
        return com.dnd.data.AppHome.resolve("data");
    }

    public Path getDefaultCampaignRoot() {
        return defaultCampaignRoot;
    }

    public Path getCustomCampaignRoot() {
        return customCampaignRoot;
    }

    public void ensureInitialized() {
        try {
            Files.createDirectories(dataRoot);
            Files.createDirectories(customCampaignRoot);
            if (!Files.exists(defaultCampaignRoot)) {
                Files.createDirectories(defaultCampaignRoot);
                CampaignTemplate.writeTemplate(defaultCampaignRoot, false);
            }
        } catch (IOException e) {
            throw new DataAccessException("Failed to initialize campaign storage under " + dataRoot, e);
        }
    }

    public List<String> listCustomCampaigns() {
        if (!Files.exists(customCampaignRoot)) {
            return Collections.emptyList();
        }
        try (Stream<Path> children = Files.list(customCampaignRoot)) {
            return children
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .sorted()
                .collect(Collectors.toList());
        } catch (IOException e) {
            // Consistent with the rest of the data layer: fail loudly with an unchecked
            // exception rather than silently reporting "no campaigns exist", which could
            // otherwise look like data loss to the user.
            throw new DataAccessException("Failed to list custom campaigns under " + customCampaignRoot, e);
        }
    }

    public Path resolveCustomCampaignPath(String name) {
        return customCampaignRoot.resolve(name);
    }

    public CampaignContext createCampaignFromDefault(String requestedName, boolean blank) {
        ensureInitialized();

        String name = sanitizeName(requestedName);
        if (name.isEmpty()) {
            name = "campaign";
        }
        String uniqueName = ensureUniqueName(name);

        Path campaignPath = customCampaignRoot.resolve(uniqueName);
        try {
            Files.createDirectories(campaignPath);
            CampaignTemplate.writeTemplate(campaignPath, blank);
        } catch (IOException e) {
            throw new DataAccessException("Failed to create campaign at " + campaignPath, e);
        }

        return new CampaignContext(uniqueName, campaignPath);
    }

    private String ensureUniqueName(String baseName) {
        List<String> taken = new ArrayList<>(listCustomCampaigns());
        if (!taken.contains(baseName)) {
            return baseName;
        }
        int index = 2;
        while (taken.contains(baseName + "-" + index)) {
            index++;
        }
        return baseName + "-" + index;
    }

    private String sanitizeName(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim().toLowerCase(Locale.ROOT);
        String sanitized = trimmed.replaceAll("[^a-z0-9\\-_]+", "-");
        return sanitized.replaceAll("(^-+|-+$)", "");
    }

    public String normalizeCampaignName(String input) {
        return sanitizeName(input);
    }

    public String renameCampaign(String currentName, String requestedName) {
        ensureInitialized();

        String normalizedCurrent = sanitizeName(currentName);
        if (normalizedCurrent.isEmpty()) {
            throw new IllegalArgumentException("Campaign name is required.");
        }

        Path currentPath = customCampaignRoot.resolve(normalizedCurrent);
        if (!Files.exists(currentPath)) {
            throw new IllegalArgumentException("Unknown campaign: " + normalizedCurrent);
        }

        String normalizedRequested = sanitizeName(requestedName);
        if (normalizedRequested.isEmpty()) {
            throw new IllegalArgumentException("New campaign name is required.");
        }

        if (normalizedRequested.equals(normalizedCurrent)) {
            return normalizedCurrent;
        }

        String uniqueName = ensureUniqueName(normalizedRequested);
        Path newPath = customCampaignRoot.resolve(uniqueName);
        try {
            Files.move(currentPath, newPath);
        } catch (IOException e) {
            throw new DataAccessException("Failed to rename campaign " + normalizedCurrent + " to " + uniqueName, e);
        }
        return uniqueName;
    }

    public boolean deleteCampaign(String name) {
        ensureInitialized();

        String normalized = sanitizeName(name);
        if (normalized.isEmpty()) {
            return false;
        }

        Path campaignPath = customCampaignRoot.resolve(normalized);
        if (!Files.exists(campaignPath)) {
            return false;
        }

        deleteDirectory(campaignPath);
        return true;
    }

    private void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        List<Path> failures = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(current -> {
                    try {
                        Files.deleteIfExists(current);
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to delete " + current + " while removing campaign directory " + path, e);
                        failures.add(current);
                    }
                });
        } catch (IOException e) {
            throw new DataAccessException("Failed to walk campaign directory " + path + " for deletion", e);
        }
        if (!failures.isEmpty()) {
            throw new DataAccessException("Failed to fully delete campaign directory " + path
                + ". " + failures.size() + " entr" + (failures.size() == 1 ? "y" : "ies") + " could not be removed: " + failures);
        }
    }
}
