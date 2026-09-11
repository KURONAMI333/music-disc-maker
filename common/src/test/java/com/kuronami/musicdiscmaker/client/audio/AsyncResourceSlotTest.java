package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class AsyncResourceSlotTest {
    @Test
    void releaseAfterInstallClosesTheInstalledResourceOnce() {
        final AtomicInteger closes = new AtomicInteger();
        final AsyncResourceSlot<Object> slot = new AsyncResourceSlot<>(ignored -> closes.incrementAndGet());
        final Object resource = new Object();

        assertTrue(slot.install(resource));
        assertSame(resource, slot.current());
        slot.release();
        slot.release();

        assertTrue(slot.isReleased());
        assertNull(slot.current());
        assertEquals(1, closes.get());
    }

    @Test
    void installAfterReleaseClosesTheLateResourceOnce() {
        final AtomicInteger closes = new AtomicInteger();
        final AsyncResourceSlot<Object> slot = new AsyncResourceSlot<>(ignored -> closes.incrementAndGet());

        slot.release();
        assertFalse(slot.install(new Object()));

        assertTrue(slot.isReleased());
        assertNull(slot.current());
        assertEquals(1, closes.get());
    }

    @Test
    void secondInstallIsClosedWithoutReplacingTheOwnedResource() {
        final AtomicInteger closes = new AtomicInteger();
        final AsyncResourceSlot<Object> slot = new AsyncResourceSlot<>(ignored -> closes.incrementAndGet());
        final Object first = new Object();

        assertTrue(slot.install(first));
        assertFalse(slot.install(new Object()));
        assertSame(first, slot.current());
        assertEquals(1, closes.get());

        slot.release();
        assertEquals(2, closes.get());
    }
}
