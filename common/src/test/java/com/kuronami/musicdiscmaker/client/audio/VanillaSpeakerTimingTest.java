package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VanillaSpeakerTimingTest {
    @Test
    void elapsedPlaybackTimeIsConvertedByThePinnedResourcePackPitch() {
        assertEquals(500L, VanillaSpeakerTiming.sourceOffsetMs(1_000L, 0.5F));
        assertEquals(1_000L, VanillaSpeakerTiming.sourceOffsetMs(1_000L, 1.0F));
        assertEquals(2_000L, VanillaSpeakerTiming.sourceOffsetMs(1_000L, 2.0F));
    }

    @Test
    void negativeAndOverflowingOffsetsStayBounded() {
        assertEquals(0L, VanillaSpeakerTiming.sourceOffsetMs(-1L, 2.0F));
        assertEquals(Long.MAX_VALUE, VanillaSpeakerTiming.sourceOffsetMs(Long.MAX_VALUE, 2.0F));
    }
}
