package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kuronami.musicdiscmaker.speaker.SpeakerTransition;

import org.junit.jupiter.api.Test;

class MultiSpeakerAnchorTest {

    @Test
    void payloadUpdateInTheSameGameTickDoesNotAdvanceSelectionTwice() {
        final long tick = 120L;
        // Set更新は payload を差し替えるが、selectionTick が同じなら transition.advance は翌tickまで待つ。
        assertFalse(MultiSpeakerAnchor.shouldAdvanceSelection(tick, tick));
        assertTrue(MultiSpeakerAnchor.shouldAdvanceSelection(tick, tick + 1L));
    }

    @Test
    void transitionRangeWinsOverTheNewPayloadRangeUntilTheFrameSettles() {
        final SpeakerTransition.Frame transition =
                new SpeakerTransition.Frame(0.0D, 0.0D, 0.0D, 10.0D, 100, 0.5D, "wide");
        assertEquals(10, MultiSpeakerAnchor.rangeForFrame(transition, 2));
        assertEquals(2, MultiSpeakerAnchor.rangeForFrame(null, 2));
    }
}
