package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 金ジューク付きトロッコの曲末の待ち ({@link GoldenCartClientPlayback#tailMs}) を固定する。
 *
 * <p>設置した金のジュークボックスとブームボックスは、曲の途中で読み込みが詰まって無音で埋めた分
 * ({@link PlaybackVoice#paddedSilenceMs}) だけ停止を遅らせる。トロッコだけこの補正が無く、詰まりが
 * 長いと停止が先に届いて曲の最後が切れていた (#59)。
 *
 * <ul>
 *   <li>詰まっていなければ、実音が曲の長さの位置へ来たところで切る</li>
 *   <li>詰まった分だけ後ろまで待つ</li>
 *   <li>上限は 10 秒、残りが 0 以下なら待たない</li>
 * </ul>
 */
class GoldenCartTailTest {

    @Test
    void withoutAStallTheWaitEndsAtTheStoredLength() {
        // 曲尺 200 秒、鳴らし始めて 197 秒。残り 3 秒 + 出力バッファの余白。
        assertEquals(3_000L + NaturalEndHandoff.END_MARGIN_MS,
                GoldenCartClientPlayback.tailMs(200_000L, 0L, 197_000L, 0L));
    }

    @Test
    void theStalledSilenceIsAddedBackToTheWait() {
        // 同じ位置でも、3 秒を無音で埋めていたら実音は 3 秒後ろにいる = 3 秒長く待つ。
        assertEquals(3_000L + 3_000L + NaturalEndHandoff.END_MARGIN_MS,
                GoldenCartClientPlayback.tailMs(200_000L, 0L, 197_000L, 3_000L));
    }

    @Test
    void aMidSongStartIsCountedInThePosition() {
        // 60 秒の位置から鳴らし始めて 140 秒経過 = 実音は曲末。
        assertEquals(NaturalEndHandoff.END_MARGIN_MS,
                GoldenCartClientPlayback.tailMs(200_000L, 60_000L, 140_000L, 0L));
    }

    @Test
    void pastTheEndWithoutAStallDoesNotWait() {
        // 3.1.1 までと同じ切り方: 曲尺を過ぎたら待たない (盤の長さが実際の音声より短い場合に
        // 全員が余分に鳴らさないため)。
        assertTrue(GoldenCartClientPlayback.tailMs(200_000L, 0L, 200_500L, 0L) <= 0L);
    }

    @Test
    void aStallKeepsTheWaitPositivePastTheStoredLength() {
        // 詰まった client は、詰まった分だけ後ろまで待つ (#59 の本体)。
        assertEquals(3_000L,
                GoldenCartClientPlayback.tailMs(200_000L, 0L, 200_500L, 3_000L));
    }

    @Test
    void theCapIsTenSeconds() {
        assertEquals(NaturalEndHandoff.MAX_WAIT_MS,
                GoldenCartClientPlayback.tailMs(200_000L, 0L, 197_000L, 30_000L));
    }

    @Test
    void aNegativePaddingCountsAsNoStall() {
        assertEquals(GoldenCartClientPlayback.tailMs(200_000L, 0L, 197_000L, 0L),
                GoldenCartClientPlayback.tailMs(200_000L, 0L, 197_000L, -5_000L));
    }
}
