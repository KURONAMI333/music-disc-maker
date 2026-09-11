package com.kuronami.musicdiscmaker.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MediaSequenceTest {

    private static final MediaSequence.Position A0 = new MediaSequence.Position(0, 0);
    private static final MediaSequence.Position B0 = new MediaSequence.Position(1, 0);
    private static final MediaSequence.Position B1 = new MediaSequence.Position(1, 1);
    private static final MediaSequence.Position A2 = new MediaSequence.Position(2, 0);
    private static final MediaSequence SEQUENCE = MediaSequence.of(List.of(A0, B0, B1, A2));

    @Test
    @DisplayName("盤内と盤間を0/0→1/0→1/1→2/0の順で送る")
    void advancesAcrossDiscAndTrackBoundaries() {
        assertEquals(B0, SEQUENCE.next(A0, false).orElseThrow());
        assertEquals(B1, SEQUENCE.next(B0, false).orElseThrow());
        assertEquals(A2, SEQUENCE.next(B1, false).orElseThrow());
    }

    @Test
    @DisplayName("前へは盤内と盤間の直前位置へ戻る")
    void movesBackwardAcrossDiscAndTrackBoundaries() {
        assertEquals(B1, SEQUENCE.previous(A2, false).orElseThrow());
        assertEquals(B0, SEQUENCE.previous(B1, false).orElseThrow());
        assertEquals(A0, SEQUENCE.previous(B0, false).orElseThrow());
    }

    @Test
    @DisplayName("末尾と先頭はrepeat時だけ全列をwrapする")
    void repeatWrapsWholeSequenceOnly() {
        assertTrue(SEQUENCE.next(A2, false).isEmpty());
        assertTrue(SEQUENCE.previous(A0, false).isEmpty());
        assertEquals(A0, SEQUENCE.next(A2, true).orElseThrow());
        assertEquals(A2, SEQUENCE.previous(A0, true).orElseThrow());
    }

    @Test
    @DisplayName("shuffleは現在位置を先頭に固定しseedごとに同じ全曲順を再生する")
    void shuffleAnchorsCurrentPositionAndIsDeterministic() {
        final MediaSequence shuffled = SEQUENCE.shuffled(B1, 849_271L);
        assertEquals(B1, shuffled.first().orElseThrow());
        assertEquals(SEQUENCE.size(), shuffled.size());
        assertTrue(shuffled.positions().containsAll(SEQUENCE.positions()));
        assertEquals(shuffled.positions(), SEQUENCE.shuffled(B1, 849_271L).positions());
        assertEquals(B1, shuffled.next(shuffled.previous(B1, true).orElseThrow(), true).orElseThrow());
    }

    @Test
    @DisplayName("消えたshuffle anchorは物理先頭へ安全に戻る")
    void shuffleFallsBackToPhysicalHeadWhenAnchorIsMissing() {
        final MediaSequence shuffled = SEQUENCE.shuffled(new MediaSequence.Position(99, 99), 7L);
        assertEquals(A0, shuffled.first().orElseThrow());
        assertEquals(SEQUENCE.size(), shuffled.size());
        assertTrue(shuffled.positions().containsAll(SEQUENCE.positions()));
    }

    @Test
    @DisplayName("空列と壊れた保存位置は例外にせず決定的に復帰する")
    void emptyAndMissingPositionsAreSafe() {
        final MediaSequence.Position missing = new MediaSequence.Position(9, 9);
        assertTrue(MediaSequence.empty().next(missing, true).isEmpty());
        assertEquals(A0, SEQUENCE.next(missing, false).orElseThrow());
        assertEquals(A2, SEQUENCE.previous(missing, false).orElseThrow());
    }

    @Test
    @DisplayName("LIVEと尺不明は自動送りだけを止める")
    void liveAndUnknownDurationBlockAutomaticAdvance() {
        assertFalse(MediaSequence.allowsAutomaticAdvance(120_000L, true));
        assertFalse(MediaSequence.allowsAutomaticAdvance(0L, false));
        assertFalse(MediaSequence.allowsAutomaticAdvance(-1L, false));
        assertTrue(MediaSequence.allowsAutomaticAdvance(1L, false));
    }

    @Test
    @DisplayName("負の盤位置と曲位置を拒否する")
    void rejectsNegativePosition() {
        assertThrows(IllegalArgumentException.class, () -> new MediaSequence.Position(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new MediaSequence.Position(0, -1));
    }
}
