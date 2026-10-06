package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.component.PlaybackCursor;

/** Smoothly advances the last server playback snapshot without resetting on each UI tick. */
final class CartPlaybackDisplayClock {
    private long elapsedMs;
    private long receivedAtMs;
    private long generation = Long.MIN_VALUE;
    private PlaybackCursor.State state;
    private boolean initialized;

    void observe(long snapshotElapsedMs, PlaybackCursor.State snapshotState,
            long snapshotGeneration, long receivedAtMillis) {
        final long normalizedElapsed = Math.max(0L, snapshotElapsedMs);
        if (!initialized || normalizedElapsed != elapsedMs || snapshotState != state
                || snapshotGeneration != generation) {
            elapsedMs = normalizedElapsed;
            state = snapshotState;
            generation = snapshotGeneration;
            receivedAtMs = receivedAtMillis;
            initialized = true;
        }
    }

    long displayElapsedMs(long nowMillis) {
        if (!initialized) {
            return 0L;
        }
        if (state != PlaybackCursor.State.PLAYING) {
            return elapsedMs;
        }
        final long delta = Math.max(0L, nowMillis - receivedAtMs);
        return delta > Long.MAX_VALUE - elapsedMs ? Long.MAX_VALUE : elapsedMs + delta;
    }
}
