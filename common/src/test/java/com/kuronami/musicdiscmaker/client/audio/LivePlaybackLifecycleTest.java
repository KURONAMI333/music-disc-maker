package com.kuronami.musicdiscmaker.client.audio;

//? if >=1.21 {
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LivePlaybackLifecycleTest {
    static final class Voice implements PlaybackVoice {
        int stops;
        boolean fail;
        public boolean isVoiceStopped() { return stops > 0; }
        public void setDirectional(boolean value) { }
        public void setRangeBlocks(int value) { }
        public void setVolumePercent(int value) { }
        public void stopAndRelease() {
            stops++;
            if (fail) throw new IllegalStateException("release failed");
        }
    }

    @Test
    void concurrencyCountsOnlyActiveVoicesAndDropsFinishedVoices() {
        var slots = new LivePlaybackRegistry<String>();
        slots.request("pending", "A", 16, 100, true);
        assertEquals(0, slots.countPlaying());
        var voice = new Voice();
        slots.request("playing", "B", 16, 100, true);
        slots.install("playing", "B", voice);
        assertEquals(1, slots.countPlaying());
        voice.stopAndRelease();
        assertEquals(0, slots.countPlaying());
        assertNull(slots.activeUrl("playing"));
    }

    @Test
    void stopCancelsPendingInstallationAndAllowsSameUrlToRestart() {
        var slots = new LivePlaybackRegistry<String>();
        assertEquals(LivePlaybackRegistry.Decision.LOAD, slots.request("source", "A", 16, 100, true));
        slots.stop("source");
        assertFalse(slots.install("source", "A", new Voice()));
        assertEquals(LivePlaybackRegistry.Decision.LOAD, slots.request("source", "A", 16, 100, true));
        var voice = new Voice();
        assertTrue(slots.install("source", "A", voice));
        slots.stop("source");
        slots.stop("source");
        assertEquals(1, voice.stops);
        assertNull(slots.activeUrl("source"));
    }

    @Test
    void logoutClearsRetryAndPendingStateAndReleasesEveryVoiceEvenAfterFailure() {
        var slots = new LivePlaybackRegistry<String>(() -> 0L);
        var first = new Voice();
        first.fail = true;
        var second = new Voice();
        slots.request("first", "A", 16, 100, true);
        slots.install("first", "A", first);
        slots.request("second", "B", 16, 100, true);
        slots.install("second", "B", second);
        slots.request("failed", "C", 16, 100, true);
        slots.loadFinished("failed", "C");
        slots.loadFailed("failed", "C", PlaybackFailure.streamUnavailable());
        slots.request("pending", "D", 16, 100, true);
        assertThrows(IllegalStateException.class, slots::stopAll);
        assertEquals(1, first.stops);
        assertEquals(1, second.stops);
        assertFalse(slots.install("pending", "D", new Voice()));
        assertEquals(LivePlaybackRegistry.Decision.LOAD, slots.request("failed", "C", 16, 100, true));
        assertEquals(LivePlaybackRegistry.Decision.LOAD, slots.request("pending", "D", 16, 100, true));
        slots.stopAll();
        assertEquals(1, first.stops);
    }
}
//?}
