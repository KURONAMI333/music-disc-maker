package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;

/**
 * <b>入れ替わった後の健全なソースを、古いソース宛ての 2 本目のやり直しに殺させない。</b>
 *
 * <h2>並びを固定しないと踏めない</h2>
 * {@code read} は「やり直しを投げるか」を先に決め、「{@code inner} が入れ替わっていないか」を
 * <b>その後で</b>見る。だから <b>read が古いソースを掴んでいる間に、先行のやり直しが
 * 入れ替えと後始末を完走する</b>という並びが揃った時だけ、古いソース宛ての 2 本目が飛ぶ。
 *
 * <p>{@link RetryingAudioSourceAsyncRetryTest} も本物のスレッドを使うが、あちらは
 * この並びを固定していない — 駆動側が次の {@code read} に入る頃には先行のやり直しが
 * 既に終わっているのが普通で、その時は {@code inner} を最初から新しい方で拾うので
 * 2 本目は飛ばない。同期 executor ({@link RetryingAudioSourceTest}) では原理的に開かない。
 *
 * <h2>どこで並びを固定したか</h2>
 * <ol>
 *   <li>古いソースの {@code read} <b>2 回目</b>が、その中で理由を可視化し
 *       ({@code faultVisible})、<b>先行のやり直しが完走するまで戻らない</b>
 *       ({@code resolverSettled})。これで「read が古いソースを掴んだまま、入れ替えと
 *       {@code resolvingEnd=false} が済む」が実時間に依らず必ず成立する</li>
 *   <li>やり直しの完走は executor を包んで数える — {@code command.run()} が返った後に
 *       掛け金を下ろすので、{@code finally} の後始末まで含めて済んでいる</li>
 *   <li>2 本目が飛んだかどうかは、単一スレッドの executor へ空の仕事を投げて
 *       待つことで確定させる (FIFO なので、先に積まれた仕事は必ず終わっている)</li>
 * </ol>
 *
 * <h2>合格の見方</h2>
 * <b>入れ替わった健全なソースから実データが最後まで読めること</b>。2 本目は
 * {@code retriesLeft} を使い切った状態で古い失敗を拾うので {@code terminated} を立て、
 * <b>健全なソースが {@code inner} に入っているのに次の {@code read} が {@code -1} を返す</b> —
 * 利用者から見れば「やり直しは成功したのに無音のまま終わる」で、この版で直したはずの症状そのもの。
 * だから {@code terminated} が立っていないことではなく、<b>音が出ること</b>を見る。
 */
class RetryingAudioSourceStaleResolverTest {

    /** 理由待ちの上限。掛け金で止めるので実時間としてはここまで走らない。 */
    private static final long GRACE_MS = 5_000L;

    /** 掛け金を待つ上限 (ms)。配線を間違えた時に固まらせない。 */
    private static final long LATCH_BUDGET_MS = 5_000L;

    private static final PlaybackFault BOT_CHECK =
            new PlaybackFault(FailureReason.BOT_CHECK, "This video requires login.");

    /** 開き直した先が返す PCM。<b>1 回の read で渡り切らない長さ</b>にしてある。 */
    private static final byte[] PAYLOAD = {1, 2, 3, 4, 5, 6, 7, 8};

    /** 1 回の read で引く量。{@link #PAYLOAD} の半分。 */
    private static final int CHUNK = 4;

    private final ExecutorService awaiter = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "test-fault-await");
        thread.setDaemon(true);
        return thread;
    });

    /** 投げられたやり直しの本数。 */
    private final AtomicInteger dispatched = new AtomicInteger();

    /** 先行のやり直しが {@code finally} まで含めて完走したことを知らせる掛け金。 */
    private final CountDownLatch resolverSettled = new CountDownLatch(1);

    /** {@code resolveSilentEnd} を数えながら本物のスレッドへ渡す。 */
    private final Executor countingAwaiter = command -> {
        dispatched.incrementAndGet();
        awaiter.execute(() -> {
            try {
                command.run();
            } finally {
                resolverSettled.countDown();
            }
        });
    };

    @AfterEach
    void shutdownAwaiter() {
        awaiter.shutdownNow();
    }

    /**
     * <b>再オープンが成功した後は、その音が最後まで出ること。</b>
     *
     * <p>直す前はここで最初の 1 かたまりだけが届き、続きが {@code -1} になる。古いソース宛ての
     * 2 本目が {@code terminated} を立てるからで、{@code inner} には健全なソースが入ったままになる。
     */
    @Test
    @Timeout(30)
    void aResolverForTheReplacedSourceMustNotKillTheFreshOne()
            throws InterruptedException, ExecutionException {
        final CountDownLatch faultVisible = new CountDownLatch(1);
        // 2 回目の read で「理由を見せてから、先行のやり直しの完走を待つ」= 並びの固定点
        final StallingSource broken = new StallingSource(BOT_CHECK, faultVisible, resolverSettled);
        final Opened opened = new Opened(new PayloadSource(PAYLOAD));
        final FakeYoutubeSession session = new FakeYoutubeSession(8);
        final RetryingAudioSource source = new RetryingAudioSource(broken, opened, session,
                reason -> MusicLoaderImpl.isRetryable(reason, false), 1, GRACE_MS,
                System::currentTimeMillis, countingAwaiter);

        final byte[] buffer = new byte[CHUNK];

        // 1 回目: 一音も出ないまま終端 → やり直しが飛び、終端はまだ確定しない (0 を返す)
        assertEquals(0, source.read(buffer, 0, buffer.length),
                "やり直しが飛行中なのに終端を返している (この連鎖の前提が崩れている)");

        // 2 回目: 古いソースを掴んだまま、先行のやり直しが入れ替えと後始末を完走する
        int total = read(source, buffer);
        assertEquals(1, opened.count.get(), "開き直しが走っていない (前提が崩れている)");
        assertTrue(total > 0, "入れ替わった健全なソースから 1 バイトも読めていない");

        // 積まれている仕事を全部片付けさせる (2 本目が飛んでいたら、ここで決着が付く)
        awaiter.submit(() -> { }).get();

        // 3 回目以降: 健全なソースの残りが最後まで出ること
        while (total < PAYLOAD.length) {
            final int n = read(source, buffer);
            assertTrue(n > 0, "入れ替わった健全なソースが inner に入っているのに音が出ない"
                    + " (古いソース宛ての 2 本目のやり直しが終端を確定させた)。"
                    + " 読めたのは " + total + " / " + PAYLOAD.length + " バイト");
            total += n;
        }
        assertEquals(PAYLOAD.length, total, "開き直した先の PCM が最後まで届いていない");
        assertEquals(1, dispatched.get(),
                "同じソースに対してやり直しが 2 本投げられている");
    }

    private static int read(RetryingAudioSource source, byte[] buffer) {
        final int n = source.read(buffer, 0, buffer.length);
        return Math.max(n, 0);
    }

    /** 開き直しの口。 */
    private static final class Opened implements RetryingAudioSource.Opener {

        private final Deque<IAudioSource> queue = new ArrayDeque<>();
        final AtomicInteger count = new AtomicInteger();

        Opened(IAudioSource... sources) {
            queue.addAll(List.of(sources));
        }

        @Override
        public IAudioSource open() {
            count.incrementAndGet();
            return queue.poll();
        }
    }

    /**
     * 一音も出さずに終わるソース。<b>2 回目の {@code read} で並びを固定する</b> —
     * 理由を見せて先行のやり直しを進ませ、それが完走するまで戻らない。
     *
     * <p>戻った時点で {@code inner} は新しいソースに入れ替わり、{@code resolvingEnd} も
     * 下りている。包む側はまだ<b>古いこのソースを {@code current} として掴んでいる</b>。
     */
    private static final class StallingSource implements IAudioSource {

        private final PlaybackFault fault;
        private final CountDownLatch visible;
        private final CountDownLatch settled;
        private final AtomicInteger reads = new AtomicInteger();

        StallingSource(PlaybackFault fault, CountDownLatch visible, CountDownLatch settled) {
            this.fault = fault;
            this.visible = visible;
            this.settled = settled;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            if (reads.incrementAndGet() == 2) {
                visible.countDown(); // ここで初めて理由が見える = 先行のやり直しが進む
                try {
                    if (!settled.await(LATCH_BUDGET_MS, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("先行のやり直しが完走しなかった");
                    }
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return -1;
        }

        @Override
        public PlaybackFault playbackFault() {
            return visible.getCount() == 0 ? fault : null;
        }

        @Override
        public void onPlaybackFault(Consumer<PlaybackFault> sink) {
            // 包む側は pull で見に来る
        }

        @Override
        public int sampleRate() {
            return 48000;
        }

        @Override
        public int channels() {
            return 1;
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
        public void close() {
            // 抱えていない
        }
    }

    /** 決まった PCM を出してから終わる健全なソース。 */
    private static final class PayloadSource implements IAudioSource {

        private final byte[] payload;
        private int pos;

        PayloadSource(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            if (pos >= payload.length) {
                return -1;
            }
            final int n = Math.min(len, payload.length - pos);
            System.arraycopy(payload, pos, dst, off, n);
            pos += n;
            return n;
        }

        @Override
        public PlaybackFault playbackFault() {
            return null;
        }

        @Override
        public void onPlaybackFault(Consumer<PlaybackFault> sink) {
            // 失敗しないソース
        }

        @Override
        public int sampleRate() {
            return 48000;
        }

        @Override
        public int channels() {
            return 1;
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
        public void close() {
            // 何も抱えていない
        }
    }
}
