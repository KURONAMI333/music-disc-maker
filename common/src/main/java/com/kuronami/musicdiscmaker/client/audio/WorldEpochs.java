package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.atomic.AtomicLong;

/** Captures the outgoing world's epoch while atomically making the next epoch current. */
final class WorldEpochs {
    private WorldEpochs() {}

    static long captureAndAdvance(AtomicLong current) {
        return current.getAndIncrement();
    }
}
