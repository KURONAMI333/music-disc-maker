package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * Sol のリリース前レビュー(2026-08-22, {@code _handoff/PLAN_MDM_V230_B_WAVE.md} §16 Phase 4)で
 * 指摘された競合: 配達スレッド ({@link PlaybackPrefetch#deliver}) が枠を読んでから
 * {@code source} を代入するまでの間に、main thread の {@link PlaybackPrefetch#claim} /
 * {@link PlaybackPrefetch#drop} が同じ枠を map から外すと、{@code deliver} は
 * <b>既に外れた枠へ代入して {@code true} を返す</b> — 呼び出し側は「渡せた」と誤解して
 * 閉じないので、HTTP 接続・LavaPlayer・バッファが回収されない (リソース漏れ)。
 *
 * <p>{@link #concurrentDeliverAndClaimNeverLeaksOrDoubleCloses()} は本物のスレッド 2 本を
 * {@link CyclicBarrier} で同時に走らせ、{@code deliver} と {@code claim} を実際に競わせる。
 * 同期 executor ({@code Runnable::run}) に潰すと、外から枠を奪うタイミングを作れず
 * この経路を原理的に再現できない (Sol レビューでの一番重い学び)。
 */
class PlaybackPrefetchTest {

    /** close() の呼び出し回数を数えるだけの音源。 */
    private static final class FakeSource implements IAudioSource {

        final AtomicInteger closeCount = new AtomicInteger();

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

    @Test
    void deliverThenClaim_transfersOwnershipWithoutClosing() {
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(() -> 0L);
        final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin("k", "u");
        final FakeSource src = new FakeSource();

        assertTrue(prefetch.deliver(ticket, src));
        final IAudioSource claimed = prefetch.claim("k", "u", 0L);

        assertSame(src, claimed);
        assertEquals(0, src.closeCount.get());
    }

    /**
     * claim が in-flight miss で先に枠を外した後に deliver が届いた場合、deliver は受け取っては
     * いけない ({@code false})。呼び出し側 ({@code ClientPlaybackManager#prefetchNext}) は契約どおり
     * 自分で close する。
     */
    @Test
    void claimRemovesSlotFirst_deliverIsRejectedAndCallerMustClose() {
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(() -> 0L);
        final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin("k", "u");

        final IAudioSource missed = prefetch.claim("k", "u", 0L); // in-flight miss、枠は既に外れる
        assertNull(missed);

        final FakeSource src = new FakeSource();
        final boolean accepted = prefetch.deliver(ticket, src);

        assertFalse(accepted, "claim が既に外した枠へ deliver が代入してはいけない");
        assertEquals(0, src.closeCount.get(), "deliver 自身は呼び出し側の代わりに close しない");
        src.close(); // 契約どおり呼び出し側が閉じる
        assertEquals(1, src.closeCount.get());
    }

    /**
     * 本番と同じ形: ロードスレッドの {@code deliver} と main thread の {@code claim} を、
     * {@link CyclicBarrier} で毎回ぴったり同時に走らせて競わせる。2,000 回繰り返し、
     * どの回でも「漏れ (誰にも close されない)」も「二重 close」も起きないことを確認する。
     *
     * <p>修正 (map 上の実体への {@code computeIfPresent}) が効いていれば、スケジューリングの
     * 結果は必ず次の 2 通りのどちらかに落ち着く: (a) deliver が先に完了して枠へ代入 → claim が
     * それを掴む (source は生きたまま返る) / (b) claim が先に枠を外す → deliver は受理を拒否し、
     * 呼び出し側 (このテストでは代理として即 close) が閉じる。中間状態は原理的に存在しない。
     */
    @Test
    void concurrentDeliverAndClaimNeverLeaksOrDoubleCloses() throws Exception {
        final int iterations = 2_000;
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(System::currentTimeMillis);
                final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin("k", "u");
                final FakeSource src = new FakeSource();
                final CyclicBarrier barrier = new CyclicBarrier(2);

                final Future<Boolean> deliverFuture = pool.submit(() -> {
                    barrier.await();
                    return prefetch.deliver(ticket, src);
                });
                final Future<IAudioSource> claimFuture = pool.submit(() -> {
                    barrier.await();
                    return prefetch.claim("k", "u", 0L);
                });

                final boolean delivered = deliverFuture.get(5, TimeUnit.SECONDS);
                final IAudioSource claimed = claimFuture.get(5, TimeUnit.SECONDS);
                if (!delivered) {
                    src.close(); // ClientPlaybackManager#prefetchNext と同じ契約: 受け取れなければ自分で閉じる
                }

                if (claimed != null) {
                    assertSame(src, claimed, "iteration=" + i);
                    assertEquals(0, src.closeCount.get(),
                            "claim が受け取ったのに close されている (競合で奪われた) iteration=" + i);
                } else {
                    assertEquals(1, src.closeCount.get(),
                            "漏れ: 誰にも受け取られなかった source が close されていない iteration=" + i);
                }
                assertTrue(src.closeCount.get() <= 1, "二重 close iteration=" + i);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
