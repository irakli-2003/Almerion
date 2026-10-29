package com.dnd.ui;

import javafx.scene.image.Image;
import java.io.*;
import java.lang.ref.SoftReference;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ImageStore {

    /**
     * Decoded images by file. Maps redraw many times a second and decoding a large map picture
     * every time used gigabytes of memory, so each file is decoded once and reused until it
     * changes on disk. Soft references let Java drop pictures again when memory runs low.
     */
    private static final int CACHE_SIZE = 96;
    private static final Map<Path, Cached> CACHE = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private record Cached(SoftReference<Image> image, long modified, long size) {
    }

    private ImageStore() {}

    public static String copyImage(Path campaignRoot, String category, String entityId, File sourceFile) throws IOException {
        String ext = getExtension(sourceFile.getName());
        Path dir = campaignRoot.resolve("images").resolve(category);
        Files.createDirectories(dir);
        Path dest = dir.resolve(entityId + "." + ext);
        Files.copy(sourceFile.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
        return campaignRoot.relativize(dest).toString().replace('\\', '/');
    }

    public static Image load(Path campaignRoot, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        Path abs = campaignRoot.resolve(relativePath).toAbsolutePath().normalize();
        long modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(abs).toMillis();
            size = Files.size(abs);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        synchronized (CACHE) {
            Cached cached = CACHE.get(abs);
            Image image = cached == null ? null : cached.image().get();
            if (image != null && cached.modified() == modified && cached.size() == size) return image;
        }
        try {
            Image image = new Image(abs.toUri().toString());
            if (image.isError()) return null;
            synchronized (CACHE) {
                CACHE.put(abs, new Cached(new SoftReference<>(image), modified, size));
            }
            return image;
        } catch (Exception e) {
            return null;
        }
    }

    public static Image loadOrPlaceholder(Path campaignRoot, String relativePath) {
        Image img = load(campaignRoot, relativePath);
        if (img != null) return img;
        return new Image("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "png";
    }
}
