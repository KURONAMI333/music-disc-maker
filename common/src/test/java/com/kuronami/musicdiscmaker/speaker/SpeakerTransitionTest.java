package com.kuronami.musicdiscmaker.speaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpeakerTransitionTest {

    @Test
    void crossingTheOverlapKeepsEffectiveGainPositiveAndSettlesOnTheOtherAnchor() {
        final SpeakerTransition transition = new SpeakerTransition();
        final List<SpeakerSelection.Candidate> candidates = List.of(
                candidate("left", 0.0D, 10.0D, 80), candidate("right", 8.0D, 10.0D, 40));

        final SpeakerTransition.Frame left = transition.advance(listener(2.0D), candidates, true);
        assertEquals("left", left.selectedId());
        final SpeakerTransition.Frame firstRight = transition.advance(listener(6.0D), candidates, true);
        assertEquals("right", firstRight.selectedId());
        assertEquals(320.0D / 9.0D, effectiveGain(firstRight, listener(6.0D)), 1.0E-9D,
                "補間位置の距離減衰を二重に掛けている");
        assertTrue(firstRight.x() > left.x() && firstRight.x() < 8.0D,
                "音源座標を1tickで次のanchorへ飛ばしている");

        SpeakerTransition.Frame settled = firstRight;
        for (int i = 1; i < SpeakerTransition.TRANSITION_TICKS; i++) {
            settled = transition.advance(listener(6.0D), candidates, true);
            assertEquals(320.0D / 9.0D, effectiveGain(settled, listener(6.0D)), 1.0E-9D,
                    "左右anchorの間で実効gainに谷を作っている");
        }
        assertEquals(8.0D, settled.x(), 1.0E-9D);
        assertEquals(40, settled.volumePercent());
        assertEquals(1.0D, settled.gainMultiplier(), 1.0E-9D);

        final SpeakerTransition.Frame firstLeft = transition.advance(listener(2.0D), candidates, true);
        assertEquals("left", firstLeft.selectedId());
        assertTrue(firstLeft.x() > 0.0D && firstLeft.x() < settled.x(),
                "逆方向の切替で座標を1tickで戻している");
    }

    @Test
    void noCandidateFadesWithoutKeepingAFullVolumeRemoteAnchor() {
        final SpeakerTransition transition = new SpeakerTransition();
        final List<SpeakerSelection.Candidate> candidates = List.of(candidate("only", 0.0D, 4.0D, 70));
        transition.advance(listener(0.0D), candidates, false);

        final SpeakerTransition.Frame firstOutside = transition.advance(listener(20.0D), candidates, false);
        assertTrue(firstOutside.gainMultiplier() > 0.0D && firstOutside.gainMultiplier() < 1.0D,
                "候補なしを即時0または全開のままにしている");
        SpeakerTransition.Frame silent = firstOutside;
        for (int i = 1; i < SpeakerTransition.TRANSITION_TICKS; i++) {
            silent = transition.advance(listener(20.0D), candidates, false);
        }
        assertEquals(0.0D, silent.gainMultiplier(), 1.0E-9D);
        assertEquals(0, transition.advance(listener(20.0D), candidates, false).volumePercent(),
                "fade後も遠方anchorの設定音量を保持している");
    }

    @Test
    void mutedCandidateUsesTheSameShortFadeInDirectionalMode() {
        final SpeakerTransition transition = new SpeakerTransition();
        transition.advance(listener(0.0D), List.of(candidate("only", 0.0D, 4.0D, 70)), true);
        final SpeakerSelection.Candidate muted =
                new SpeakerSelection.Candidate("only", 0.0D, 0.0D, 0.0D, 4.0D, 70, true);

        final SpeakerTransition.Frame firstMuted = transition.advance(listener(0.0D), List.of(muted), true);
        assertTrue(firstMuted.gainMultiplier() > 0.0D && firstMuted.gainMultiplier() < 1.0D,
                "positional候補消滅を即時0へ落としている");
        SpeakerTransition.Frame silent = firstMuted;
        for (int i = 1; i < SpeakerTransition.TRANSITION_TICKS; i++) {
            silent = transition.advance(listener(0.0D), List.of(muted), true);
        }
        assertEquals(0.0D, silent.gainMultiplier(), 1.0E-9D);
    }

    @Test
    void endpointUsesTheOriginalCandidateVolume() {
        final SpeakerTransition transition = new SpeakerTransition();
        final List<SpeakerSelection.Candidate> candidates = List.of(
                candidate("left", 0.0D, 10.0D, 80), candidate("right", 8.0D, 10.0D, 37));
        transition.advance(listener(1.0D), candidates, true);
        SpeakerTransition.Frame frame = null;
        for (int i = 0; i < SpeakerTransition.TRANSITION_TICKS; i++) {
            frame = transition.advance(listener(7.0D), candidates, true);
        }
        assertEquals(37, frame.volumePercent());
        assertEquals(1.0D, frame.gainMultiplier(), 1.0E-9D);
    }

    @Test
    void nearReferenceDistanceUsesOpenAlGainThroughEndpointAndReversal() {
        final SpeakerTransition transition = new SpeakerTransition();
        final List<SpeakerSelection.Candidate> candidates = List.of(
                candidate("far", 2.0D, 10.0D, 80), candidate("near", 0.0D, 10.0D, 40));
        transition.advance(listener(2.0D), candidates, true);

        final double fromAtListener = openAlLinearGain(2.0D, 10.0D) * 80.0D;
        final double toAtListener = 40.0D; // distance 0 is inside OpenAL's reference-distance plateau.
        SpeakerTransition.Frame frame = null;
        for (int step = 1; step <= SpeakerTransition.TRANSITION_TICKS; step++) {
            frame = transition.advance(listener(0.0D), candidates, true);
            assertEquals(lerp(fromAtListener, toAtListener, smooth(step)),
                    effectiveGain(frame, listener(0.0D)), 1.0E-9D,
                    "reference distance内で終点直前のgainを過大補正している");
        }
        assertEquals(40.0D, effectiveGain(frame, listener(0.0D)), 1.0E-9D);

        final double reverseFrom = openAlLinearGain(2.0D, 10.0D) * 40.0D;
        final double reverseTo = 80.0D;
        final SpeakerTransition.Frame reversed = transition.advance(listener(2.0D), candidates, true);
        assertEquals(lerp(reverseFrom, reverseTo, smooth(1)),
                effectiveGain(reversed, listener(2.0D)), 1.0E-9D,
                "耳元endpointからの反転でgainを飛ばしている");
    }

    @Test
    void overOneHundredPercentKeepsTheOpenAlTransitionCorrection() {
        final SpeakerTransition transition = new SpeakerTransition();
        final List<SpeakerSelection.Candidate> candidates = List.of(
                candidate("far", 2.0D, 10.0D, 200), candidate("near", 0.0D, 10.0D, 200));
        transition.advance(listener(2.0D), candidates, true);

        final double from = openAlLinearGain(2.0D, 10.0D) * 200.0D;
        final double to = 200.0D;
        for (int step = 1; step <= SpeakerTransition.TRANSITION_TICKS; step++) {
            final SpeakerTransition.Frame frame = transition.advance(listener(0.0D), candidates, true);
            assertEquals(lerp(from, to, smooth(step)), effectiveGain(frame, listener(0.0D)), 1.0E-9D,
                    "200%でもPCM段に渡す前のOpenAL距離補正を失っている");
        }
    }

    @Test
    void differentRangesKeepOpenAlGainContinuousAndReverseFromTheCurrentFrame() {
        final SpeakerTransition transition = new SpeakerTransition();
        final List<SpeakerSelection.Candidate> candidates = List.of(
                candidate("wide", 8.0D, 10.0D, 100), candidate("near", 1.0D, 2.0D, 100));
        transition.advance(listener(8.0D), candidates, true);

        final double from = openAlLinearGain(8.0D, 10.0D) * 100.0D;
        final double to = openAlLinearGain(1.0D, 2.0D) * 100.0D;
        SpeakerTransition.Frame frame = null;
        for (int step = 1; step <= 3; step++) {
            frame = transition.advance(listener(0.0D), candidates, true);
            assertEquals(10.0D, frame.rangeBlocks(), 1.0E-9D,
                    "狭い遷移先rangeを早くOpenALへ渡して中間座標を無音にしている");
            assertEquals(lerp(from, to, smooth(step)), effectiveGain(frame, listener(0.0D)), 1.0E-9D,
                    "rangeが違うanchorの間で音量と距離減衰の積に谷を作っている");
        }

        final double beforeReverse = effectiveGain(frame, listener(8.0D));
        final SpeakerTransition.Frame reversed = transition.advance(listener(8.0D), candidates, true);
        assertEquals(lerp(beforeReverse, 100.0D, smooth(1)), effectiveGain(reversed, listener(8.0D)), 1.0E-9D,
                "rangeが違う遷移の途中反転で現在frameから連続していない");

        SpeakerTransition.Frame settled = reversed;
        for (int step = 2; step <= SpeakerTransition.TRANSITION_TICKS; step++) {
            settled = transition.advance(listener(8.0D), candidates, true);
        }
        assertEquals(10.0D, settled.rangeBlocks(), 1.0E-9D);
    }

    private static SpeakerSelection.Candidate candidate(String id, double x, double range, int volume) {
        return new SpeakerSelection.Candidate(id, x, 0.0D, 0.0D, range, volume, false);
    }

    private static SpeakerSelection.Listener listener(double x) {
        return new SpeakerSelection.Listener(x, 0.0D, 0.0D);
    }

    private static double effectiveGain(SpeakerTransition.Frame frame, SpeakerSelection.Listener listener) {
        final double distance = Math.abs(listener.x() - frame.x());
        return frame.volumePercent() * frame.gainMultiplier()
                * openAlLinearGain(distance, frame.rangeBlocks());
    }

    /** Channel#linearAttenuation の AL_LINEAR_DISTANCE_CLAMPED 定義を、実装と独立に記す。 */
    private static double openAlLinearGain(double distance, double range) {
        if (range <= 1.0D) {
            return distance <= range ? 1.0D : 0.0D;
        }
        if (distance <= 1.0D) {
            return 1.0D;
        }
        if (distance >= range) {
            return 0.0D;
        }
        return (range - distance) / (range - 1.0D);
    }

    private static double smooth(int step) {
        final double linear = step / (double) SpeakerTransition.TRANSITION_TICKS;
        return linear * linear * (3.0D - 2.0D * linear);
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }
}
