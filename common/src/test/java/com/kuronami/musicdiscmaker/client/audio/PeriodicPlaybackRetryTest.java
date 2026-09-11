package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PeriodicPlaybackRetryTest {
    @Test
    void oneSecondAnnouncementsBackOffWithoutGivingUpOrRepeatingTheNotice() {
        var time = new AtomicLong();
        var retries = new LivePlaybackRegistry<String>(time::get);
        String token = "source:1:custom:track";
        int attempts = 0;
        int notices = 0;
        for (int second = 0; second <= 195; second++) {
            time.set(second * 1000L);
            if (retries.request("actor", token, 16, 100, false) != LivePlaybackRegistry.Decision.LOAD) continue;
            attempts++;
            retries.loadFinished("actor", token);
            if (retries.loadFailed("actor", token, PlaybackFailure.streamUnavailable())) notices++;
        }
        // Attempts at 0, 5, 15, 35, 75, 135, 195 seconds; recovery is still possible.
        assertEquals(7, attempts);
        assertEquals(1, notices);
    }

    @Test
    void newGenerationAndExplicitStopBypassPreviousFailureWait() {
        var retries = new LivePlaybackRegistry<String>(() -> 0L);
        String old = "source:1:custom:track";
        String next = "source:2:custom:track";
        assertEquals(LivePlaybackRegistry.Decision.LOAD, retries.request("actor", old, 16, 100, false));
        retries.loadFinished("actor", old);
        retries.loadFailed("actor", old, PlaybackFailure.streamUnavailable());
        assertEquals(LivePlaybackRegistry.Decision.SKIP, retries.request("actor", old, 16, 100, false));
        assertEquals(LivePlaybackRegistry.Decision.LOAD, retries.request("actor", next, 16, 100, false));
        assertEquals(LivePlaybackRegistry.Decision.SKIP, retries.request("actor", next, 32, 70, true));
        retries.loadFinished("actor", next);
        retries.loadFailed("actor", next, PlaybackFailure.streamUnavailable());
        retries.stop("actor");
        assertEquals(LivePlaybackRegistry.Decision.LOAD, retries.request("actor", next, 16, 100, false));
    }
}
