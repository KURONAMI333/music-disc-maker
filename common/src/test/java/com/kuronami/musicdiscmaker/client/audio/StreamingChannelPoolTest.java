package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StreamingChannelPoolTest {

    @Test
    void normalDeviceProvidesSixtyFourMdmSourcesAndBothReserves() {
        final StreamingChannelPool.Allocation allocation = StreamingChannelPool.allocate(256);

        assertEquals(184, allocation.staticLimit());
        assertEquals(72, allocation.streamingLimit());
        assertEquals(64, StreamingChannelPool.automaticMdmLimit(allocation.streamingLimit()));
    }

    @Test
    void eightySourceDeviceStillProvidesSixtyFourMdmSources() {
        assertEquals(new StreamingChannelPool.Allocation(8, 72), StreamingChannelPool.allocate(80));
    }

    @Test
    void lowSourceDeviceKeepsStaticReserveAndReducesAutoLimit() {
        final StreamingChannelPool.Allocation allocation = StreamingChannelPool.allocate(30);

        assertEquals(new StreamingChannelPool.Allocation(8, 22), allocation);
        assertEquals(14, StreamingChannelPool.automaticMdmLimit(allocation.streamingLimit()));
    }

    @Test
    void reportedSourceCountIsNeverExceededWhenItIsBelowTheNormalMinimum() {
        assertEquals(new StreamingChannelPool.Allocation(6, 2), StreamingChannelPool.allocate(8));
        assertEquals(new StreamingChannelPool.Allocation(0, 1), StreamingChannelPool.allocate(1));
        assertEquals(new StreamingChannelPool.Allocation(0, 0), StreamingChannelPool.allocate(0));
        assertEquals(new StreamingChannelPool.Allocation(0, 0), StreamingChannelPool.allocate(Integer.MIN_VALUE));
        assertEquals(1, StreamingChannelPool.automaticMdmLimit(2));
    }

    @Test
    void existingPoolChangesAreNeverReversed() {
        final StreamingChannelPool.Allocation allocation = StreamingChannelPool.allocate(256);

        assertEquals(96, StreamingChannelPool.streamingLimit(96, allocation, 156));
        assertEquals(72, StreamingChannelPool.streamingLimit(8, allocation, 72));
        assertEquals(100, StreamingChannelPool.staticLimit(100, allocation));
        assertEquals(184, StreamingChannelPool.staticLimit(248, allocation));
        assertEquals(72, StreamingChannelPool.streamingLimit(96, allocation, 72),
                "a foreign streaming increase cannot exceed the sources left after static allocation");
    }

    @Test
    void autoLimitKeepsEightStreamingSlotsAndCapsAtSixtyFour() {
        assertEquals(64, StreamingChannelPool.automaticMdmLimit(100));
        assertEquals(64, StreamingChannelPool.automaticMdmLimit(72));
        assertEquals(14, StreamingChannelPool.automaticMdmLimit(22));
        assertEquals(1, StreamingChannelPool.automaticMdmLimit(8));
        assertEquals(1, StreamingChannelPool.automaticMdmLimit(0));
    }

    @Test
    void configuredLimitIsCappedByTheActualPool() {
        assertEquals(14, StreamingChannelPool.effectiveMdmLimit(64, 22));
        assertEquals(32, StreamingChannelPool.effectiveMdmLimit(32, 72));
        assertEquals(64, StreamingChannelPool.effectiveMdmLimit(0, 72));
        assertEquals(1, StreamingChannelPool.effectiveMdmLimit(64, 0));
    }

    @Test
    void actualLimitComesFromMinecraftPoolDebugGetter() {
        assertEquals(72, StreamingChannelPool.actualStreamingLimit("Sounds: 3/184 + 64/72"));
        assertEquals(96, StreamingChannelPool.actualStreamingLimit("Sounds: 0/160 + 0/96"));
        assertEquals(0, StreamingChannelPool.actualStreamingLimit("Sounds: 0/0 + 0/0"));
    }

    @Test
    void malformedUninitializedAndOverflowDebugValuesDoNotFallBackToARecalculation() {
        assertEquals(0, StreamingChannelPool.actualStreamingLimit(null));
        assertEquals(0, StreamingChannelPool.actualStreamingLimit(""));
        assertEquals(0, StreamingChannelPool.actualStreamingLimit("Sounds: 0/184 + 0/not-a-number"));
        assertEquals(0, StreamingChannelPool.actualStreamingLimit("Localized: 0/184 + 0/72"));
        assertEquals(0, StreamingChannelPool.actualStreamingLimit("Sounds: 0/184 + 0/2147483648"));
        assertEquals(0, StreamingChannelPool.actualStreamingLimit("Sounds: 0/184 + 0/-1"));
    }
}
