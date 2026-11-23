package com.dnd.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SyncProgressTest {
    @Test public void tracksWorkWithinATaskWithoutCarryingPercentIntoTheNextPhase() {
        var monitor = new SyncProgress();
        monitor.phase("Fetching from GitHub");
        assertEquals(-1, monitor.snapshot().fraction(), 0);
        monitor.beginTask("Receiving objects", 100);
        var started = monitor.snapshot();
        monitor.update(35);
        monitor.update(15);
        monitor.endTask();
        var completed = monitor.snapshot();
        assertEquals("Fetching from GitHub", completed.phase());
        assertEquals("Receiving objects", completed.task());
        assertEquals(0.5, completed.fraction(), 0);
        assertTrue(completed.lastActivityNanos() >= started.lastActivityNanos());

        monitor.phase("Creating restore point");
        assertEquals("", monitor.snapshot().task());
        assertEquals(-1, monitor.snapshot().fraction(), 0);
    }
}
