package com.kuronami.musicdiscmaker.component;

import java.util.function.LongSupplier;

/** Keeps real audio time during slow ticks, but stops during an explicit game pause. */
public final class PauseAwarePlaybackClock {
    private static final long EPOCH_MS = System.currentTimeMillis();
    private static final long ORIGIN_NS = System.nanoTime();
    private final LongSupplier time;
    private long pausedAt;
    private long pausedDuration;
    private boolean paused;

    public PauseAwarePlaybackClock() {
        this(PauseAwarePlaybackClock::realTimeMs);
    }

    PauseAwarePlaybackClock(LongSupplier time) {
        this.time = time;
    }

    public static long realTimeMs() {
        return EPOCH_MS + (System.nanoTime() - ORIGIN_NS) / 1_000_000L;
    }

    public synchronized void setPaused(boolean value) {
        if (paused == value) return;
        final long now = time.getAsLong();
        if (value) pausedAt = now;
        else pausedDuration += now - pausedAt;
        paused = value;
    }

    public synchronized long timeMs() {
        return (paused ? pausedAt : time.getAsLong()) - pausedDuration;
    }
}
