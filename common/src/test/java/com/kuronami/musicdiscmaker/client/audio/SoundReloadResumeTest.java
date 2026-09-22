package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SoundReloadResumeTest {
    @Test
    void multiplayerResumeAdvancesByElapsedRealPlaybackTime() {
        assertEquals(17_500L, SoundReloadResume.offset(12_000L, 100_000L, 105_500L, 60_000L, false));
    }

    @Test
    void pausedIntegratedClockDoesNotAdvanceTheTrack() {
        assertEquals(12_000L, SoundReloadResume.offset(12_000L, 100_000L, 100_000L, 60_000L, false));
    }

    @Test
    void radioReopensFromItsLiveEndpointInsteadOfSeeking() {
        assertEquals(0L, SoundReloadResume.offset(12_000L, 100_000L, 105_500L, 0L, true));
    }

    @Test
    void completedFiniteTrackIsNotResurrectedByReload() {
        assertEquals(-1L, SoundReloadResume.offset(58_000L, 100_000L, 103_000L, 60_000L, false));
    }

    @Test
    void offsetAdditionSaturatesInsteadOfWrappingNegative() {
        assertEquals(-1L, SoundReloadResume.offset(Long.MAX_VALUE - 2L, 0L, 10L,
                Long.MAX_VALUE, false));
    }
}
