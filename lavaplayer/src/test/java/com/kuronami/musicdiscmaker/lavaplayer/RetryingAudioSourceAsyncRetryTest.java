package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;

/**
 * <b>やり直しを本物のスレッドで決着させる。</b>
 *
 * <h2>同期 executor では原理的に再現しない</h2>
 * {@link RetryingAudioSourceTest} は理由待ちの executor に {@code Runnable::run} を渡している。
 * すると {@code read} が飛行中の印を上げた直後にやり直しがその場で完走し、
 * 続く {@code inner != current} の判定が必ず真になる = <b>「やり直しが飛行中のまま終端を返す」
 * という本番の窓が一度も開かない</b>。実機の届け先は専用スレッド
 * ({@code music-disc-maker-fault-await}) なので、この窓こそが常態。
 *
 * <h2>再現する連鎖</h2>
 * <ol>
 *   <li>最初のソースが一音も返さず {@code -1} を返す</li>
 *   <li>{@code resolveSilentEnd} が別スレッドへ渡り、{@code read} はその場で終端を返す</li>
 *   <li>呼び出し元 ({@code LavaPlayerAudioStream#prefill}) が終端とみなす
 *       = {@code prebufferEnded} が立つ</li>
 *   <li>やり直しが成功して {@code inner} が入れ替わっても<b>二度と読まれない</b></li>
 *   <li>{@code LavaPlayerAudioStream#reportEnd} が理由の付いていない終端を見て
 *       {@code UNKNOWN / no audio} を出す</li>
 * </ol>
 *
 * <h2>3 と 5 をどう観測するか</h2>
 * どちらも {@code common} 側 ({@code LavaPlayerAudioStream}) で起きるが、あちらは MC の
 * {@code AudioStream} を実装しているのでこの層からは触れない。そこで
 * {@link #drive} が両者の判定をそのまま写す — {@code prefill} の
 * {@code if (n < 0) ended = true} と、{@code reportEnd} の
 * 「{@code playbackFault()} が null かつ PCM 0 バイト → {@code UNKNOWN / "no audio"}」。
 * 1・2・4 は assertion で直接見る。
 *
 * <h2>時間で決めない</h2>
 * 理由の可視化は {@link GatedSource} の掛け金で止めてあり、外すのは駆動側が
 * <b>その回の read の結果を確定させた後</b>。{@code sleep} で当てにいかないので、
 * 赤が揺れて意味を失うことがない。
 */
class RetryingAudioSourceAsyncRetryTest {

    /**
     * 理由待ちの上限。掛け金を外すまで待てる長さが要るので本番 (1.5 秒) より長く取る。
     * 掛け金は駆動側が即座に外すので、実時間としてはここまで走らない。
     */
    private static final long GRACE_MS = 5_000L;

    /** 駆動側が諦めるまでの時間 (ms)。{@code prefill} の予算 (10 秒) より短く。 */
    private static final long DRIVE_BUDGET_MS = 3_000L;

    private static final PlaybackFault BOT_CHECK =
            new PlaybackFault(FailureReason.BOT_CHECK, "This video requires login.");

    private final ExecutorService awaiter = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "test-fault-await");
        thread.setDaemon(true);
        return thread;
    });

    @AfterEach
    void shutdownAwaiter() {
        awaiter.shutdownNow();
    }

    /**
     * <b>やり直しが成功したなら、その音が上の層まで届くこと。</b>
     *
     * <p>直す前はここで {@code verdict} が {@code UNKNOWN / no audio} になる —
     * 開き直しは成功していて ({@code opened}/{@code rolls} が 1)、健全なソースが
     * {@code inner} に入っているのに、駆動側は既に終端を確定させているので一度も読まれない。
     */
    @Test
    void anAsyncRetryThatSucceedsMustReachThePlayer() throws InterruptedException {
        final CountDownLatch faultVisible = new CountDownLatch(1);
        final GatedSource broken = new GatedSource(BOT_CHECK, faultVisible);
        final CountDownLatch reopened = new CountDownLatch(1);
        final Opened opened = new Opened(reopened, new PayloadSource(new byte[] {1, 2, 3, 4}));
        final FakeYoutubeSession session = new FakeYoutubeSession(8);

        final RetryingAudioSource source = wrap(broken, opened, session);
        final Drive drive = drive(source, faultVisible);

        assertTrue(reopened.await(DRIVE_BUDGET_MS, TimeUnit.MILLISECONDS),
                "開き直しが走っていない (この連鎖の前提が崩れている)");
        assertEquals(1, opened.count.get(), "開き直しの回数が想定と違う");
        assertEquals(1, session.rolls, "セッションを入れ替えずに開き直している");

        assertEquals("audio", drive.verdict(),
                "やり直しが成功して inner は健全なソースに入れ替わっているのに、駆動側は"
                        + " 終端を確定させた後なので二度と読まない。上の層 (LavaPlayerAudioStream)"
                        + " から見ると理由の付かない無音の終端 = UNKNOWN / no audio になる");
        assertEquals(4, drive.bytes, "開き直した先の PCM が届いていない");
    }

    /**
     * <b>やり直せない失敗なら、その理由が終端に間に合うこと。</b>
     *
     * <p>直す前はここで理由が {@code null} のまま終端が確定する。実機ではこの瞬間に
     * {@code reportEnd} が走るので、利用者の画面に出るのは分類の消えた
     * {@code UNKNOWN / no audio} になる — 本当は {@code BOT_CHECK} で、画面には
     * 「ログインが要る動画」と出せたはずのもの。
     */
    @Test
    void aFaultThatCannotBeRetriedMustStillReachTheEndOfTheStream() throws InterruptedException {
        final CountDownLatch faultVisible = new CountDownLatch(1);
        final GatedSource broken = new GatedSource(BOT_CHECK, faultVisible);
        final Opened opened = new Opened(new CountDownLatch(1));
        final FakeYoutubeSession session = new FakeYoutubeSession(0); // 入れ替え不可 = やり直せない

        final RetryingAudioSource source = wrap(broken, opened, session);
        final Drive drive = drive(source, faultVisible);

        assertTrue(drive.ended, "終端に達していない (この連鎖の前提が崩れている)");
        assertEquals(FailureReason.BOT_CHECK, drive.endReason(),
                "終端が確定した瞬間に理由が relay されておらず、上の層 (LavaPlayerAudioStream)"
                        + " は理由の付かない終端として UNKNOWN / no audio を出す");
    }

    /**
     * {@code LavaPlayerAudioStream#prefill} の駆動をそのまま写す。
     *
     * <p>あちらは {@code n < 0} を見た瞬間に {@code prebufferEnded} を立て、{@code n == 0} は
     * 「まだ届いていないだけ」として刻んで粘る。終端を見た時の理由 ({@code reportEnd} が
     * その直後に読むもの) をその場で控えるのは、控えた後で掛け金を外すため —
     * 実機でも理由の到着は終端より後になる。
     */
    private Drive drive(RetryingAudioSource source, CountDownLatch faultVisible) {
        final Drive drive = new Drive();
        final byte[] buffer = new byte[16];
        final long deadline = System.currentTimeMillis() + DRIVE_BUDGET_MS;
        while (drive.bytes == 0 && System.currentTimeMillis() < deadline) {
            final int n = source.read(buffer, 0, buffer.length);
            if (n < 0) {
                drive.ended = true;
                drive.endFault = source.playbackFault(); // reportEnd がこの瞬間に見るもの
            }
            faultVisible.countDown(); // 理由が確定する (実機では終端の後に届く)
            if (drive.ended) {
                break;
            }
            if (n > 0) {
                drive.bytes += n;
                break;
            }
            try {
                Thread.sleep(20L); // prefill の IDLE_POLL_MS
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
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

    private RetryingAudioSource wrap(IAudioSource inner, Opened opened, YoutubeSession session) {
        return new RetryingAudioSource(inner, opened, session,
                reason -> MusicLoaderImpl.isRetryable(reason, false), 1, GRACE_MS,
                System::currentTimeMillis, awaiter);
    }

    /** 開き直しが済んだことを掛け金で知らせる口。 */
    private static final class Opened implements RetryingAudioSource.Opener {

        private final Deque<IAudioSource> queue = new ArrayDeque<>();
        private final CountDownLatch reopened;
        final AtomicInteger count = new AtomicInteger();

        Opened(CountDownLatch reopened, IAudioSource... sources) {
            this.reopened = reopened;
            queue.addAll(List.of(sources));
        }

        @Override
        public IAudioSource open() {
            count.incrementAndGet();
            final IAudioSource next = queue.poll();
            reopened.countDown();
            return next;
        }
    }

    /**
     * 一音も出さずに終わり、<b>掛け金が外れて初めて</b>理由を答えるソース。
     *
     * <p>lavaplayer は「ストリームは終わった」を例外イベントより先に公開する
     * ({@code PlaybackFaultRelay} の javadoc)。その順番を実時間でなく掛け金で作るので、
     * 駆動側が終端を見た瞬間には理由が必ず存在しない。
     */
    private static final class GatedSource implements IAudioSource {

        private final PlaybackFault fault;
        private final CountDownLatch visible;
        volatile boolean closed;

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
            closed = true;
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
