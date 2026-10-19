package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 曲の終わりで前の音を鳴らし切ってから次の曲を始める待ち ({@link NaturalEndHandoff}) を固定する。
 *
 * <ul>
 *   <li>前の音が止まるまで待ち、止まったらすぐ次へ進む</li>
 *   <li>10 秒で打ち切る (読み込みが詰まったままの音で次の曲を止め続けない)</li>
 *   <li>次の曲は最初の世代だけ預かり、別の世代 (手動の次へ・前へ) が来たら待ちを畳む</li>
 *   <li>前の音と同じか古い世代は待ちと関係ない</li>
 * </ul>
 */
class NaturalEndHandoffTest {

    @Test
    void waitsWhileThePreviousSongIsStillPlaying() {
        final NaturalEndHandoff wait = new NaturalEndHandoff(5L, 1_000L);
        assertTrue(wait.keepWaiting(true, 1_000L));
        assertTrue(wait.keepWaiting(true, 1_000L + NaturalEndHandoff.MAX_WAIT_MS - 1L));
        assertFalse(wait.keepWaiting(false, 1_050L), "the next song must start as soon as the previous one ends");
    }

    @Test
    void givesUpAfterTheCap() {
        final NaturalEndHandoff wait = new NaturalEndHandoff(5L, 1_000L);
        assertFalse(wait.keepWaiting(true, 1_000L + NaturalEndHandoff.MAX_WAIT_MS));
    }

    @Test
    void holdsTheFirstNewerPlaybackAndItsResends() {
        final NaturalEndHandoff wait = new NaturalEndHandoff(5L, 0L);
        final List<String> ran = new ArrayList<>();
        assertEquals(NaturalEndHandoff.Arrival.HOLD, wait.arrive(7L, 100L, () -> ran.add("play")));
        assertEquals(NaturalEndHandoff.Arrival.HOLD, wait.arrive(7L, 1_100L, () -> ran.add("keep-alive")));
        assertTrue(wait.holding());
        assertEquals(7L, wait.heldGeneration());
        assertEquals(1_900L, wait.heldForMs(2_000L), "the delay counts from the first held payload");
        wait.drainHeld().forEach(Runnable::run);
        assertEquals(List.of("play", "keep-alive"), ran);
        assertTrue(wait.drainHeld().isEmpty());
    }

    @Test
    void aDifferentNewerPlaybackIsAManualAction() {
        final NaturalEndHandoff wait = new NaturalEndHandoff(5L, 0L);
        assertEquals(NaturalEndHandoff.Arrival.HOLD, wait.arrive(7L, 0L, null));
        assertEquals(NaturalEndHandoff.Arrival.SUPERSEDE, wait.arrive(8L, 0L, null));
    }

    @Test
    void theEndedSongItselfIsNotHeld() {
        final NaturalEndHandoff wait = new NaturalEndHandoff(5L, 0L);
        assertEquals(NaturalEndHandoff.Arrival.PASS, wait.arrive(5L, 0L, null));
        assertEquals(NaturalEndHandoff.Arrival.PASS, wait.arrive(4L, 0L, null));
        assertFalse(wait.holding());
        assertEquals(0L, wait.heldForMs(5_000L));
    }
}
