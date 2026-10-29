package com.dnd.update;

import com.dnd.boot.Launcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.Assert.*;

public class UpdateCheckerTest {

    private static Path jar(Path dir, String name, String version, String classPath) throws Exception {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (version != null) m.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        if (classPath != null) m.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream ignored = new JarOutputStream(out, m)) {
            // manifest only
        }
        return jar;
    }

    @Test
    public void comparesVersionsNumerically() {
        assertTrue(Launcher.compareVersions("1.10.0", "1.9.3") > 0);
        assertTrue(Launcher.compareVersions("v1.1", "1.0.9") > 0);
        assertEquals(0, Launcher.compareVersions("1.1", "1.1.0"));
        assertTrue(Launcher.compareVersions("1.0.0", "1.0.1-SNAPSHOT") < 0);
        assertTrue(Launcher.compareVersions(null, "1.0") < 0);
    }

    @Test
    public void parsesTheLatestReleaseAssets() throws Exception {
        String json = "{\"tag_name\":\"v1.2.0\",\"html_url\":\"https://x/release\",\"assets\":["
            + "{\"name\":\"almerion.jar\",\"browser_download_url\":\"https://x/jar\"},"
            + "{\"name\":\"Almerion-1.2.0.exe\",\"browser_download_url\":\"https://x/exe\"}]}";
        UpdateChecker.Release r = UpdateChecker.parse(new ObjectMapper().readTree(json));
        assertEquals("1.2.0", r.version());
        assertEquals("https://x/jar", r.jarUrl());
        assertEquals("https://x/exe", r.installerUrl());
        assertNull(UpdateChecker.parse(new ObjectMapper().readTree("{\"tag_name\":\"v2\",\"draft\":true}")));
    }

    @Test
    public void oldReleaseJarNameIsNotTakenAsAnUpdate() throws Exception {
        String json = "{\"tag_name\":\"v1.2.0\",\"assets\":["
            + "{\"name\":\"dnd-campaign-manager.jar\",\"browser_download_url\":\"https://x/jar\"}]}";
        assertNull(UpdateChecker.parse(new ObjectMapper().readTree(json)).jarUrl());
    }

    @Test
    public void legacyDataFolderIsRenamedOnce() throws Exception {
        Path base = Files.createTempDirectory("appdata");
        Path legacy = Files.createDirectories(base.resolve("DnD Campaign Manager").resolve("data"));
        Files.writeString(legacy.resolve("x.txt"), "kept");
        Path current = base.resolve("Almerion");
        assertEquals(current, Launcher.migrateLegacy(base.resolve("DnD Campaign Manager"), current));
        assertEquals("kept", Files.readString(current.resolve("data").resolve("x.txt")));
        assertFalse(Files.exists(base.resolve("DnD Campaign Manager")));
        Files.createDirectories(base.resolve("DnD Campaign Manager"));
        assertEquals(current, Launcher.migrateLegacy(base.resolve("DnD Campaign Manager"), current));
        assertTrue(Files.isDirectory(base.resolve("DnD Campaign Manager")));
    }

    @Test
    public void launcherOnlyPicksANewerUpdateWhoseLibrariesAreInstalled() throws Exception {
        Path install = Files.createTempDirectory("install");
        Path updates = Files.createTempDirectory("updates");
        Files.writeString(install.resolve("lib-a-1.jar"), "x");
        Path installed = jar(install, "almerion-1.1.0.jar", "1.1.0", "lib-a-1.jar");

        Path older = jar(updates, "older.jar", "1.0.0", "lib-a-1.jar");
        Path newer = jar(updates, "newer.jar", "1.2.0", "lib-a-1.jar");
        Path newLibs = jar(updates, "newlibs.jar", "1.3.0", "lib-a-1.jar lib-b-2.jar");

        assertFalse(Launcher.shouldUse(older, installed));
        assertTrue(Launcher.shouldUse(newer, installed));
        assertFalse(Launcher.shouldUse(newLibs, installed));
        assertEquals(List.of("lib-b-2.jar"), Launcher.missingLibraries(newLibs, install));
        assertFalse(Launcher.shouldUse(updates.resolve("absent.jar"), installed));
    }

    @Test
    public void installKeepsTheOldUpdateWhenLibrariesAreMissing() throws Exception {
        Path install = Files.createTempDirectory("install2");
        Path updates = Files.createTempDirectory("updates2");
        Path target = updates.resolve("almerion-1.2.0.jar");

        Path ok = jar(updates, "ok.part", "1.2.0", "");
        assertEquals(UpdateChecker.Outcome.READY_AFTER_RESTART, UpdateChecker.install(ok, updates, install));
        assertTrue(Files.exists(target));
        assertFalse(Files.exists(ok));

        Path needsLibs = jar(updates, "libs.part", "1.3.0", "missing.jar");
        assertEquals(UpdateChecker.Outcome.NEEDS_INSTALLER, UpdateChecker.install(needsLibs, updates, install));
        assertEquals("1.2.0", Launcher.versionOf(target));
        assertFalse(Files.exists(needsLibs));
        assertFalse(Files.exists(updates.resolve("almerion-1.3.0.jar")));
    }

    @Test
    public void newUpdateNeverOverwritesTheJarInUse() throws Exception {
        Path install = Files.createTempDirectory("install3");
        Path updates = Files.createTempDirectory("updates3");
        Path installed = jar(install, "almerion-1.2.0.jar", "1.2.0", "");
        // The update the app is running from right now (Windows keeps it locked).
        Path running = jar(updates, Launcher.UPDATE_JAR, "1.2.1", "");
        try (java.util.jar.JarFile locked = new java.util.jar.JarFile(running.toFile())) {
            Path downloaded = jar(updates, "almerion.jar.part", "1.2.3", "");
            assertEquals(UpdateChecker.Outcome.READY_AFTER_RESTART, UpdateChecker.install(downloaded, updates, install));
            assertEquals("1.2.1", Launcher.versionOf(running));
        }
        Path newest = Launcher.newestUpdate(updates, installed);
        assertEquals(updates.resolve("almerion-1.2.3.jar"), newest);

        Launcher.removeOldUpdates(updates, newest);
        assertFalse(Files.exists(running));
        assertTrue(Files.exists(newest));
    }

    @Test
    public void installedVersionWinsOverOlderDownloads() throws Exception {
        Path install = Files.createTempDirectory("install4");
        Path updates = Files.createTempDirectory("updates4");
        Path installed = jar(install, "almerion-1.3.0.jar", "1.3.0", "");
        jar(updates, Launcher.UPDATE_JAR, "1.2.1", "");
        jar(updates, "almerion-1.2.3.jar", "1.2.3", "");
        assertNull(Launcher.newestUpdate(updates, installed));
        assertEquals("almerion-1.2.3.jar", Launcher.updateJarName("1.2.3"));
    }
}
