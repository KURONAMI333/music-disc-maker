package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 1 秒ごとの keep-alive を持つ経路 (ブームボックス) が<b>永久に鳴らない</b>形を潰す。
 *
 * <h2>潰している形</h2>
 * ロード中は「鳴っているインスタンス」がまだ無いので、判断材料をそれだけにすると心拍ごとに
 * 前のロードを捨てて新しいロードを始める。ストリーム確立が 1 秒を超える音源 (ネットワーク経由は
 * ほぼ該当) では、これが永久に繰り返されて<b>一度も音が立たず、ログにも何も出ない</b>。
 *
 * <p>あわせて {@link LoadFailureBackoff} の予算も固定する。ロードが失敗すると in-flight ガードが
 * 外れて次の心拍が必ず繋ぎ直すので、リンク切れ URL を積んだ機体は<b>持っている間ずっと</b>
 * 1 Hz でネットワークを叩く。
 */
class PlaybackRequestGateTest {

    private static final String URL_A = "https://example.invalid/a";
    private static final String URL_B = "https://example.invalid/b";

    @Test
    void firstRequestStartsALoad() {
        assertEquals(PlaybackRequestGate.Decision.START,
                PlaybackRequestGate.decide(null, false, null, URL_A));
    }

    @Test
    void keepAliveWhileLoadingIsIgnored() {
        // ここが START になると、ロードが 1 秒で終わらない音源は永久に鳴らない。
        assertEquals(PlaybackRequestGate.Decision.IGNORE,
                PlaybackRequestGate.decide(null, false, URL_A, URL_A));
    }

    @Test
    void keepAliveOnTheSameTrackOnlyRefreshes() {
        assertEquals(PlaybackRequestGate.Decision.REFRESH,
                PlaybackRequestGate.decide(URL_A, false, null, URL_A));
    }

    @Test
    void aDifferentTrackStartsEvenWhileLoading() {
        assertEquals(PlaybackRequestGate.Decision.START,
                PlaybackRequestGate.decide(null, false, URL_A, URL_B));
        assertEquals(PlaybackRequestGate.Decision.START,
                PlaybackRequestGate.decide(URL_A, false, null, URL_B));
    }

    @Test
    void aStoppedInstanceIsRestartedEvenOnTheSameTrack() {
        // 自然終了した後に同じ曲の心拍が来たら鳴らし直す (REFRESH だと無音のまま生き続ける)。
        assertEquals(PlaybackRequestGate.Decision.START,
                PlaybackRequestGate.decide(URL_A, true, null, URL_A));
    }

    @Test
    void backoffStopsRetryingTheSameBrokenUrl() {
        final LoadFailureBackoff<Long> backoff = new LoadFailureBackoff<>();
        final long key = 42L;
        assertFalse(backoff.isExhausted(key, URL_A));
        for (int attempt = 1; attempt < LoadFailureBackoff.MAX_ATTEMPTS; attempt++) {
            assertFalse(backoff.recordFailure(key, URL_A), "予算を使い切る前に打ち切っている");
        }
        assertTrue(backoff.recordFailure(key, URL_A));
        assertTrue(backoff.isExhausted(key, URL_A));
    }

    @Test
    void backoffIsPerTrackSoOneDeadLinkDoesNotSilenceTheRest() {
        final LoadFailureBackoff<Long> backoff = new LoadFailureBackoff<>();
        final long key = 42L;
        for (int attempt = 0; attempt < LoadFailureBackoff.MAX_ATTEMPTS; attempt++) {
            backoff.recordFailure(key, URL_A);
        }
        assertTrue(backoff.isExhausted(key, URL_A));
        assertFalse(backoff.isExhausted(key, URL_B), "別の曲まで巻き添えで打ち切っている");
    }

    @Test
    void backoffIsReturnedOnExplicitStop() {
        final LoadFailureBackoff<Long> backoff = new LoadFailureBackoff<>();
        final long key = 42L;
        for (int attempt = 0; attempt < LoadFailureBackoff.MAX_ATTEMPTS; attempt++) {
            backoff.recordFailure(key, URL_A);
        }
        backoff.reset(key);
        assertFalse(backoff.isExhausted(key, URL_A), "止めて掛け直しても試せないままになっている");
    }
    @Test
    void sameUrlAtNewAudioGenerationRestartsActiveAndPendingLoads() {
        assertEquals(PlaybackRequestGate.Decision.START,
                PlaybackRequestGate.decide(URL_A, false, null, URL_A, 7L, 8L));
        assertEquals(PlaybackRequestGate.Decision.START,
                PlaybackRequestGate.decide(null, false, URL_A, URL_A, 7L, 8L));
        assertEquals(PlaybackRequestGate.Decision.REFRESH,
                PlaybackRequestGate.decide(URL_A, false, null, URL_A, 8L, 8L));
        assertEquals(PlaybackRequestGate.Decision.IGNORE,
                PlaybackRequestGate.decide(null, false, URL_A, URL_A, 8L, 8L));
    }
}
