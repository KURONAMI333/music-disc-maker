package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;


class PlaybackConcurrencyCounterTest {
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
}
