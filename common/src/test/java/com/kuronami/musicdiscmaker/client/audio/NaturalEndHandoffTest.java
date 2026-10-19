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
        assertEquals(List.of("play"), ran, "a resend of the held song is folded into the first request");
        assertTrue(wait.drainHeld().isEmpty());
    }

    @Test
    void aDifferentNewerPlaybackIsAManualAction() {
        final NaturalEndHandoff wait = new NaturalEndHandoff(5L, 0L);
        assertEquals(NaturalEndHandoff.Arrival.HOLD, wait.arrive(7L, 0L, null));
        assertEquals(NaturalEndHandoff.Arrival.SUPERSEDE, wait.arrive(8L, 0L, null));
    }

    @Test
    void resendsKeepTheFirstPlayAndTheLatestSpeakerSet() {
        // 同じ世代の送り直し (chunk の追跡し直し・Speaker の範囲に入った時) は server の位置を載せている。
        // Play は最初の1本 (頭から) を残し、Speaker の集合は最新を残す。順序は最初に届いた順。
        final NaturalEndHandoff wait = new NaturalEndHandoff(5L, 0L);
        final List<String> ran = new ArrayList<>();
        wait.arrive(7L, 0L, "set", true, () -> ran.add("set-1"));
        wait.arrive(7L, 0L, "play", false, () -> ran.add("play@0"));
        wait.arrive(7L, 500L, "set", true, () -> ran.add("set-2"));
        wait.arrive(7L, 500L, "play", false, () -> ran.add("play@500"));
        wait.drainHeld().forEach(Runnable::run);
        assertEquals(List.of("set-2", "play@0"), ran);
    }

    @Test
    void aHeldSongThatAlreadyEndedOnTheServerIsSkipped() {
        final NaturalEndHandoff wait = new NaturalEndHandoff(5L, 0L);
        final List<String> ran = new ArrayList<>();
        assertEquals(NaturalEndHandoff.Arrival.HOLD, wait.arrive(7L, 0L, "play", false, () -> ran.add("song 7")));
        assertFalse(wait.dropHeldEndedBy(6L), "an older stop must not drop the held song");
        assertTrue(wait.dropHeldEndedBy(8L), "the end of the held song arrived while waiting");
        assertFalse(wait.holding());
        assertEquals(NaturalEndHandoff.Arrival.DROP, wait.arrive(7L, 0L, "play", false, () -> ran.add("resend 7")),
                "a resend of the skipped song must not start it");
        assertEquals(NaturalEndHandoff.Arrival.HOLD, wait.arrive(9L, 0L, "play", false, () -> ran.add("song 9")),
                "the song after it is held, so the previous song still rings out");
        wait.drainHeld().forEach(Runnable::run);
        assertEquals(List.of("song 9"), ran);
    }

    @Test
    void theCapCutsAtTheStoredLengthUnlessTheListenerStalled() {
        // 詰まっていない: 実音が曲の長さの位置に来たら切る (盤の長さが実際の音声より短くても全員が 10 秒鳴らさない)。
        assertEquals(NaturalEndHandoff.END_MARGIN_MS, NaturalEndHandoff.tailCapMs(12_000L, 12_000L, 0L));
        assertTrue(NaturalEndHandoff.tailCapMs(12_000L, 13_000L, 0L) <= 0L, "already past the end: stop now");
        // 詰まった: 無音で埋めた分だけ後ろまで待つ (F-1)。
        assertEquals(3_000L + NaturalEndHandoff.END_MARGIN_MS, NaturalEndHandoff.tailCapMs(12_000L, 12_000L, 3_000L));
        assertEquals(NaturalEndHandoff.MAX_WAIT_MS, NaturalEndHandoff.tailCapMs(12_000L, 12_000L, 30_000L));
        assertEquals(NaturalEndHandoff.MAX_WAIT_MS, NaturalEndHandoff.tailCapMs(12_000L, -1L, 0L),
                "unknown position: wait for the sound itself");
        final NaturalEndHandoff wait = new NaturalEndHandoff(5L, 1_000L, null);
        assertTrue(wait.keepWaiting(true, 1_500L, 300L));
        assertFalse(wait.keepWaiting(true, 1_500L, 0L), "no tail left: stop even if the sound still plays");
        assertFalse(wait.keepWaiting(true, 1_000L + NaturalEndHandoff.MAX_WAIT_MS, 5_000L), "the 10 s cap still holds");
    }

    @Test
    void aWorldChangeIsDetected() {
        final Object overworld = new Object();
        final NaturalEndHandoff wait = new NaturalEndHandoff(5L, 0L, overworld);
        assertTrue(wait.sameScope(overworld));
        assertFalse(wait.sameScope(new Object()));
        assertFalse(wait.sameScope(null));
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
