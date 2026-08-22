package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;

/**
 * Sol のリリース前レビュー ④⑤ の固定。
 *
 * <h2>何を分けたのか</h2>
 * {@code playStartMillis} は「登録できた時刻」1 つで 2 つの問いに答えていた:
 *
 * <ol>
 *   <li>ラジオは十分長く<b>鳴っていた</b>か ({@link PlaybackSessions#radioStreamEnded})</li>
 *   <li>いま<b>どこを再生している推定</b>か ({@code isSeekRequest})</li>
 * </ol>
 *
 * {@code SoundManager#play} はインスタンスを登録するだけで音声ストリームの future は未完了の
 * まま返るので、登録時刻は (1) の答えとしては<b>早すぎる</b> — 一音も出ないまま 15 秒経てば
 * 「安定していた」とみなし、再接続の試行回数を誤ってリセットする。
 *
 * <p>一方 (2) の起点は<b>動かしてはいけない</b>。server が送ってくる {@code requestedOffsetMs} は
 * 壁時計基準なので、推定を実 PCM 基準へ寄せると無音の総量ぶん後退し、
 * {@code SEEK_TOLERANCE_MS} (1200ms) を超えた瞬間に chunk 再入の再送を本物のシークと
 * 誤判定して鳴らし直す。だから起点は 2 つに割った
 * ({@code registeredMillis} と {@code firstAudioMillis})。
 */
class PlaybackSessionsFirstAudioTest {

    private static final BlockPos KEY = new BlockPos(1, 2, 3);
    private static final CustomTrackData RADIO =
            new CustomTrackData("http://example.invalid/stream", "Live", "Station", 0L, "", true);
    private static final CustomTrackData SONG =
            new CustomTrackData("http://example.invalid/song.mp3", "Song", "Artist", 300_000L, "", false);

    /** 停止だけを覚える音源。 */
    private static final class FakeVoice implements PlaybackVoice {

        private volatile boolean stopped;

        @Override
        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void setDirectional(boolean value) {
        }

        @Override
        public void setRangeBlocks(int value) {
        }

        @Override
        public void setVolumePercent(int value) {
        }

        @Override
        public void stopAndRelease() {
            stopped = true;
        }
    }

    /** 進めたい時に進める時計。 */
    private final AtomicLong clock = new AtomicLong();
    private final PlaybackSessions sessions = new PlaybackSessions(clock::get);

    private int startRadio() {
        final PlaybackSessions.StartDecision decision =
                sessions.start(KEY, RADIO, 0L, 0, 100, true);
        assertTrue(decision.load());
        return decision.token();
    }

    /**
     * ⑤ の本体。<b>登録から</b>数えると「安定していた」になり、<b>鳴り始めてから</b>数えると
     * ならない区間を作って、試行回数がリセットされないことを見る。
     *
     * <p>2 周目: 登録 25s → 最初の実 PCM 35s → 瞬断 45s。登録起点なら 20 秒で
     * {@code STABLE_MS} (15 秒) を超えるのでリセットされ {@code attempt=1} に戻るが、
     * 実際に鳴っていたのは 10 秒しかない。分割後は {@code attempt=2} が返る。
     */
    @Test
    void reconnectAttemptIsNotResetByTimeSpentBeforeTheFirstRealPcm() {
        final int token = startRadio();

        clock.set(0L);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
        clock.set(10_000L);
        assertTrue(sessions.noteFirstAudio(KEY, token));

        clock.set(20_000L);
        final PlaybackSessions.Reconnect first = sessions.radioStreamEnded(KEY, token);
        assertEquals(PlaybackSessions.ReconnectKind.RETRY, first.kind());
        assertEquals(1, first.attempt());

        // 再接続 (同じ世代を持ち回る)。登録 25s / 実 PCM 35s / 瞬断 45s。
        clock.set(25_000L);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
        clock.set(35_000L);
        assertTrue(sessions.noteFirstAudio(KEY, token));

        clock.set(45_000L);
        final PlaybackSessions.Reconnect second = sessions.radioStreamEnded(KEY, token);
        assertEquals(PlaybackSessions.ReconnectKind.RETRY, second.kind());
        assertEquals(2, second.attempt(),
                "実際に鳴っていたのは 10 秒 (< STABLE_MS) なので試行回数はリセットされない");
    }

    /** 実際に {@code STABLE_MS} 以上鳴っていれば、これまで通りリセットされる。 */
    @Test
    void reconnectAttemptIsResetWhenAudioActuallyPlayedLongEnough() {
        final int token = startRadio();

        clock.set(0L);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
        clock.set(1_000L);
        assertTrue(sessions.noteFirstAudio(KEY, token));
        clock.set(10_000L);
        assertEquals(1, sessions.radioStreamEnded(KEY, token).attempt());

        clock.set(11_000L);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
        clock.set(12_000L);
        assertTrue(sessions.noteFirstAudio(KEY, token));
        clock.set(30_000L); // 実 PCM から 18 秒 = STABLE_MS 超え
        assertEquals(1, sessions.radioStreamEnded(KEY, token).attempt(),
                "18 秒鳴っていたのだから試行回数はリセットされる");
    }

    /** 一音も鳴らないまま瞬断が続いたら、リセットは一度も起きず上限で諦める。 */
    @Test
    void neverStartingRadioGivesUpAfterMaxReconnect() {
        final int token = startRadio();
        for (int attempt = 1; attempt <= PlaybackSessions.MAX_RECONNECT; attempt++) {
            clock.set(attempt * 60_000L);
            assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
            clock.set(attempt * 60_000L + 59_000L); // 登録からは 59 秒。だが実 PCM は一度も来ていない
            final PlaybackSessions.Reconnect r = sessions.radioStreamEnded(KEY, token);
            assertEquals(PlaybackSessions.ReconnectKind.RETRY, r.kind());
            assertEquals(attempt, r.attempt());
        }
        clock.set(999_000L);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
        clock.set(1_000_000L);
        assertEquals(PlaybackSessions.ReconnectKind.GIVE_UP,
                sessions.radioStreamEnded(KEY, token).kind());
    }

    /** 最初の実 PCM は 1 世代につき 1 回だけ通す (2 回目は {@code false})。 */
    @Test
    void noteFirstAudioIsAcceptedOnlyOncePerGeneration() {
        final int token = startRadio();
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));

        assertTrue(sessions.noteFirstAudio(KEY, token));
        assertFalse(sessions.noteFirstAudio(KEY, token), "同じ世代で 2 回目は通さない");
    }

    /** 世代が進んだ後に届いた最初の実 PCM は捨てる (前の曲の Now Playing を上書きさせない)。 */
    @Test
    void noteFirstAudioFromASupersededGenerationIsRejected() {
        final int stale = startRadio();
        assertTrue(sessions.install(KEY, stale, RADIO.url(), 0L, new FakeVoice()));

        final PlaybackSessions.StartDecision next = sessions.start(KEY, SONG, 0L, 0, 100, true);
        assertTrue(next.load());

        assertFalse(sessions.noteFirstAudio(KEY, stale), "古い世代の最初の実 PCM は通さない");
        assertTrue(sessions.noteFirstAudio(KEY, next.token()));
    }

    /**
     * ④ の上限。実 PCM が一度も来ないまま {@link PlaybackSessions#FIRST_AUDIO_DEADLINE_MS} を
     * 超えたら期限切れとして拾える (= 永久に待たない)。
     */
    @Test
    void firstAudioBecomesOverdueOnlyAfterTheDeadlineAndOnlyWhenSilent() {
        final int token = startRadio();
        clock.set(0L);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));

        clock.set(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS - 1L);
        assertFalse(sessions.firstAudioOverdue(KEY, token), "期限前は倒さない");

        clock.set(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS);
        assertTrue(sessions.firstAudioOverdue(KEY, token), "期限を過ぎて無音なら倒す");
    }

    /** 期限までに鳴り始めていれば、期限を大きく過ぎても倒さない。 */
    @Test
    void firstAudioIsNeverOverdueOnceAudioArrived() {
        final int token = startRadio();
        clock.set(0L);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
        clock.set(5_000L);
        assertTrue(sessions.noteFirstAudio(KEY, token));

        clock.set(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS * 10L);
        assertFalse(sessions.firstAudioOverdue(KEY, token));
    }

    /** 停止済み / 世代違いには期限切れを答えない (止めた再生の後始末で失敗を出さない)。 */
    @Test
    void firstAudioOverdueIsSilentForStoppedOrSupersededPlayback() {
        final int token = startRadio();
        clock.set(0L);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
        sessions.stop(KEY);

        clock.set(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS * 2L);
        assertFalse(sessions.firstAudioOverdue(KEY, token));
    }

    /** 期限は下限 (プリバッファ 10 秒) を割らず、上限 (lavaplayer の 60 秒 CLEANUP) を超えない。 */
    @Test
    void firstAudioDeadlineStaysInsideTheKnownBand() {
        assertTrue(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS >= 10_000L,
                "10 秒未満は健全なプリバッファ中の再生を失敗側へ倒す");
        assertTrue(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS < 60_000L,
                "60 秒を超えると lavaplayer の CLEANUP が先に殺して理由が出ない");
    }
}
