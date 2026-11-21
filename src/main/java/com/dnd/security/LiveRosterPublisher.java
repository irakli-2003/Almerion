package com.dnd.security;

import com.dnd.data.CampaignPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class LiveRosterPublisher implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(LiveRosterPublisher.class.getName());
    private final ScheduledExecutorService worker;
    private byte[] published;
    private int failures;

    LiveRosterPublisher(Path root, Runnable push) {
        Path roster = new CampaignPaths(root).playersFile();
        worker = Executors.newSingleThreadScheduledExecutor(job -> {
            Thread thread = new Thread(job, "online-roster-publisher");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(() -> publishChanges(roster, push), 0, 750, TimeUnit.MILLISECONDS);
    }

    private void publishChanges(Path roster, Runnable push) {
        try {
            byte[] current = Files.notExists(roster) ? new byte[0] : Files.readAllBytes(roster);
            if (!Arrays.equals(current, published)) {
                push.run();
                published = current;
            }
            failures = 0;
        } catch (IOException | RuntimeException error) {
            if (failures++ % 20 == 0) LOG.log(System.Logger.Level.WARNING, "Could not publish live character updates; retrying.", error);
        }
    }

    @Override public void close() {
        worker.shutdownNow();
    }
}
