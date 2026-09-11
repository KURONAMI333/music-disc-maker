package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * 金ジュークの loop (repeat ON) で曲の尺ごとに起きる折り返しを、実際の帳簿へ N 周ぶん流して
 * 数を合わせる。
 *
 * <h2>なぜこれを測るか</h2>
 * C1 (1.21.1 Fabric で loop を使うと近くの全員がクラッシュする) の探索範囲が、
 * <b>loop を入れた 1.21.1 でだけ通る経路</b>へ絞れている。その経路の中心は
 * {@link PlaybackPrefetch} の warm path と {@link SoundEngineAcceptance} の拒否時の後始末で、
 * どちらも MC を握らないのでここで回せる。
 *
 * <h2>ここで測れないもの</h2>
 * {@link PlaybackSessions} は API に {@code BlockPos} を取るので、この層 (MC 非依存が契約) には
 * 載らない。世代トークンと期限回収の帳尻はまだ測れていない。
 *
 * <p>{@link SoundEngineAcceptance} の<b>拒否の経路も、例外を投げる engine では回せない</b>。
 * {@code reasonFor} が捕まえた後のログが {@code MusicDiscMaker.LOGGER} 経由で
 * {@code com.mojang.logging.LogUtils} を class-load するので、MC を持たない test classpath では
 * {@code NoClassDefFoundError} になる (2026-09-02 実測)。production には LogUtils があるので
 * そちらでは起きない。同クラスの javadoc が言う「Minecraft を掴まないので headless テストに載る」は、
 * <b>受理・拒否の判定までで、拒否の理由付けには当てはまらない</b>。
 *
 * <p>サウンドエンジンが実際にチャンネルを配るかも測れない。<b>緑でも「engine 側に何かある」を
 * 否定しない。</b>逆に赤が出れば、それは実機を待たずに直せる欠陥になる。
 */
class LoopTurnaroundBookkeepingTest {

    private static final String KEY = "10,64,-30";
    private static final String URL = "https://example.invalid/loop-track";
    private static final long TRACK_MS = 20_000L;

    /** 閉じられた回数を数えるだけの音源。 */
    private static final class FakeSource implements IAudioSource {

        private final int serial;
        private int closeCount;

        private FakeSource(int serial) {
            this.serial = serial;
        }

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
            closeCount++;
        }

        @Override
        public String toString() {
            return "source#" + serial + "(closed=" + closeCount + ")";
        }
    }

    private final AtomicLong clockMs = new AtomicLong(1_000_000L);

    private void advance(long ms) {
        clockMs.addAndGet(ms);
    }

    // -- 1. 先読み枠が周回で漏れないか ------------------------------------

    /**
     * repeat ON では「次の曲」が同じ曲になる。周回ごとに温めて引き取る形を 5 周ぶん回し、
     * 作った音源が<b>必ずちょうど 1 回だけ</b>閉じられることを数える。
     *
     * <p>漏れ (0 回) なら周回ごとにストリームが積み上がる。二重閉じ (2 回以上) なら
     * 死んだソースから読む経路ができる。C1 はどちらでも説明が付く形。
     */
    @Test
    void prefetchDoesNotLeakOrDoubleCloseAcrossLoops() {
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clockMs::get);
        final List<FakeSource> made = new ArrayList<>();
        final List<IAudioSource> claimed = new ArrayList<>();

        for (int lap = 0; lap < 5; lap++) {
            final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin(KEY, URL, 1L);
            if (ticket != null) {
                final FakeSource source = new FakeSource(lap);
                made.add(source);
                if (!prefetch.deliver(ticket, source)) {
                    source.close(); // 契約: 受け取られなければ呼び出し側が閉じる
                }
            }
            // 折り返し = offset 0 の再生要求。温めた分をここで引き取る。
            final IAudioSource warm = prefetch.claim(KEY, URL, 0L);
            if (warm != null) {
                claimed.add(warm);
                warm.close(); // 実際は DiscSoundInstance が抱えて最後に閉じる
            }
            advance(TRACK_MS);
        }

        for (final FakeSource source : made) {
            assertEquals(1, source.closeCount,
                    "先読みで作った音源はちょうど 1 回閉じられること: " + source);
        }
        assertNull(prefetch.slotUrl(KEY), "周回が終わったら先読み枠は空であること");
        assertFalse(claimed.isEmpty(), "1 周も引き取れていないなら、この形は C1 の経路を再現していない");
    }

    /**
     * 同じ URL を続けて温めようとしても枠は重ならない (repeat-single の形)。
     * 重なると周回ごとに枠と音源が増える。
     */
    @Test
    void repeatingTheSameUrlDoesNotStackSlots() {
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clockMs::get);

        final PlaybackPrefetch.Ticket<String> first = prefetch.begin(KEY, URL, 1L);
        assertNotNull(first, "最初の先読みは始まるはず");
        assertNull(prefetch.begin(KEY, URL, 1L), "同じ URL を抱えている間は重ねない");

        final FakeSource source = new FakeSource(0);
        assertTrue(prefetch.deliver(first, source));
        assertNull(prefetch.begin(KEY, URL, 1L), "完了済みでも同じ URL は重ねない");
        assertEquals(0, source.closeCount, "重ねなかっただけで閉じてはいけない");

        assertSame(source, prefetch.claim(KEY, URL, 0L));
    }

    /**
     * 折り返し以外 (シーク) で引き取ろうとした時は、温めた音源を<b>閉じてから</b> miss を返す。
     * 閉じ忘れると、シークのたびにストリームが 1 本ずつ残る。
     */
    @Test
    void claimWithSeekOffsetClosesTheWarmSource() {
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clockMs::get);
        final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin(KEY, URL, 1L);
        assertNotNull(ticket);
        final FakeSource source = new FakeSource(0);
        assertTrue(prefetch.deliver(ticket, source));

        assertNull(prefetch.claim(KEY, URL, 5_000L), "offset 付きの要求は先読みを使えない");
        assertEquals(1, source.closeCount, "使わないと決めた音源は閉じること");
        assertNull(prefetch.slotUrl(KEY));
    }

    /**
     * ロードが終わる前に取り消された枠へ音源が届いたら、{@code deliver} は受け取らない。
     * 受け取らなかった分は<b>呼び出し側が閉じる</b>のが契約。ここではその契約が
     * 守られる形になっていること (受け取り拒否が分かること) を固定する。
     */
    @Test
    void deliverToACancelledSlotIsRejectedSoTheCallerCanClose() {
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clockMs::get);
        final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin(KEY, URL, 1L);
        assertNotNull(ticket);

        prefetch.drop(KEY, "test: stopped before the load finished");

        final FakeSource late = new FakeSource(0);
        assertFalse(prefetch.deliver(ticket, late), "取り消し済みの枠は受け取らない");
        assertNull(prefetch.slotUrl(KEY));
    }

    @Test
    void endedTrackDoesNotReserveANewPrefetchSlot() {
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(() -> 0L);
        for (final long remainingMs : new long[] {-1L, 0L}) {
            assertNull(prefetch.begin(KEY, URL, remainingMs));
            assertNull(prefetch.slotUrl(KEY));
        }
        for (final long remainingMs : new long[] {1L, 15_000L}) {
            assertNotNull(prefetch.begin(KEY, URL, remainingMs));
            prefetch.drop(KEY);
        }
    }

    @Test
    void endedTrackDoesNotReplaceTheReadySourceAwaitingClaim() {
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(() -> 0L);
        final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin(KEY, URL, 1L);
        final FakeSource source = new FakeSource(1);
        assertTrue(prefetch.deliver(ticket, source));

        assertNull(prefetch.begin(KEY, URL + "-other", 0L));

        assertEquals(URL, prefetch.slotUrl(KEY));
        assertEquals(0, source.closeCount);
        assertSame(source, prefetch.claim(KEY, URL, 0L));
        source.close();
        assertEquals(1, source.closeCount);
    }

    @Test
    void endedTrackDoesNotInvalidateAnInFlightDelivery() {
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(() -> 0L);
        final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin(KEY, URL, 1L);

        assertNull(prefetch.begin(KEY, URL + "-other", -1L));

        final FakeSource source = new FakeSource(1);
        assertTrue(prefetch.deliver(ticket, source));
        assertSame(source, prefetch.claim(KEY, URL, 0L));
        assertEquals(0, source.closeCount);
        source.close();
    }

    @Test
    void endedTrackStillReapsAnExpiredSourceExactlyOnce() {
        final AtomicLong clockMs = new AtomicLong();
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clockMs::get);
        final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin(KEY, URL, 1L);
        final FakeSource source = new FakeSource(1);
        assertTrue(prefetch.deliver(ticket, source));
        clockMs.set(PlaybackPrefetch.EXPIRY_MS);

        assertNull(prefetch.begin(KEY, URL, 0L));
        assertNull(prefetch.slotUrl(KEY));
        assertEquals(1, source.closeCount);
        assertNull(prefetch.begin(KEY, URL, -1L));
        assertEquals(1, source.closeCount);
    }

    // -- 2. engine に拒否された時の後始末 ---------------------------------

    /** 受理する engine。 */
    private static final class AcceptingEngine implements SoundEngineAcceptance.Engine {

        @Override
        public boolean playAndConfirm() {
            return true;
        }

        @Override
        public boolean mutedOut() {
            return false;
        }
    }

    /** 拒否する engine。 */
    private static final class RejectingEngine implements SoundEngineAcceptance.Engine {

        private int playCount;

        @Override
        public boolean playAndConfirm() {
            playCount++;
            return false;
        }

        @Override
        public boolean mutedOut() {
            return false;
        }
    }

    /** 受理されたら後始末も報告も走らない。 */
    @Test
    void acceptedSoundRunsNoCleanup() {
        final int[] cleanups = {0};
        final List<PlaybackFailure> reported = new ArrayList<>();

        assertTrue(SoundEngineAcceptance.start(new AcceptingEngine(), () -> cleanups[0]++, reported::add));

        assertEquals(0, cleanups[0]);
        assertTrue(reported.isEmpty());
    }

    /**
     * 拒否されたら後始末が 1 回だけ走り、理由が 1 件だけ報告される。
     * loop は周回ごとにここを通るので、1 周につき 1 回を超えると積み上がる。
     */
    @Test
    void rejectedSoundCleansUpOncePerLap() {
        final int[] cleanups = {0};
        final List<PlaybackFailure> reported = new ArrayList<>();
        final RejectingEngine engine = new RejectingEngine();

        for (int lap = 0; lap < 4; lap++) {
            assertFalse(SoundEngineAcceptance.start(engine, () -> cleanups[0]++, reported::add));
        }

        assertEquals(4, engine.playCount);
        assertEquals(4, cleanups[0], "後始末は 1 周につきちょうど 1 回");
        assertEquals(4, reported.size(), "報告も 1 周につきちょうど 1 回");
    }

}
