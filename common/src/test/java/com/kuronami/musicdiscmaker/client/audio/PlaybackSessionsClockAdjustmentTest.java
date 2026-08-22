package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;

/**
 * Sol のリリース前<b>再</b>レビュー ① の固定 — OS の時刻調整で判定が壊れないこと。
 *
 * <h2>壊れていた順序</h2>
 * <ol>
 *   <li>{@link PlaybackSessions#install} が<b>壁時計</b>で登録時刻を保存する</li>
 *   <li>OS の時刻が後方修正される (NTP 同期・手動調整・スリープ復帰)</li>
 *   <li>実時間で 30 秒後、{@code ClientPlaybackManager} の delayed task が予定どおり
 *       <b>一度だけ</b>起きる ({@code CompletableFuture.delayedExecutor} は {@code nanoTime}
 *       基準なので、時刻調整では動かない)</li>
 *   <li>{@link PlaybackSessions#firstAudioOverdue} は {@code 今 - 登録} が 30 秒に届かず<b>偽</b></li>
 *   <li>task は<b>再アームされない</b>ので、future 未完了・PCM 無しの再生が source と channel を
 *       掴んだまま永久に残る</li>
 * </ol>
 *
 * <p>同じ形がラジオの安定判定 ({@link PlaybackSessions#STABLE_MS}) にもあり、そちらは前方修正で
 * 逆に倒れる — 実際には数秒しか鳴っていないのに「安定していた」とみなして再接続の試行回数を
 * リセットし、瞬断を繰り返す音源で永久に再接続する。
 *
 * <p>だから「経過時間の計測」と「server の壁時計との突き合わせ」を別の時計に分けた。
 * ここでは 2 つを<b>別々に動かして</b>、経過側が壁時計に一切依存しないことを見る。
 */
class PlaybackSessionsClockAdjustmentTest {

    private static final BlockPos KEY = new BlockPos(7, 8, 9);
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

    /** 壁時計。巻き戻しも早送りもできる。 */
    private final AtomicLong wall = new AtomicLong(1_700_000_000_000L);
    /** 単調時計。前にしか進まない。 */
    private final AtomicLong mono = new AtomicLong();
    private final PlaybackSessions sessions = new PlaybackSessions(wall::get, mono::get);

    private int start(CustomTrackData track) {
        final PlaybackSessions.StartDecision decision = sessions.start(KEY, track, 0L, 0, 100, true);
        assertTrue(decision.load());
        return decision.token();
    }

    /**
     * <b>① の本体。</b> 登録の後に壁時計が 1 時間<b>戻って</b>も、実時間で 30 秒経っていれば
     * 期限切れとして拾えること。
     *
     * <p>壁時計で数える実装では {@code 今 - 登録} が負になるので偽を返し、delayed task は
     * 再アームされないまま消える = source と channel を掴んだ再生が永久に残る。
     */
    @Test
    void theDeadlineStillFiresAfterTheWallClockIsMovedBackwards() {
        final int token = start(RADIO);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));

        wall.addAndGet(-3_600_000L); // OS の時刻が 1 時間戻った
        mono.set(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS); // 実時間では期限ちょうど

        assertTrue(sessions.firstAudioOverdue(KEY, token),
                "壁時計が戻っても、実時間で期限を過ぎた無音の再生は畳めること");
    }

    /** 壁時計が大きく<b>進んで</b>も、実時間で期限前なら健全な再生を倒さないこと。 */
    @Test
    void theDeadlineDoesNotFireEarlyAfterTheWallClockJumpsForward() {
        final int token = start(RADIO);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));

        wall.addAndGet(3_600_000L); // OS の時刻が 1 時間進んだ
        mono.set(1_000L); // 実時間ではまだ 1 秒

        assertFalse(sessions.firstAudioOverdue(KEY, token),
                "壁時計が進んだだけで、まだプリバッファ中の健全な再生を失敗側へ倒してはいけない");
    }

    /**
     * ラジオの安定判定も壁時計に依存しないこと。実際には 5 秒しか鳴っていないのに、壁時計が
     * 1 時間進んだせいで「安定していた」とみなして試行回数をリセットしないこと。
     *
     * <p>リセットされると、瞬断を繰り返す音源で {@link PlaybackSessions#MAX_RECONNECT} に
     * 永久に届かない = 再接続を延々繰り返す。
     */
    @Test
    void radioStabilityIsNotFakedByAForwardWallClockJump() {
        final int token = start(RADIO);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
        assertTrue(sessions.noteFirstAudio(KEY, token));
        mono.set(1_000L);
        assertEquals(1, sessions.radioStreamEnded(KEY, token).attempt());

        // 2 周目: 実時間では 5 秒しか鳴っていない (< STABLE_MS)。その間に壁時計が 1 時間進む。
        mono.set(2_000L);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
        assertTrue(sessions.noteFirstAudio(KEY, token));
        wall.addAndGet(3_600_000L);
        mono.set(7_000L);

        assertEquals(2, sessions.radioStreamEnded(KEY, token).attempt(),
                "実際に鳴っていたのは 5 秒 (< STABLE_MS)。壁時計の飛びでリセットしてはいけない");
    }

    /** 逆に、壁時計が戻っても実時間で十分鳴っていればこれまで通りリセットされること。 */
    @Test
    void radioStabilityIsStillCreditedWhenTheWallClockMovesBackwards() {
        final int token = start(RADIO);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
        assertTrue(sessions.noteFirstAudio(KEY, token));
        mono.set(1_000L);
        assertEquals(1, sessions.radioStreamEnded(KEY, token).attempt());

        mono.set(2_000L);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));
        assertTrue(sessions.noteFirstAudio(KEY, token));
        wall.addAndGet(-3_600_000L);
        mono.set(2_000L + PlaybackSessions.STABLE_MS + 1_000L);

        assertEquals(1, sessions.radioStreamEnded(KEY, token).attempt(),
                "実時間で STABLE_MS 以上鳴っていたのだから、壁時計が戻っても試行回数はリセットする");
    }

    /**
     * <b>錠前。</b> 推定再生位置は<b>壁時計のまま</b>であること。
     *
     * <p>server が送ってくる {@code requestedOffsetMs} は壁時計基準の経過 ms なので、ここを
     * 単調時計へ寄せると基準がずれ、chunk 再入の再送を本物のシークと誤判定して鳴らし直す
     * ({@code PlaybackSessionsSeekRequestTest} の錠前は 2 つの時計を同じ供給元に束ねているので、
     * この取り違えを区別できない。区別できるのはここだけ)。
     *
     * <p>登録から壁時計で 5 秒・単調時計で 50 秒進めた状態に、5 秒地点の再送を投げる。
     * 壁時計基準なら推定は 5 秒でぴたり一致 = 畳む。単調時計基準なら推定が 50 秒へ飛び、
     * 45 秒のずれが {@code SEEK_TOLERANCE_MS} を大きく超えて鳴らし直しになる。
     */
    @Test
    void theEstimatedPlaybackPositionStaysOnTheWallClock() {
        final int token = start(SONG);
        assertTrue(sessions.install(KEY, token, SONG.url(), 0L, new FakeVoice()));

        wall.addAndGet(5_000L);
        mono.set(50_000L);

        assertFalse(sessions.start(KEY, SONG, 5_000L, 0, 100, true).load(),
                "推定再生位置の起点は壁時計のまま。単調時計へ寄せると chunk 再入で鳴らし直す");
    }

    /**
     * <b>設計の錠前。</b> 期限判定を「世代トークンが一致していて実 PCM が無ければ期限切れ」
     * という<b>経過時間を見ない形</b>に置き換えてはいけないこと。
     *
     * <p>ラジオの再接続は<b>同じ世代を持ち回る</b> ({@code radioStreamEnded} は RETRY では
     * 世代を進めない)。だから 1 回目の install で仕掛けた delayed task は、再接続後の健全な
     * 再生が走っている最中に「トークン一致・実 PCM 無し」で起きる。経過時間を見ない実装は
     * ここで<b>健全な再接続を殺す</b>。
     *
     * <p>実時間: install(0s) → 瞬断(5s) → 再接続の install(8s) → 1 本目の期限が 30s に発火。
     * 生きている再生はまだ 22 秒しか経っていないので、倒してはいけない。
     */
    @Test
    void aStaleDeadlineFromAnEarlierInstallDoesNotKillAHealthyReconnect() {
        final int token = start(RADIO);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()));

        mono.set(5_000L);
        final PlaybackSessions.Reconnect retry = sessions.radioStreamEnded(KEY, token);
        assertEquals(PlaybackSessions.ReconnectKind.RETRY, retry.kind());

        mono.set(8_000L);
        assertTrue(sessions.install(KEY, token, RADIO.url(), 0L, new FakeVoice()),
                "再接続は同じ世代を持ち回るので install は通る");

        mono.set(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS); // 1 本目の task が起きる時刻
        assertFalse(sessions.firstAudioOverdue(KEY, token),
                "生きている再生はまだ 22 秒。古い task の発火で倒してはいけない");
    }
}
