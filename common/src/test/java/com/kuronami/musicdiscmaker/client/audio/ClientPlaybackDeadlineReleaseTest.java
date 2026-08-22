package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

import net.minecraft.core.BlockPos;

/**
 * Sol のリリース前<b>再</b>レビュー ② の固定 — 期限切れの経路が先読みの枠を手放すこと。
 *
 * <h2>漏れていた形</h2>
 * 通常停止 ({@code ClientPlaybackManager#stopPlayback}) は {@code prefetch.drop} と
 * {@code sessions.stop} を対で呼ぶのに、期限切れは {@code sessions.stop} しか呼んでいなかった。
 *
 * <p>尺 40 秒のアルバム曲が登録されたのに実 PCM が来ない場合:
 *
 * <ol>
 *   <li>t=25s — {@code DiscSoundInstance#tick} が残り時間を見て<b>次の曲を先読みする</b></li>
 *   <li>t=30s — 期限が発火し、現在の音源と channel は解放される</li>
 *   <li>次の曲の枠と、その中で開いたソース (HTTP 接続 1 本 + LavaPlayer + 約 1MB のバッファ) が
 *       map に残る</li>
 * </ol>
 *
 * <p>{@link PlaybackPrefetch#EXPIRY_MS} の掃除は常駐タイマーではなく {@code begin}/{@code deliver}
 * が走るついでの遅延掃除なので、<b>次の再生が来るまで誰も閉じない</b>。
 *
 * <p>ここが JUnit で見られるのは、期限切れの<b>手放す部分だけ</b>を
 * {@link ClientPlaybackManager#releaseOnFirstAudioDeadline} に切り出してあるから。呼び出し元は
 * 失敗の表示 ({@code Minecraft} 依存) まで持っているのでそのままでは載らない。
 */
class ClientPlaybackDeadlineReleaseTest {

    private static final BlockPos KEY = new BlockPos(3, 4, 5);
    private static final String NEXT_URL = "http://example.invalid/next.mp3";
    private static final CustomTrackData SONG =
            new CustomTrackData("http://example.invalid/song.mp3", "Song", "Artist", 40_000L, "", false);

    /** close() の回数だけ数える音源。 */
    private static final class FakeSource implements IAudioSource {

        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public int sampleRate() {
            return 48_000;
        }

        @Override
        public int channels() {
            return 2;
        }

        @Override
        public int bitsPerSample() {
            return 16;
        }

        @Override
        public boolean bigEndian() {
            return false;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            return -1;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

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

    private final AtomicLong clock = new AtomicLong();
    private final PlaybackSessions sessions = new PlaybackSessions(clock::get);
    private final PlaybackPrefetch<BlockPos> prefetch = new PlaybackPrefetch<>(clock::get);

    /** 実 PCM が来ないまま次の曲を温めた状態を作る (t=25s の先読み)。 */
    private FakeSource armPlaybackWithAWarmNextTrack() {
        final PlaybackSessions.StartDecision decision =
                sessions.start(KEY, SONG, 0L, 0, 100, true);
        assertTrue(decision.load());
        assertTrue(sessions.install(KEY, decision.token(), SONG.url(), 0L, new FakeVoice()));

        clock.set(25_000L);
        final PlaybackPrefetch.Ticket<BlockPos> ticket = prefetch.begin(KEY, NEXT_URL);
        final FakeSource warm = new FakeSource();
        assertTrue(prefetch.deliver(ticket, warm), "先読みが枠に着地していない");
        assertEquals(NEXT_URL, prefetch.slotUrl(KEY), "先読みを抱えていない");

        clock.set(30_000L); // 期限
        return warm;
    }

    /** 期限切れ (非ラジオ) は、音源と channel だけでなく先読みの枠も手放すこと。 */
    @Test
    void expiringANonRadioPlaybackAlsoReleasesThePrefetchedNextTrack() {
        final FakeSource warm = armPlaybackWithAWarmNextTrack();

        assertFalse(ClientPlaybackManager.releaseOnFirstAudioDeadline(sessions, prefetch, KEY, false),
                "非ラジオは再接続経路へ流さない");

        assertEquals(1, warm.closeCount.get(),
                "期限切れで先読みのソースが閉じられていない"
                        + " (HTTP 接続と約 1MB のバッファを掴んだまま次の再生まで残る)");
        assertNull(prefetch.slotUrl(KEY), "先読みの枠が map に残っている");
    }

    /**
     * ラジオの期限切れも同じく手放すこと。
     *
     * <p>ラジオは {@code prefetchNext} が弾くので枠を持たないはずで、実際には no-op になる。
     * それでも経路で書き分けない — <b>「持たないはず」を根拠にした省略が、片方の経路だけ
     * 解放の抜けた今回の欠陥そのものだった</b>。音源は畳まず終端と同じ再接続経路へ流す。
     */
    @Test
    void expiringARadioPlaybackAlsoReleasesThePrefetch() {
        final FakeSource warm = armPlaybackWithAWarmNextTrack();

        assertTrue(ClientPlaybackManager.releaseOnFirstAudioDeadline(sessions, prefetch, KEY, true),
                "ラジオは再接続経路へ流す");

        assertEquals(1, warm.closeCount.get(), "ラジオ経路でも先読みは手放すこと");
        assertNull(prefetch.slotUrl(KEY), "先読みの枠が map に残っている");
    }

    /** 二度呼んでも二度閉じないこと (drop は枠を取り除けた側だけが閉じる)。 */
    @Test
    void releasingTwiceDoesNotCloseTheSourceTwice() {
        final FakeSource warm = armPlaybackWithAWarmNextTrack();

        ClientPlaybackManager.releaseOnFirstAudioDeadline(sessions, prefetch, KEY, false);
        ClientPlaybackManager.releaseOnFirstAudioDeadline(sessions, prefetch, KEY, false);

        assertEquals(1, warm.closeCount.get(), "同じソースを二度閉じている");
    }
}
