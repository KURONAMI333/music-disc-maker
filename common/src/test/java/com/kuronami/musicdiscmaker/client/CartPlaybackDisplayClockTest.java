package com.kuronami.musicdiscmaker.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.PlaybackCursor;

class CartPlaybackDisplayClockTest {
    @Test
    void repeatedObservationOfSameSnapshotDoesNotResetInterpolation() {
        final CartPlaybackDisplayClock clock = new CartPlaybackDisplayClock();
        clock.observe(2_000L, PlaybackCursor.State.PLAYING, 4L, 100L);

        assertEquals(2_050L, clock.displayElapsedMs(150L));
        clock.observe(2_000L, PlaybackCursor.State.PLAYING, 4L, 150L);
        assertEquals(2_100L, clock.displayElapsedMs(200L));
    }

    @Test
    void newerServerSnapshotReanchorsWithoutLosingPlaybackTime() {
        final CartPlaybackDisplayClock clock = new CartPlaybackDisplayClock();
        clock.observe(2_000L, PlaybackCursor.State.PLAYING, 4L, 100L);
        clock.observe(2_900L, PlaybackCursor.State.PLAYING, 4L, 1_000L);

        assertEquals(3_400L, clock.displayElapsedMs(1_500L));
    }

    @Test
    void pausedSnapshotStopsAndNewGenerationResumesFromItsAnchor() {
        final CartPlaybackDisplayClock clock = new CartPlaybackDisplayClock();
        clock.observe(2_500L, PlaybackCursor.State.PAUSED, 5L, 100L);
        assertEquals(2_500L, clock.displayElapsedMs(10_000L));

        clock.observe(2_500L, PlaybackCursor.State.PLAYING, 6L, 10_000L);
        assertEquals(2_750L, clock.displayElapsedMs(10_250L));
    }
}
