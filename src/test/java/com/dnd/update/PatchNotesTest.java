package com.dnd.update;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class PatchNotesTest {
    @Test
    public void acknowledgesOnlyTheDisplayedVersionAcrossInstances() throws Exception {
        Path dir = Files.createTempDirectory("patch-notes").resolve("ack");
        PatchNotes notes = new PatchNotes(dir);
        assertTrue(notes.pending("1.4.0").orElseThrow().contains("Almerion 1.4.0"));
        notes.acknowledge("1.4.0");
        assertTrue(new PatchNotes(dir).pending("1.4.0").isEmpty());
        assertEquals("Next version", notes.pending("0.0.0-test").orElseThrow());
        notes.acknowledge("0.0.0-test");
        assertTrue(notes.pending("1.4.0").isEmpty());
        assertTrue(notes.pending("0.0.0-test").isEmpty());
        assertThrows(IOException.class, () -> notes.pending("9.9.9"));
    }

    @Test
    public void rejectsUnsafeVersionsAndReportsStorageFailures() throws Exception {
        Path file = Files.createTempFile("patch-notes", ".tmp");
        PatchNotes notes = new PatchNotes(file);
        assertThrows(IllegalArgumentException.class, () -> notes.pending("../settings"));
        assertThrows(IOException.class, () -> notes.acknowledge("1.4.0"));
    }
}
