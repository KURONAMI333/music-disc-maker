package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
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
 * <b>世代 N のやり直しの後始末に、世代 N+1 のやり直しの印を下ろさせない。</b>
 *
 * <h2>踏む並び</h2>
 * <ol>
 *   <li>世代 N のやり直しが {@code inner} を N+1 (開き直した新ソース) へ入れ替える</li>
 *   <li><b>まだ N の {@code finally} は走っていない</b></li>
 *   <li>read スレッドが {@code inner == N+1} を観測し、N+1 に対するやり直しを投入する
 *       ({@code resolverDispatchedFor} は<b>ソース単位</b>の 1 回性なので、これは正しく通る)</li>
 *   <li>ここで N の {@code finally} が飛行中の印を下ろす — <b>自分が下ろすべきでない印</b></li>
 *   <li>read スレッドは「飛行中ではない」と読むので、{@code 0} ではなく<b>終端</b>へ倒れる</li>
 *   <li>N+1 のやり直しが理由を relay する前に {@code -1} が呼び出し側へ出る</li>
 * </ol>
 *
 * <h2>合格の見方</h2>
 * <b>N+1 の分類された理由が呼び出し側へ届くこと</b>。{@code -1} が早く出ないことだけを見るのは弱い —
 * 症状は「やり直しは走ったのに理由が {@code UNKNOWN} になる」なので、<b>正しい理由が出ること</b>を見る。
 * そこで N と N+1 に<b>別の {@link FailureReason}</b> を持たせ、届いたのが N+1 のものだと分かるようにした
 * (N = {@code BOT_CHECK} / N+1 = {@code AGE_RESTRICTED})。直す前の答えは
 * {@code UNKNOWN / no audio} = {@code LavaPlayerAudioStream#reportEnd} が理由の付かない終端に出す答え。
 *
 * <h2>どこで並びを固定したか</h2>
 * <ol>
 *   <li><b>入れ替えの直後</b>: {@code afterReopen} の口で N のやり直しを止める。この窓の中で
 *       read スレッドを走らせる。入れ替えから {@code finally} までの間、製品コードは外の code を
 *       一切呼ばないので、この口が無いと並びは実時間頼みになる (詳細は
 *       {@code RetryingAudioSource#afterReopen})</li>
 *   <li><b>N+1 の投入の最中</b>: {@code faultAwaiter} は read スレッドの上で走る。<b>2 本目</b>が
 *       来た時に (a) 先に N を進ませ (b) N が {@code finally} まで完走するのを待ってから
 *       (c) 実際の executor へ渡す。read が飛行中の印を上げた<b>後</b>・戻り値を決める<b>前</b>に、
 *       N の後始末が必ず入る</li>
 *   <li>N の完走は executor を包んで数える — {@code command.run()} が返った後に掛け金を下ろすので、
 *       {@code finally} の後始末まで含めて済んでいる</li>
 *   <li>理由の可視化はどちらも掛け金で止めてある。外すのは駆動側が<b>その回の read の結果を
 *       確定させた後</b>なので、実機と同じ「終端が先・理由が後」になり、赤が揺れない</li>
 * </ol>
 *
 * <p>掛け金を下ろす順は (a) が (b) より先であること。逆にすると N は read スレッドの後ろで
 * 待たされ、read は N を待つので互いに詰まる。
 *
 * <h2>既存の test では踏めない</h2>
 * 同期 executor ({@link RetryingAudioSourceTest}) では、投入した瞬間にやり直しが完走するので
 * 2 本が重ならない。{@link RetryingAudioSourceStaleResolverTest} は本物のスレッドを使うが、
 * <b>N が完走してから</b> read を進めるので、やはり重ならない (あちらが固定しているのは
 * 「古いソース宛ての 2 本目を投げない」という別の錠前)。
 */
class RetryingAudioSourceOverlappingResolverTest {

    /** 理由待ちの上限。掛け金で止めるので実時間としてはここまで走らない。 */
    private static final long GRACE_MS = 5_000L;

    /** 掛け金を待つ上限 (ms)。配線を間違えた時に固まらせない。 */
    private static final long LATCH_BUDGET_MS = 5_000L;

    /** 駆動側が諦めるまでの時間 (ms)。 */
    private static final long DRIVE_BUDGET_MS = 3_000L;

    /** {@code prefill} の空振り間隔。 */
    private static final long IDLE_POLL_MS = 20L;

    /** 世代 N の理由。<b>やり直せる</b>ので開き直しが走る。 */
    private static final PlaybackFault BOT_CHECK =
            new PlaybackFault(FailureReason.BOT_CHECK, "This video requires login.");

    /** 世代 N+1 の理由。<b>N と別の分類</b>にして、届いたのが N+1 のものだと分かるようにする。 */
    private static final PlaybackFault AGE_RESTRICTED =
            new PlaybackFault(FailureReason.AGE_RESTRICTED, "Sign in to confirm your age.");

    private final ExecutorService awaiter = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "test-fault-await");
        thread.setDaemon(true);
        return thread;
    });

    /** 投げられたやり直しの本数。 */
    private final AtomicInteger dispatched = new AtomicInteger();

    /** 世代 N が {@code inner} を入れ替え、{@code afterReopen} の口で止まった。 */
    private final CountDownLatch swapped = new CountDownLatch(1);

    /** read スレッドが N+1 のやり直しを投入し、飛行中の印を上げ終わった。 */
    private final CountDownLatch secondDispatched = new CountDownLatch(1);

    /** 世代 N のやり直しが {@code finally} まで含めて完走した。 */
    private final CountDownLatch resolverNSettled = new CountDownLatch(1);

    /**
     * やり直しを数えながら本物のスレッドへ渡す。<b>2 本目だけ並びを固定する</b> —
     * read スレッドの上で「N を進ませ、N が完走するのを待つ」。
     */
    private final Executor pinningAwaiter = command -> {
        final int nth = dispatched.incrementAndGet();
        if (nth == 2) {
            secondDispatched.countDown();   // 先に下ろす (N を自分の後ろで待たせない)
            await(resolverNSettled, "世代 N のやり直しが完走しなかった");
        }
        awaiter.execute(() -> {
            try {
                command.run();
            } finally {
                if (nth == 1) {
                    resolverNSettled.countDown();
                }
            }
        });
    };

    @AfterEach
    void shutdownAwaiter() {
        awaiter.shutdownNow();
    }

    /**
     * <b>世代が重なっても、最後に落ちた世代の理由が呼び出し側へ届くこと。</b>
     *
     * <p>直す前はここで理由が {@code null} のまま終端が確定する。開き直しは成功していて
     * ({@code opened} が 1)、N+1 のやり直しも投げられている ({@code dispatched} が 2) のに、
     * N の後始末が N+1 の印を下ろすので {@code read} が {@code -1} を返してしまい、実機では
     * この瞬間に {@code reportEnd} が走って {@code UNKNOWN / no audio} が利用者の画面に出る。
     * 本当は {@code AGE_RESTRICTED} で、「年齢制限のある動画」と出せたはずのもの。
     */
    @Test
    @Timeout(60)
    void aSettlingResolverMustNotClearTheMarkOfTheNextGeneration() throws InterruptedException {
        final CountDownLatch reasonOfN = new CountDownLatch(1);
        final CountDownLatch reasonOfNext = new CountDownLatch(1);
        final GatedSource generationN = new GatedSource(BOT_CHECK, reasonOfN);
        final GatedSource generationNext = new GatedSource(AGE_RESTRICTED, reasonOfNext);
        final Opened opened = new Opened(generationNext);
        final FakeYoutubeSession session = new FakeYoutubeSession(8);

        final RetryingAudioSource source = new RetryingAudioSource(generationN, opened, session,
                reason -> MusicLoaderImpl.isRetryable(reason, false), 1, GRACE_MS,
                System::currentTimeMillis, pinningAwaiter,
                // 入れ替えの直後で N を止め、read スレッドが N+1 を投入するまで待たせる
                () -> {
                    swapped.countDown();
                    await(secondDispatched, "read スレッドが N+1 のやり直しを投入しなかった");
                });

        final byte[] buffer = new byte[16];

        // 1 回目: 一音も出ないまま終端 → N のやり直しが飛び、終端はまだ確定しない
        assertEquals(0, source.read(buffer, 0, buffer.length),
                "やり直しが飛行中なのに終端を返している (この連鎖の前提が崩れている)");

        // N の理由を見せる → N が開き直し、入れ替えた直後の口で止まる
        reasonOfN.countDown();
        assertTrue(swapped.await(LATCH_BUDGET_MS, TimeUnit.MILLISECONDS),
                "開き直しが inner を入れ替えていない (この連鎖の前提が崩れている)");
        assertEquals(1, opened.count.get(), "開き直しの回数が想定と違う");

        // 2 回目: current = N+1。投入の最中に N の finally が走る = 踏みたい並び
        final int n = source.read(buffer, 0, buffer.length);
        // reportEnd がこの瞬間に見るもの。N+1 の理由はまだ掛け金の向こう = 実機と同じ順
        final PlaybackFault atEnd = n < 0 ? source.playbackFault() : null;
        assertEquals(2, dispatched.get(),
                "N+1 に対するやり直しが投げられていない (この連鎖の前提が崩れている)");

        // 理由が確定する (実機では終端の後に届く)
        reasonOfNext.countDown();
        final Drive drive = drive(source, buffer, n, atEnd);

        assertTrue(drive.ended, "終端に達していない");
        assertEquals(FailureReason.AGE_RESTRICTED, drive.endReason(),
                "世代 N の後始末が世代 N+1 の飛行中の印を下ろしたので、read は N+1 の理由が"
                        + " relay される前に終端を返した。上の層 (LavaPlayerAudioStream) が見るのは"
                        + " 理由の付かない終端 = " + drive.verdict()
                        + " で、分類のついた理由を出せたはずの場面で UNKNOWN / no audio になる");
    }

    /** {@code prefill} の粘りを写して、決着が付くまで駆動する。 */
    private Drive drive(RetryingAudioSource source, byte[] buffer, int first, PlaybackFault atEnd)
            throws InterruptedException {
        final Drive drive = new Drive();
        if (first < 0) {
            drive.ended = true;
            drive.endFault = atEnd;
            return drive;
        }
        final long deadline = System.currentTimeMillis() + DRIVE_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
            final int n = source.read(buffer, 0, buffer.length);
            if (n < 0) {
                drive.ended = true;
                drive.endFault = source.playbackFault();
                break;
            }
            if (n > 0) {
                drive.bytes += n;
                break;
            }
            Thread.sleep(IDLE_POLL_MS);
        }
        return drive;
    }

    /** {@code prefill} + {@code reportEnd} が到達する状態。 */
    private static final class Drive {

        boolean ended;
        PlaybackFault endFault;
        int bytes;

        /** {@code reportEnd} の分岐をそのまま写した答え。 */
        String verdict() {
            if (bytes > 0) {
                return "audio";
            }
            if (!ended) {
                return "still-waiting";
            }
            return endFault == null ? "UNKNOWN / no audio" : endFault.reason().name();
        }

        FailureReason endReason() {
            return endFault == null ? null : endFault.reason();
        }
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(LATCH_BUDGET_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, ex);
        }
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
     * 一音も出さずに終わり、<b>掛け金が外れて初めて</b>理由を答えるソース。
     *
     * <p>lavaplayer は「ストリームは終わった」を例外イベントより先に公開する
     * ({@code PlaybackFaultRelay} の javadoc)。その順番を実時間でなく掛け金で作る。押し出しの口
     * ({@code onPlaybackFault}) を空にしてあるのも同じ理由で、包む側が終端を見た瞬間に理由が
     * 既に relay されていると、この test が狙っている窓が消える。
     */
    private static final class GatedSource implements IAudioSource {

        private final PlaybackFault fault;
        private final CountDownLatch visible;

        GatedSource(PlaybackFault fault, CountDownLatch visible) {
            this.fault = fault;
            this.visible = visible;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
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
}
