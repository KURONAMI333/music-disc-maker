package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;


class PlaybackConcurrencyCounterTest {
    private static final class Voice implements PlaybackVoice {
        private final int count;
        private boolean stopped;

        Voice(int count) {
            this.count = count;
        }

        @Override public int voiceCount() { return count; }
        @Override public boolean isVoiceStopped() { return stopped; }
        @Override public void setDirectional(boolean value) {}
        @Override public void setRangeBlocks(int value) {}
        @Override public void setVolumePercent(int value) {}
        @Override public void stopAndRelease() { stopped = true; }
    }

    @Test
    void resourceVoicesShareTheBudgetAndReleaseItWhenStopped() {
        final PlaybackConcurrency budget = new PlaybackConcurrency();
        final AtomicInteger vanillaVoices = new AtomicInteger(2);
        final IntSupplier owner = vanillaVoices::get;
        budget.registerCounter(owner);
        budget.registerCounter(owner);
        budget.registerCounter(() -> 3);
        assertEquals(5, budget.sweepAll());
        vanillaVoices.set(0);
        assertEquals(3, budget.sweepAll());
    }

    @Test
    void expandedAutoLimitAdmitsTheSixtyFourthSharedVoiceAndRecoversAfterRelease() {
        final int automaticLimit = StreamingChannelPool.automaticMdmLimit(72);
        final PlaybackConcurrency budget = new PlaybackConcurrency();
        budget.registerCounter(() -> 60);
        final AtomicInteger otherOwners = new AtomicInteger(3);
        budget.registerCounter(otherOwners::get);
        assertEquals(63, budget.sweepAll());
        assertTrue(budget.sweepAll() < automaticLimit, "63 voices must admit the 64th");

        otherOwners.incrementAndGet();
        assertEquals(64, budget.sweepAll());
        assertFalse(budget.sweepAll() < automaticLimit, "64 voices must block a new one");

        otherOwners.decrementAndGet();
        assertEquals(63, budget.sweepAll());
        assertTrue(budget.sweepAll() < automaticLimit, "releasing one voice must restore capacity");
    }

    @Test
    void goldenCartVanillaVoicesJoinTheSharedBudgetAndStoppedVoicesAreReleased() {
        final int automaticLimit = StreamingChannelPool.automaticMdmLimit(72);
        final Map<UUID, GoldenCartClientPlayback.Playing> active = new HashMap<>();
        final Voice vanillaA = new Voice(1);
        final Voice vanillaB = new Voice(1);
        final Voice customCart = new Voice(1);
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final UUID custom = UUID.randomUUID();
        active.put(a, new GoldenCartClientPlayback.Playing("vanilla:a", null, vanillaA, true));
        active.put(b, new GoldenCartClientPlayback.Playing("vanilla:b", null, vanillaB, true));
        active.put(custom, new GoldenCartClientPlayback.Playing("https://example.invalid/track", null, customCart, false));

        final AtomicInteger otherOwners = new AtomicInteger(62);
        final PlaybackConcurrency budget = new PlaybackConcurrency();
        budget.registerCounter(otherOwners::get);
        budget.registerCounter(() -> GoldenCartClientPlayback.sweepVanilla(active));
        assertEquals(64, budget.sweepAll(), "two vanilla cart voices must be counted; custom cart belongs to SESSIONS");
        assertFalse(budget.sweepAll() < automaticLimit, "64 shared voices must block another start");

        vanillaA.stopped = true;
        assertEquals(63, budget.sweepAll(), "stopped vanilla cart voice must release one channel");
        assertFalse(active.containsKey(a));
        assertTrue(active.containsKey(custom), "vanilla counter must not remove custom cart state");
        assertTrue(budget.sweepAll() < automaticLimit, "released channel must restore admission");
    }

    @Test
    void goldenCartVanillaProductBoundaryStartsTheEngineOnceBeforePublishingOwnership() {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger rejectedCleanup = new AtomicInteger();
        final AtomicInteger acceptedOwnership = new AtomicInteger();
        final SoundEngineAcceptance.Engine engine = new SoundEngineAcceptance.Engine() {
            @Override public boolean playAndConfirm() { starts.incrementAndGet(); return true; }
            @Override public boolean mutedOut() { return false; }
        };

        assertTrue(GoldenCartClientPlayback.startVanillaCartVoice(engine,
                rejectedCleanup::incrementAndGet, ignored -> {}, acceptedOwnership::incrementAndGet));
        assertEquals(1, starts.get(), "one vanilla cart acceptance must issue one SoundManager.play");
        assertEquals(1, acceptedOwnership.get(), "ownership publishes once after acceptance");
        assertEquals(0, rejectedCleanup.get());
    }
}
