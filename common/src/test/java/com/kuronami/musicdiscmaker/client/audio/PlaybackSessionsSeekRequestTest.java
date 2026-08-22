package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;

/**
 * chunk 再入の再送 (dedup) と本物のシーク (鳴らし直し) の境目を固定する。
 *
 * <h2>これが錠前である理由</h2>
 * 推定再生位置は {@code loadOffsetMs + (今 - 登録時刻)} で、突き合わせる相手は server が送って
 * くる {@code requestedOffsetMs} = <b>壁時計基準</b>の経過 ms。だから起点は「登録できた時刻」で
 * なければならない。最初の実 PCM の時刻へ寄せると<b>無音の総量ぶん推定が後退</b>し、
 * {@code SEEK_TOLERANCE_MS} (1200ms) を超えた瞬間に chunk の出入りのたびに曲が頭から鳴り直す。
 *
 * <p>{@code isSeekRequest} は private なので {@link PlaybackSessions#start} の戻り値
 * ({@code load}) 越しに見る。{@code load=false} = 同じ曲の再送として畳んだ、
 * {@code load=true} = 鳴らし直す。
 */
class PlaybackSessionsSeekRequestTest {

    private static final BlockPos KEY = new BlockPos(4, 5, 6);
    private static final CustomTrackData SONG =
            new CustomTrackData("http://example.invalid/song.mp3", "Song", "Artist", 300_000L, "", false);

    private static final class FakeVoice implements PlaybackVoice {

        @Override
        public boolean isStopped() {
            return false;
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
        }
    }

    private final AtomicLong clock = new AtomicLong();
    private final PlaybackSessions sessions = new PlaybackSessions(clock::get);

    /** 登録済みの再生を 1 つ作る。{@code loadOffsetMs} は 0。 */
    private int install(long atMs) {
        final PlaybackSessions.StartDecision decision = sessions.start(KEY, SONG, 0L, 0, 100, true);
        assertTrue(decision.load());
        clock.set(atMs);
        assertTrue(sessions.install(KEY, decision.token(), SONG.url(), 0L, new FakeVoice()));
        return decision.token();
    }

    /** 推定位置とほぼ一致する再送 (chunk 再入) は畳む。 */
    @Test
    void resendMatchingTheEstimatedPositionIsDeduped() {
        install(0L);
        clock.set(5_000L);
        assertFalse(sessions.start(KEY, SONG, 5_000L, 0, 100, true).load());
    }

    /** 許容幅の内側は畳み、外側は鳴らし直す (境目 = 1200ms)。 */
    @Test
    void toleranceBoundaryIsUnchanged() {
        install(0L);
        clock.set(5_000L);
        assertFalse(sessions.start(KEY, SONG, 5_000L + 1_199L, 0, 100, true).load(),
                "1199ms のずれは再送として畳む");

        install(0L);
        clock.set(5_000L);
        assertTrue(sessions.start(KEY, SONG, 5_000L + 1_200L, 0, 100, true).load(),
                "1200ms のずれは本物のシーク");
    }

    /** 後方シークは常に推定 (前進中) と乖離するので必ず通る。 */
    @Test
    void backwardSeekAlwaysLoads() {
        install(0L);
        clock.set(120_000L);
        assertTrue(sessions.start(KEY, SONG, 0L, 0, 100, true).load());
    }

    /**
     * <b>錠前の本体。</b> 最初の実 PCM を記録しても推定位置は動かない。
     *
     * <p>起点を実 PCM 基準へ寄せた実装なら、ここで推定が 4 秒後退して
     * {@code |5000 - 1000| = 4000 >= 1200} となり「本物のシーク」に化ける
     * = chunk の出入りのたびに曲が頭から鳴り直す。
     */
    @Test
    void notingTheFirstRealPcmDoesNotMoveTheEstimatedPosition() {
        final int token = install(0L);
        clock.set(4_000L);
        assertTrue(sessions.noteFirstAudio(KEY, token));

        clock.set(5_000L);
        assertFalse(sessions.start(KEY, SONG, 5_000L, 0, 100, true).load(),
                "推定位置の起点は登録時刻のまま。実 PCM の到着で動かしてはいけない");
    }
}
