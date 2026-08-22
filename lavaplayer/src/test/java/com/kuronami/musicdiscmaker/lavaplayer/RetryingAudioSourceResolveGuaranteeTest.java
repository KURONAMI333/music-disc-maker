package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;

/**
 * Sol のリリース前<b>再</b>レビュー ③ の固定 — 飛行中の印は<b>どう抜けても</b>下ろすこと。
 *
 * <h2>立ったまま残ると何が起きるか</h2>
 * {@code resolversInFlight} は「理由待ちと開き直しが飛行中」の本数で、残っている間 {@code read} は
 * {@code 0} (= 今この瞬間に出せる分が無い) を返す。決着が付けば {@code terminated} か
 * {@code inner} の入れ替えか {@code closed} のどれかが成立して窓が閉じる、というのが約束だった。
 *
 * <p>ところが下ろす代入は<b>正常に末尾まで到達した場合だけ</b>実行されていた。途中で例外が
 * 抜けると飛行中の印が残ったまま / {@code terminated=false} / {@code inner} 未交換のまま残り、
 * <b>後続の read が永久に {@code 0} を返し続ける</b> — MC は終端を受け取れないので、その音源は
 * source と channel を掴んだまま二度と手放されない。
 *
 * <p>抜け道は 2 つある。どちらも「通常稼働では起きにくい」だけで、コード上の保証は無かった:
 *
 * <ol>
 *   <li>{@code faultAwaiter.execute()} が同期例外を投げる (executor の飽和・シャットダウン)</li>
 *   <li>{@code relay.record()} が届け先 (sink) の例外を持ち帰る</li>
 * </ol>
 */
class RetryingAudioSourceResolveGuaranteeTest {

    private static final long GRACE_MS = 40L;
    private static final PlaybackFault BOT_CHECK =
            new PlaybackFault(FailureReason.BOT_CHECK, "This video requires login.");

    /** 1 バイトも出さずに終端だけ返すソース。理由は聞かれたら答えるが、push はしない。 */
    private static final class SilentSource implements IAudioSource {

        private final PlaybackFault fault;

        private SilentSource(PlaybackFault fault) {
            this.fault = fault;
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
        public PlaybackFault playbackFault() {
            return fault;
        }

        @Override
        public void onPlaybackFault(Consumer<PlaybackFault> sink) {
            // push はしない (pull 側 = awaitFault だけで拾わせる)
        }

        @Override
        public void close() {
        }
    }

    /** 入れ替える気のないセッション。 */
    private static final class FixedSession implements YoutubeSession {

        @Override
        public long generation() {
            return 1L;
        }

        @Override
        public boolean rollIfStale(long seen) {
            return true;
        }
    }

    /** 例外を飲んで読む (壊れた経路では read 自体が投げうるので、そこでテストを止めない)。 */
    private static int readQuietly(IAudioSource source) {
        try {
            return source.read(new byte[16], 0, 16);
        } catch (final Throwable expected) {
            return Integer.MIN_VALUE;
        }
    }

    private static RetryingAudioSource wrap(IAudioSource inner, Executor faultAwaiter) {
        return new RetryingAudioSource(inner, () -> null, new FixedSession(),
                reason -> false, // やり直さない = relay.record へまっすぐ落ちる
                1, GRACE_MS, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), faultAwaiter);
    }

    /**
     * 抜け道 1 — 理由待ちを投げる先が受け取らなかった場合。
     *
     * <p>{@code execute} が同期例外を投げると、飛行中の印は上げた直後に置き去りになる。
     * 以後 {@code read} は永久に {@code 0} を返し、MC は終端を受け取れない。
     */
    @Test
    void aRejectedFaultAwaiterStillLetsTheStreamReachItsEnd() {
        final RetryingAudioSource source = wrap(new SilentSource(BOT_CHECK), command -> {
            throw new RejectedExecutionException("fault awaiter is saturated");
        });

        readQuietly(source); // ここで投げてもよい。見たいのは「その後」
        final int after = readQuietly(source);

        assertNotEquals(0, after, "理由待ちを投げられなかった後、read が 0 を返し続けている"
                + " (飛行中の印が残ったまま = 終端に到達できない)");
        assertEquals(-1, after, "決着が付かないなら終端に倒すこと");
        for (int i = 0; i < 3; i++) {
            assertEquals(-1, readQuietly(source), "終端の後に -1 以外を返している");
        }
    }

    /**
     * 抜け道 2 — 届け先 (sink) が例外を投げた場合。
     *
     * <p>{@code relay.record()} は届け先をその場で呼ぶので、届け先が投げると
     * {@code resolveSilentEnd} は {@code terminated=true} にも飛行中の印を下ろす側にも
     * 到達しないまま抜ける。壊れているのは届け先であって、そのせいで音源が席を掴んだまま
     * 残ってよい理由は無い。
     */
    @Test
    void aThrowingFaultSinkStillLetsTheStreamReachItsEnd() {
        final RetryingAudioSource source = wrap(new SilentSource(BOT_CHECK), Runnable::run);
        source.onPlaybackFault(fault -> {
            throw new IllegalStateException("the sink is broken");
        });

        readQuietly(source);
        final int after = readQuietly(source);

        assertNotEquals(0, after, "届け先が投げた後、read が 0 を返し続けている"
                + " (飛行中の印が残ったまま = 終端に到達できない)");
        assertEquals(-1, after, "届け先が壊れていても終端には到達すること");
        for (int i = 0; i < 3; i++) {
            assertEquals(-1, readQuietly(source), "終端の後に -1 以外を返している");
        }
    }

    /** 理由も付かず届け先も壊れていない普通の経路は、これまで通り終端を返すこと。 */
    @Test
    void theOrdinarySilentEndIsUnchanged() {
        final RetryingAudioSource source = wrap(new SilentSource(null), Runnable::run);

        assertEquals(-1, readQuietly(source), "理由なしの終端はこれまで通り -1");
    }
}
