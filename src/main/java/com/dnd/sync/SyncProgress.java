package com.dnd.sync;

import org.eclipse.jgit.lib.ProgressMonitor;

import java.util.concurrent.TimeUnit;

final class SyncProgress implements ProgressMonitor {
    private volatile CampaignSyncService.Progress snapshot;
    private String phase;
    private String task;
    private long completed;
    private int total;

    CampaignSyncService.Progress snapshot() {
        return snapshot;
    }

    synchronized void phase(String phase) {
        this.phase = phase;
        task = "";
        completed = 0;
        total = UNKNOWN;
        publish(System.nanoTime());
    }

    @Override public synchronized void beginTask(String title, int totalWork) {
        task = title;
        total = totalWork;
        completed = 0;
        publish(System.nanoTime());
    }

    @Override public synchronized void update(int work) {
        if (work <= 0) return;
        completed += work;
        long now = System.nanoTime();
        if (now - snapshot.lastActivityNanos() >= TimeUnit.MILLISECONDS.toNanos(200) || completed == total) {
            publish(now);
        }
    }

    @Override public synchronized void endTask() {
        publish(System.nanoTime());
    }

    private void publish(long now) {
        snapshot = new CampaignSyncService.Progress(phase, task, completed, total, now);
    }

    @Override public void start(int totalTasks) {}
    @Override public boolean isCancelled() { return false; }
    @Override public void showDuration(boolean enabled) {}
}
