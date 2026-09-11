package com.kuronami.musicdiscmaker.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * プレイリストディスクの曲送りの判断を固定する。
 *
 * <p>縛りたいのは {@code DESIGN_PLAYLIST_SYNC.md} の凍結 3 つ:
 * 曲送りの契機はサーバの壁時計と曲の尺だけであること (凍結4)、
 * ラジオと尺不明に当たったら鳴って止まること (同・KURONAMI333 裁定 2026-09-04)、
 * repeat は列全体のループであること。
 *
 * <p><b>対照を両側に置く。</b>「送られること」だけを確かめると、常に送る実装でも通ってしまう。
 */
class PlaylistPlaybackTest {

    // ── 陽性対照: 送られるべきものが送られる ──

    @Test
    @DisplayName("尺を使い切った曲は終わる。境界ちょうども終わる")
    void finiteTrackFinishesAtBoundary() {
        assertTrue(PlaylistPlayback.finished(180_000L, 180_000L, false));
        assertTrue(PlaylistPlayback.finished(180_001L, 180_000L, false));
    }

    @Test
    @DisplayName("列の途中では次の位置へ進む")
    void advancesWithinList() {
        assertEquals(1, PlaylistPlayback.nextIndex(0, 3, false));
        assertEquals(2, PlaylistPlayback.nextIndex(1, 3, false));
    }

    @Test
    @DisplayName("repeat が入っていれば末尾から先頭へ戻る (列全体のループ)")
    void repeatWrapsToHead() {
        assertEquals(0, PlaylistPlayback.nextIndex(2, 3, true));
        assertEquals(0, PlaylistPlayback.nextIndex(0, 1, true));
    }

    @Test
    @DisplayName("専用盤は1曲でもプレイリストの再生位置を持つ")
    void nonemptyPlaylistGetsIndex() {
        assertEquals(0, PlaylistPlayback.initialIndex(1));
        assertEquals(0, PlaylistPlayback.initialIndex(2));
        assertEquals(0, PlaylistPlayback.initialIndex(25));
    }

    @Test
    @DisplayName("有限尺の曲は先読みへ渡せる")
    void finiteTrackIsPrefetchable() {
        assertTrue(PlaylistPlayback.prefetchable(1L, false));
        assertTrue(PlaylistPlayback.prefetchable(180_000L, false));
    }

    // ── 陰性対照: 送ってはいけないものが送られない ──

    @Test
    @DisplayName("ラジオは鳴り終わらない。当たったらそこで止まる")
    void radioNeverFinishes() {
        assertFalse(PlaylistPlayback.finished(0L, 180_000L, true));
        assertFalse(PlaylistPlayback.finished(Long.MAX_VALUE / 2, 180_000L, true));
        assertFalse(PlaylistPlayback.finished(Long.MAX_VALUE / 2, 0L, true));
    }

    @Test
    @DisplayName("尺不明も鳴り終わらない。バケットの尺で代用しない")
    void unknownDurationNeverFinishes() {
        assertFalse(PlaylistPlayback.finished(Long.MAX_VALUE / 2, 0L, false));
        assertFalse(PlaylistPlayback.finished(Long.MAX_VALUE / 2, -1L, false));
    }

    @Test
    @DisplayName("尺の途中では終わらない")
    void midTrackDoesNotFinish() {
        assertFalse(PlaylistPlayback.finished(0L, 180_000L, false));
        assertFalse(PlaylistPlayback.finished(179_999L, 180_000L, false));
    }

    @Test
    @DisplayName("repeat が無ければ末尾で止まる")
    void lastTrackStopsWithoutRepeat() {
        assertEquals(PlaylistPlayback.NO_TRACK, PlaylistPlayback.nextIndex(2, 3, false));
        assertEquals(PlaylistPlayback.NO_TRACK, PlaylistPlayback.nextIndex(0, 1, false));
    }

    @Test
    @DisplayName("列を持たない盤は位置を持たない。曲送りの経路に入らない")
    void emptyPlaylistHasNoIndex() {
        assertEquals(PlaylistPlayback.NO_TRACK, PlaylistPlayback.initialIndex(0));
        assertEquals(PlaylistPlayback.NO_TRACK, PlaylistPlayback.initialIndex(-1));
    }

    @Test
    @DisplayName("位置を持たない状態からは進めない (単曲の repeat をここへ流し込ませない)")
    void noTrackIndexNeverAdvances() {
        assertEquals(PlaylistPlayback.NO_TRACK, PlaylistPlayback.nextIndex(PlaylistPlayback.NO_TRACK, 3, true));
        assertEquals(PlaylistPlayback.NO_TRACK, PlaylistPlayback.nextIndex(0, 0, true));
    }

    @Test
    @DisplayName("ラジオと尺不明は先読みへ渡さない")
    void radioAndUnknownAreNotPrefetchable() {
        assertFalse(PlaylistPlayback.prefetchable(180_000L, true));
        assertFalse(PlaylistPlayback.prefetchable(0L, false));
        assertFalse(PlaylistPlayback.prefetchable(-1L, false));
    }

    // ── 壊れた値からの復帰 ──

    @Test
    @DisplayName("保存された位置が曲数を超えていても、進めずに止まるだけで済む")
    void indexBeyondListStopsInsteadOfThrowing() {
        assertEquals(PlaylistPlayback.NO_TRACK, PlaylistPlayback.nextIndex(9, 3, false));
        assertEquals(0, PlaylistPlayback.nextIndex(9, 3, true));
    }
}
