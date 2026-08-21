package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;

/**
 * 非同期側 (再生スレッドの中で落ちた失敗) のやり直しを固定する。
 *
 * <h2>この経路の何が難しいか</h2>
 * 解決に成功して再生が始まった後の失敗には<b>戻り値が無い</b> — 呼び出し側はとっくに返っている。
 * さらに lavaplayer は「ストリームは終わった」を例外イベントより<b>先に</b>公開するので、
 * {@code read} が {@code -1} を見た時点では理由がまだ存在しない (根拠は {@code PlaybackFaultRelay}
 * の javadoc)。ここの偽ソースはその順番をそのまま再現する ({@link FakeSource#faultDelayMs})。
 */
class RetryingAudioSourceTest {

    /** テストでの理由待ち上限 (本番は {@link RetryingAudioSource#DEFAULT_GRACE_MS})。 */
    private static final long GRACE_MS = 400L;

    private static final PlaybackFault BOT_CHECK =
            new PlaybackFault(FailureReason.BOT_CHECK, "This video requires login.");

    /**
     * <b>これが直したかった挙動。</b> 再生スレッドで落ちたら、セッションを入れ替えて開き直し、
     * 上の層には何事もなかったように PCM が続くこと。
     */
    @Test
    void playbackFailureIsRetriedWithAFreshSessionAndAudioResumes() {
        // 1 本目は 1 バイトも出さずに落ちる。理由は終端より 100ms 遅れて確定する (実機と同じ順)。
        final FakeSource broken = new FakeSource(new byte[0], BOT_CHECK, 100L);
        final FakeSource healthy = new FakeSource(new byte[] {1, 2, 3, 4}, null, 0L);
        final FakeYoutubeSession session = new FakeYoutubeSession(8);
        final Opened opened = new Opened(healthy);

        final RetryingAudioSource source = wrap(broken, opened, session, 1);
        final AtomicReference<PlaybackFault> reported = new AtomicReference<>();
        source.onPlaybackFault(reported::set);

        final byte[] buffer = new byte[16];
        assertEquals(4, source.read(buffer, 0, buffer.length), "開き直した先の PCM が返っていない");
        assertEquals(1, session.rolls, "セッションを入れ替えずに開き直している");
        assertEquals(1, opened.count.get(), "開き直しの回数が想定と違う");
        assertTrue(broken.closed, "落ちた側のソースを閉じていない");
        assertNull(reported.get(), "やり直しで直ったのに利用者へ失敗を出している");
        assertNull(source.playbackFault(), "回復したのに失敗が残っている");
    }

    /**
     * <b>対照。</b> セッションを入れ替えられないなら開き直さないこと。
     *
     * <p>入れ替え無しの再試行は実測で回復 0/10。開き直しても同じ visitorId を使うだけなので、
     * 無駄に待たせず理由を出す方が正しい。
     */
    @Test
    void withoutARollTheFaultIsReportedInsteadOfRetried() {
        final FakeSource broken = new FakeSource(new byte[0], BOT_CHECK, 0L);
        final FakeYoutubeSession session = new FakeYoutubeSession(0); // 入れ替え不可
        final Opened opened = new Opened(new FakeSource(new byte[] {9}, null, 0L));

        final RetryingAudioSource source = wrap(broken, opened, session, 1);
        final AtomicReference<PlaybackFault> reported = new AtomicReference<>();
        source.onPlaybackFault(reported::set);

        assertEquals(-1, source.read(new byte[16], 0, 16), "終端を返していない");
        assertEquals(0, opened.count.get(), "入れ替えられないのに開き直している");
        assertNotNull(reported.get(), "理由が利用者まで届いていない");
        assertEquals(FailureReason.BOT_CHECK, reported.get().reason(), "分類が失われている");
    }

    /** 上限を使い切ったら、分類つきの失敗を届けて終わること。 */
    @Test
    void exhaustedRetriesDeliverTheClassifiedFault() {
        final FakeSource broken = new FakeSource(new byte[0], BOT_CHECK, 0L);
        final FakeSource alsoBroken = new FakeSource(new byte[0], BOT_CHECK, 0L);
        final FakeYoutubeSession session = new FakeYoutubeSession(8);
        final Opened opened = new Opened(alsoBroken);

        final RetryingAudioSource source = wrap(broken, opened, session, 1); // やり直しは 1 回まで
        final AtomicReference<PlaybackFault> reported = new AtomicReference<>();
        source.onPlaybackFault(reported::set);

        assertEquals(-1, source.read(new byte[16], 0, 16));
        assertEquals(1, opened.count.get(), "上限を超えて開き直している");
        assertEquals(1, session.rolls, "入れ替えの回数が上限と合わない");
        assertNotNull(reported.get(), "上限を使い切った後に理由が出ていない");
        assertEquals(FailureReason.BOT_CHECK, reported.get().reason());
    }

    /** 既に鳴っていた曲は開き直さないこと (頭から鳴らし直す方が害が大きい)。 */
    @Test
    void aFaultAfterAudioHasPlayedIsNotRetried() {
        final FakeSource broken = new FakeSource(new byte[] {1, 2}, BOT_CHECK, 0L);
        final FakeYoutubeSession session = new FakeYoutubeSession(8);
        final Opened opened = new Opened(new FakeSource(new byte[] {7}, null, 0L));

        final RetryingAudioSource source = wrap(broken, opened, session, 1);
        final AtomicReference<PlaybackFault> reported = new AtomicReference<>();
        source.onPlaybackFault(reported::set);

        assertEquals(2, source.read(new byte[16], 0, 16), "最初の PCM が返っていない");
        assertEquals(-1, source.read(new byte[16], 0, 16), "終端を返していない");
        assertEquals(0, opened.count.get(), "途中まで鳴っていた曲を開き直している");
        assertEquals(0, session.rolls, "無駄にセッションを入れ替えている");
        assertEquals(FailureReason.BOT_CHECK, reported.get().reason(), "理由が届いていない");
    }

    /** 普通に鳴り終わっただけなら、開き直しも報告もしないこと。 */
    @Test
    void aCleanEndIsNeitherRetriedNorReported() {
        final FakeSource finished = new FakeSource(new byte[] {1, 2, 3}, null, 0L);
        final FakeYoutubeSession session = new FakeYoutubeSession(8);
        final Opened opened = new Opened(new FakeSource(new byte[] {9}, null, 0L));

        final RetryingAudioSource source = wrap(finished, opened, session, 1);
        final AtomicReference<PlaybackFault> reported = new AtomicReference<>();
        source.onPlaybackFault(reported::set);

        assertEquals(3, source.read(new byte[16], 0, 16));
        assertEquals(-1, source.read(new byte[16], 0, 16));
        assertEquals(0, opened.count.get(), "正常終了で開き直している");
        assertNull(reported.get(), "正常終了なのに失敗を報告している");
    }

    /** 開き直しそのものが失敗したら、その理由を届けること (握り潰さない)。 */
    @Test
    void aFailedReopenReportsItsOwnReason() {
        final FakeSource broken = new FakeSource(new byte[0], BOT_CHECK, 0L);
        final FakeYoutubeSession session = new FakeYoutubeSession(8);
        final AtomicInteger opens = new AtomicInteger();

        final RetryingAudioSource source = new RetryingAudioSource(broken, () -> {
            opens.incrementAndGet();
            throw new ResolveException(FailureReason.PRIVATE_OR_REMOVED);
        }, session, reason -> MusicLoaderImpl.isRetryable(reason, false), 1, GRACE_MS,
                System::currentTimeMillis, Runnable::run);
        final AtomicReference<PlaybackFault> reported = new AtomicReference<>();
        source.onPlaybackFault(reported::set);

        assertEquals(-1, source.read(new byte[16], 0, 16));
        assertEquals(1, opens.get());
        assertEquals(FailureReason.PRIVATE_OR_REMOVED, reported.get().reason(),
                "開き直しの失敗理由が届いていない");
    }

    /**
     * <b>これが今回の回帰テスト。</b> 開けたのに 1 バイトも鳴らずに終わったら、理由が付いて
     * いなくても失敗として上げること。
     *
     * <p>従来は「理由なしの終端 = 最後まで鳴った」と一括りにしていたので、この経路は
     * 利用者にもログにも何も出ないまま無音で終わっていた。
     */
    @Test
    void aStreamThatEndsWithoutEverProducingAudioIsReportedAsAFailure() {
        final FakeSource empty = new FakeSource(new byte[0], null, 0L);
        final FakeYoutubeSession session = new FakeYoutubeSession(8);
        final RetryingAudioSource source = wrap(empty, new Opened(), session, 1);
        final AtomicReference<PlaybackFault> reported = new AtomicReference<>();
        source.onPlaybackFault(reported::set);

        assertEquals(-1, source.read(new byte[16], 0, 16), "終端を返していない");
        assertNotNull(reported.get(), "一音も鳴らずに終わったのに何も出ていない (完全な無音)");
        assertEquals(FailureReason.UNKNOWN, reported.get().reason());
        assertTrue(reported.get().detail().contains("without audio"),
                "何が起きたか分からない detail: " + reported.get().detail());
    }

    /** <b>対照。</b> PCM を渡した後の理由なしの終端は「最後まで鳴った」なので何も出さないこと。 */
    @Test
    void aStreamThatPlayedToTheEndStaysSilent() {
        final FakeSource played = new FakeSource(new byte[] {1, 2, 3, 4}, null, 0L);
        final FakeYoutubeSession session = new FakeYoutubeSession(8);
        final RetryingAudioSource source = wrap(played, new Opened(), session, 1);
        final AtomicReference<PlaybackFault> reported = new AtomicReference<>();
        source.onPlaybackFault(reported::set);

        final byte[] buffer = new byte[16];
        assertEquals(4, source.read(buffer, 0, buffer.length));
        assertEquals(-1, source.read(buffer, 0, buffer.length));
        assertNull(reported.get(), "最後まで鳴ったのに失敗を出している");
    }

    /**
     * <b>対照。</b> 停止による終端を失敗に仕立てないこと。
     *
     * <p>{@code close} は理由の到着待ち ({@code graceMs}) の中でも起きうるので、そこで返る
     * {@code null} を「理由なしの終端」と同じに扱うと、ディスクを抜くたびに失敗が出る。
     */
    @Test
    void aDeliberateCloseIsNotTurnedIntoAFailure() {
        final FakeSource empty = new FakeSource(new byte[0], null, 0L);
        final FakeYoutubeSession session = new FakeYoutubeSession(8);
        final RetryingAudioSource source = wrap(empty, new Opened(), session, 1);
        final AtomicReference<PlaybackFault> reported = new AtomicReference<>();
        source.onPlaybackFault(reported::set);

        source.close();

        assertEquals(-1, source.read(new byte[16], 0, 16));
        assertNull(reported.get(), "停止したのに失敗として報告している");
    }

    /** 停止されたら、やり直さずに中のソースを閉じること。 */
    @Test
    void closingStopsTheRetryAndClosesTheInnerSource() {
        final FakeSource broken = new FakeSource(new byte[0], BOT_CHECK, 0L);
        final FakeYoutubeSession session = new FakeYoutubeSession(8);
        final Opened opened = new Opened(new FakeSource(new byte[] {5}, null, 0L));

        final RetryingAudioSource source = wrap(broken, opened, session, 1);
        source.close();

        assertTrue(broken.closed, "中のソースを閉じていない");
        assertEquals(-1, source.read(new byte[16], 0, 16), "閉じた後に終端を返していない");
        assertEquals(0, opened.count.get(), "閉じた後に開き直している");
    }

    /**
     * <b>終端の後も引かれ続けても、理由待ちを払い直さないこと。</b>
     *
     * <p>MC は終端を見た後もチャンネルを手放すまで {@code updateStream} → {@code pumpBuffers}
     * を回し続ける。理由待ち ({@link RetryingAudioSource#DEFAULT_GRACE_MS}) を毎回払うと、
     * 正常に鳴り終わった曲 (理由は一生来ないので待ちは必ず上限まで走る) の終わりごとに
     * <b>Sound engine スレッドが数秒止まる</b> = 曲の切り替わりのフリーズ。
     *
     * <p>時計を引いた回数で見る。実時間で測ると環境で揺れるし、「払っていない」ことは
     * 「時計を見ていない」ことで十分に言える。
     */
    @Test
    void theEndOfTheStreamIsResolvedOnlyOnce() {
        // PCM を出したストリームの終端は、理由待ちを Sound engine で行わない。
        final FakeSource finished = new FakeSource(new byte[] {1, 2, 3, 4}, null, 0L);
        final AtomicInteger clockReads = new AtomicInteger();
        final RetryingAudioSource source = new RetryingAudioSource(finished, new Opened(),
                new FakeYoutubeSession(8), reason -> MusicLoaderImpl.isRetryable(reason, false),
                1, GRACE_MS, () -> {
                    clockReads.incrementAndGet();
                    return System.currentTimeMillis();
                }, command -> fail("PCM 読み取りの終端で理由待ちを実行してはいけない"));

        final byte[] buffer = new byte[16];
        assertEquals(4, source.read(buffer, 0, buffer.length), "最初の PCM が返っていない");
        assertEquals(-1, source.read(buffer, 0, buffer.length), "終端を返していない");

        final int afterFirstEnd = clockReads.get();
        assertEquals(0, afterFirstEnd, "終端の read が理由待ちをしてはいけない");

        for (int i = 0; i < 3; i++) {
            assertEquals(-1, source.read(buffer, 0, buffer.length), "終端の後に -1 以外を返している");
        }
        assertEquals(afterFirstEnd, clockReads.get(),
                "終端の後の read が理由待ちを払い直している"
                        + " (MC は終端後もチャンネルを手放すまで引き続けるので、曲の終わりごとに"
                        + " Sound engine スレッドが上限ぶん止まる)");
    }

    @Test
    void theEndOfTheStreamDoesNotWaitForAReason() {
        final AtomicInteger clockReads = new AtomicInteger();
        final RetryingAudioSource source = new RetryingAudioSource(
                new FakeSource(new byte[] {1, 2, 3, 4}, null, 0L), new Opened(),
                new FakeYoutubeSession(8), reason -> MusicLoaderImpl.isRetryable(reason, false),
                1, GRACE_MS, () -> {
                    clockReads.incrementAndGet();
                    return System.currentTimeMillis();
                }, command -> fail("PCM read must not await a terminal reason"));

        final byte[] buffer = new byte[16];
        assertEquals(4, source.read(buffer, 0, buffer.length));
        assertEquals(-1, source.read(buffer, 0, buffer.length));
        assertEquals(0, clockReads.get(), "terminal read must not consult the clock");

        for (int i = 0; i < 3; i++) {
            assertEquals(-1, source.read(buffer, 0, buffer.length));
        }
        assertEquals(0, clockReads.get(), "post-terminal reads must not consult the clock");
    }

    private static RetryingAudioSource wrap(IAudioSource inner, Opened opened,
            YoutubeSession session, int maxRetries) {
        return new RetryingAudioSource(inner, opened, session,
                reason -> MusicLoaderImpl.isRetryable(reason, false), maxRetries, GRACE_MS,
                System::currentTimeMillis, Runnable::run);
    }

    /** 順番に返す開き直し口 (呼ばれた回数を数える)。 */
    private static final class Opened implements RetryingAudioSource.Opener {

        private final Deque<IAudioSource> queue = new ArrayDeque<>();
        private final AtomicInteger count = new AtomicInteger();

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
     * 決まった PCM を出してから終わる偽ソース。
     *
     * <p>{@code faultDelayMs} が実機の順番を作る — <b>終端を返してから</b>その時間が経って初めて
     * {@link #playbackFault()} が理由を答える。0 でも「終端が先」は変わらない。
     */
    private static final class FakeSource implements IAudioSource {

        private final byte[] payload;
        private final PlaybackFault fault;
        private final long faultDelayMs;

        private int pos;
        private long endedAtMs;
        boolean closed;

        FakeSource(byte[] payload, PlaybackFault fault, long faultDelayMs) {
            this.payload = payload;
            this.fault = fault;
            this.faultDelayMs = faultDelayMs;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            if (closed) {
                return -1;
            }
            if (pos < payload.length) {
                final int n = Math.min(len, payload.length - pos);
                System.arraycopy(payload, pos, dst, off, n);
                pos += n;
                return n;
            }
            if (endedAtMs == 0L) {
                endedAtMs = System.currentTimeMillis();
            }
            return -1;
        }

        @Override
        public PlaybackFault playbackFault() {
            if (fault == null || endedAtMs == 0L) {
                return null;
            }
            return System.currentTimeMillis() - endedAtMs >= faultDelayMs ? fault : null;
        }

        @Override
        public void onPlaybackFault(Consumer<PlaybackFault> sink) {
            // 包む側は pull で見に来る (この経路は使わない)
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
}
