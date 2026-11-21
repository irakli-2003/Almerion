package com.dnd.ui.components;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import java.util.concurrent.Executors;

final class CountdownTone {
    private static final int SAMPLE_RATE = 44100;
    private static final int FREQUENCY = 2048;
    private static final int LENGTH_MS = 75;
    private static final System.Logger LOG = System.getLogger(CountdownTone.class.getName());
    private static final java.util.concurrent.Executor AUDIO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "countdown-audio");
        thread.setDaemon(true);
        return thread;
    });

    private CountdownTone() { }

    static void play() {
        AUDIO.execute(() -> {
            Clip clip = Playback.CLIP;
            if (clip == null) return;
            clip.stop();
            clip.setFramePosition(0);
            clip.start();
        });
    }

    private static final class Playback {
        private static final Clip CLIP = open();
    }

    private static Clip open() {
        Clip clip = null;
        try {
            byte[] pcm = waveform();
            clip = AudioSystem.getClip();
            clip.open(new AudioFormat(SAMPLE_RATE, 16, 1, true, false), pcm, 0, pcm.length);
            return clip;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException error) {
            if (clip != null) clip.close();
            LOG.log(System.Logger.Level.WARNING, "Could not open countdown audio output.", error);
            return null;
        }
    }

    private static byte[] waveform() {
        int samples = SAMPLE_RATE * LENGTH_MS / 1000;
        double attack = SAMPLE_RATE * 0.003;
        double release = SAMPLE_RATE * 0.012;
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double envelope = Math.min(1.0, Math.min(i / attack, (samples - 1 - i) / release));
            int sample = (int) (32767 * 0.12 * envelope * Math.sin(2 * Math.PI * FREQUENCY * i / SAMPLE_RATE));
            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }
}
