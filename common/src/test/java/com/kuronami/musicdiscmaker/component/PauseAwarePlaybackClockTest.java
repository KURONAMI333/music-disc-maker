package com.kuronami.musicdiscmaker.component;

import java.util.concurrent.atomic.AtomicLong;
import com.kuronami.musicdiscmaker.event.BoomboxHeartbeat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PauseAwarePlaybackClockTest {
    @Test
    void repeatedGamePausesDoNotFinishAnAlbumTrackEarly() {
        final AtomicLong real = new AtomicLong(1_000_000L);
        final var clock = new PauseAwarePlaybackClock(real::get);
        final long start = clock.timeMs();
        real.addAndGet(60_000L);
        for (int i = 0; i < 6; i++) {
            clock.setPaused(true);
            real.addAndGet(20_000L);
            clock.setPaused(true); // Repeated paused server ticks must retain the original boundary.
            assertEquals(60_000L, clock.timeMs() - start);
            clock.setPaused(false);
        }
        assertFalse(PlaylistPlayback.finished(clock.timeMs() - start, 175_000L, false));
        real.addAndGet(115_000L);
        assertTrue(PlaylistPlayback.finished(clock.timeMs() - start, 175_000L, false));
    }

    @Test
    void slowServerTicksStillCountRealAudioTime() {
        final AtomicLong real = new AtomicLong(1_000_000L);
        final var clock = new PauseAwarePlaybackClock(real::get);
        final long start = clock.timeMs();
        real.addAndGet(175_000L); // No tick callbacks occur during this interval.
        assertEquals(175_000L, clock.timeMs() - start);
        assertTrue(PlaylistPlayback.finished(clock.timeMs() - start, 175_000L, false));
    }

    @Test
    void seekOriginAndNewSessionKeepTheirOwnOffsets() {
        final AtomicLong real = new AtomicLong(1_000_000L);
        final var clock = new PauseAwarePlaybackClock(real::get);
        clock.setPaused(true);
        real.addAndGet(120_000L);
        clock.setPaused(false);
        final long seekOrigin = clock.timeMs() - 30_000L;
        real.addAndGet(5_000L);
        assertEquals(35_000L, clock.timeMs() - seekOrigin);
        assertEquals(real.get(), new PauseAwarePlaybackClock(real::get).timeMs());
    }

    @Test
    void boomboxHeartbeatDoesNotAdvanceOrExpireWhileTheIntegratedGameIsPaused() {
        final AtomicLong real = new AtomicLong(1_000_000L);
        final var clock = new PauseAwarePlaybackClock(real::get);
        final long start = clock.timeMs();
        real.addAndGet(900L);
        clock.setPaused(true);
        real.addAndGet(60_000L);
        final long elapsedWhilePaused = clock.timeMs() - start;
        assertEquals(900L, elapsedWhilePaused);
        assertEquals(BoomboxHeartbeat.Action.IDLE,
                BoomboxHeartbeat.decide(true, false, elapsedWhilePaused, 1_000L, elapsedWhilePaused,
                        1_000L, 8_000L));
        clock.setPaused(false);
        real.addAndGet(100L);
        assertEquals(BoomboxHeartbeat.Action.IDLE,
                BoomboxHeartbeat.decide(true, false, clock.timeMs() - start, 1_000L, 1_000L,
                        1_000L, 8_000L));
        real.addAndGet(8_000L);
        assertEquals(BoomboxHeartbeat.Action.END,
                BoomboxHeartbeat.decide(true, false, clock.timeMs() - start, 1_000L, 1_000L,
                        1_000L, 8_000L));
    }
}
