package com.kuronami.musicdiscmaker.client.audio;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SourcePlaybackOwnershipTest {
    private record PlotRoute(long position) {}
    private record WorldRoute(long position) {}

    @Test
    void sameCoordinatesOnDifferentRouteTypesTransferWithoutCancellingKeepAlive() {
        var ownership = new SourcePlaybackOwnership<Object>();
        var id = UUID.randomUUID();
        var fixedStops = new AtomicInteger();
        var plotStops = new AtomicInteger();
        var fixed = ownership.claim(id, 9, new WorldRoute(Long.MAX_VALUE), fixedStops::incrementAndGet);
        var plot = ownership.claim(id, 9, new PlotRoute(Long.MAX_VALUE), plotStops::incrementAndGet);
        assertEquals(1, fixedStops.get());
        assertFalse(ownership.isCurrent(fixed));
        for (int i = 0; i < 20; i++) {
            assertSame(plot, ownership.claim(id, 9, new PlotRoute(Long.MAX_VALUE), plotStops::incrementAndGet));
        }
        assertEquals(0, plotStops.get());
        assertFalse(ownership.stop(id, 9, new WorldRoute(Long.MAX_VALUE)));
        var returned = ownership.claim(id, 10, new WorldRoute(Long.MAX_VALUE), fixedStops::incrementAndGet);
        assertEquals(1, plotStops.get());
        assertFalse(ownership.isCurrent(plot));
        assertTrue(ownership.isCurrent(returned));
        assertNull(ownership.claim(id, 9, new PlotRoute(Long.MAX_VALUE), plotStops::incrementAndGet));
    }

    @Test
    void replacementAtSamePositionEvictsTheOldSourceAndItsStopCallback() {
        var ownership = new SourcePlaybackOwnership<String>();
        var oldId = UUID.randomUUID();
        var newId = UUID.randomUUID();
        var oldStops = new AtomicInteger();
        var newStops = new AtomicInteger();
        var old = ownership.claim(oldId, 80, "world:A", oldStops::incrementAndGet);
        var replacement = ownership.claim(newId, 1, "world:A", newStops::incrementAndGet);
        assertEquals(1, oldStops.get());
        assertFalse(ownership.isCurrent(old));
        assertFalse(ownership.stop(oldId, 81, "world:A"));
        assertEquals(0, newStops.get());
        assertTrue(ownership.isCurrent(replacement));
    }
    @Test
    void routeTransferStopsOnlyPreviousVoiceAndInvalidatesItsPendingLoad() {
        var ownership = new SourcePlaybackOwnership<String>();
        var id = UUID.randomUUID();
        var oldStops = new AtomicInteger();
        var movingStops = new AtomicInteger();
        var fixed = ownership.claim(id, 4, "world:A", oldStops::incrementAndGet);
        var moving = ownership.claim(id, 4, "create:12:local", movingStops::incrementAndGet);
        assertEquals(1, oldStops.get());
        assertFalse(ownership.isCurrent(fixed));
        assertTrue(ownership.isCurrent(moving));
        assertFalse(ownership.stop(id, 5, "world:A"));
        assertEquals(0, movingStops.get());
        var restored = ownership.claim(id, 5, "world:B", () -> {});
        assertEquals(1, movingStops.get());
        assertTrue(ownership.isCurrent(restored));
        assertNull(ownership.claim(id, 4, "create:12:local", () -> {}));
    }

    @Test
    void keepAliveRetainsLeaseButSameUrlAtNewGenerationDoesNot() {
        var ownership = new SourcePlaybackOwnership<String>();
        var id = UUID.randomUUID();
        var stops = new AtomicInteger();
        var first = ownership.claim(id, 1, "create:one", stops::incrementAndGet);
        for (int index = 0; index < 20; index++) {
            assertSame(first, ownership.claim(id, 1, "create:one", () -> fail("keep-alive replaced callback")));
        }
        assertEquals(0, stops.get());
        var repeatedTrack = ownership.claim(id, 2, "create:one", () -> {});
        assertNotSame(first, repeatedTrack);
        assertFalse(ownership.isCurrent(first));
        assertEquals(1, stops.get());
    }

    @Test
    void rangeReentryAllowsSameGenerationButOldGenerationsRemainRejected() {
        var ownership = new SourcePlaybackOwnership<String>();
        var id = UUID.randomUUID();
        var first = ownership.claim(id, 6, "world:A", () -> {});
        assertTrue(ownership.stop(id, 6, "world:A"));
        assertFalse(ownership.isCurrent(first));
        assertNull(ownership.claim(id, 5, "world:A", () -> {}));
        assertNotNull(ownership.claim(id, 6, "world:A", () -> {}));
    }

    @Test
    void worldExitInvalidatesLoadsEvenWhenNextWorldReusesTheSameIdentity() {
        var ownership = new SourcePlaybackOwnership<String>();
        var id = UUID.randomUUID();
        var old = ownership.claim(id, 0, "create:1", () -> {});
        ownership.clear();
        var next = ownership.claim(id, 0, "create:1", () -> {});
        assertFalse(ownership.isCurrent(old));
        assertTrue(ownership.isCurrent(next));
    }
}
