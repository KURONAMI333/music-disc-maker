package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFaultRelay;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;

/**
 * 再生終了を即時に返し、後から push される失敗理由を relay するソース。
 *
 * <h2>同期側で拾えない失敗が残る理由</h2>
 * client を 1 本だけにすると大半の失敗は解決の時点 ({@code loadTrackSync}) に出る
 * ので、そちらは {@link SessionRetry} が普通のループで包める。それでも「解決には成功し、
 * 再生スレッドの中で落ちる」経路は残る — この経路には戻り値が無く、呼び出し側はとっくに返っている。
 *
 * <p>そこで<b>やり直しの起点を {@link #read} に置く</b>。MC が PCM を引きに来る唯一の口がここで、
 * かつ「もう鳴らない」が確定するのもここ ({@code -1} を返した瞬間) だから、返す前に開き直せば
 * 上の層は何も知らずに済む。
 *
 * <h2>ストリームの終わりは失敗より先に来る</h2>
 * lavaplayer は再生が例外で落ちたとき「ストリームは終わった」を先に公開し、例外イベントを後から
 * 配る (根拠は {@code PlaybackFaultRelay} の javadoc に実バイトコードの順で書いてある)。つまり
 * {@code -1} を見た時点では理由がまだ存在しない。だから終端を見たら<b>少しだけ理由の到着を待つ</b>
 * ({@link #graceMs})。待っても来なければ、それは正常に鳴り終わっただけ。
 *
 * <p><b>この待ちは 1 本のストリームにつき 1 回だけ</b> ({@link #terminated})。MC は終端を見た後も
 * チャンネルを手放すまで引き続けるので、毎回払うと曲の終わりごとに Sound engine スレッドが
 * 数秒止まる。
 *
 * <h2>やり直さない場合</h2>
 * <ul>
 *   <li>既に PCM を 1 バイトでも渡した後 — 途中まで聴こえていた曲を頭から鳴らし直す方が害が大きい</li>
 *   <li>上限 ({@code maxRetries}) を使い切った</li>
 *   <li>確定的な失敗 (非公開・年齢制限・地域制限) — 何度開き直しても同じ</li>
 *   <li>セッションの入れ替えに余力が無い — 入れ替え無しの再試行は効果ゼロ (対照 0/10)</li>
 * </ul>
 * どの場合も理由を握り潰さず、届け先へそのまま流す (利用者の画面に分類つきで出る)。
 */
final class RetryingAudioSource implements IAudioSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetryingAudioSource.class);

    /** 終端を見てから理由の到着を待つ既定の上限。MC の streaming スレッドを塞ぐので短く。 */
    static final long DEFAULT_GRACE_MS = 1_500L;
    /** 理由の到着を見に行く間隔。 */
    private static final long POLL_MS = 20L;
    private static final ThreadFactory FAULT_AWAITER_THREADS = runnable -> {
        final Thread thread = new Thread(runnable, "music-disc-maker-fault-await");
        thread.setDaemon(true);
        return thread;
    };
    private static final Executor DEFAULT_FAULT_AWAITER =
            command -> FAULT_AWAITER_THREADS.newThread(command).start();

    /** 開き直し 1 回分。中で解決からやり直す (失敗は {@link ResolveException})。 */
    @FunctionalInterface
    interface Opener {
        IAudioSource open();
    }

    private final Opener opener;
    private final YoutubeSession session;
    private final Predicate<FailureReason> retryable;
    private final long graceMs;
    private final LongSupplier clockMs;
    private final Executor faultAwaiter;

    /** 届け先。<b>やり直しても駄目だった時だけ</b>ここへ流す。 */
    private final PlaybackFaultRelay relay = new PlaybackFaultRelay();

    private volatile IAudioSource inner;
    private volatile boolean emitted;
    private volatile boolean closed;
    /**
     * 終端を確定させた印。<b>一度 {@code -1} を返したら、以後は即座に {@code -1} を返す。</b>
     *
     * <p>これが無いと、終端を見た後も MC が引きに来るたびに {@link #awaitFault} を通り、
     * <b>1 回につき {@link #graceMs} をまるごと払う</b>。正常に鳴り終わった曲には理由が
     * 一生来ないので、この待ちは毎回上限まで走る。MC は終端後もチャンネルを手放すまで
     * {@code updateStream} → {@code pumpBuffers} を回し続けるため、<b>曲の終わりごとに
     * Sound engine スレッドが数秒止まる</b> = 切り替わりのフリーズ
     * (MDM_DECISIONS D28 の「1 曲につき 4 回・間隔 1.505s」は 1500ms + poll 20ms のこと)。
     */
    private volatile boolean terminated;
    /**
     * 理由待ちと開き直しが飛行中の印。<b>立っている間は終端を返さない</b> ({@code 0} を返す)。
     *
     * <p>{@link #resolveSilentEnd} は Sound engine の外で走るので、投げた時点では結果が無い。
     * そこで {@code -1} を返すと、呼び出し元は<b>やり直しの結果を見る前に終端を確定させる</b> —
     * {@code LavaPlayerAudioStream#prefill} は {@code prebufferEnded} を立て、開き直しが成功して
     * {@link #inner} が健全なソースに入れ替わっても二度と読まれない。届いた理由も
     * {@code reportEnd} には間に合わず、分類の消えた {@code UNKNOWN / no audio} だけが残る。
     *
     * <p>{@code 0} は「今この瞬間に出せる分が無い」であって終端ではない
     * ({@code LavaAudioSource} と同じ約束)。上の層はこれを待たずに扱える —
     * {@code prefill} は刻んで粘り、{@code LavaPlayerAudioStream#read} は残りを無音で埋めて返す。
     * <b>音声スレッドを掴まないまま、決着だけを待てる。</b>
     *
     * <p>この窓は必ず閉じる。{@link #resolveSilentEnd} を抜けた時点で「{@link #terminated} が
     * 立っている」「{@link #inner} が入れ替わっている」「{@link #closed} である」のいずれかが
     * 成立しており、長さは {@link #graceMs} + 開き直し 1 回 ({@code MusicLoaderImpl} の
     * {@code REOPEN_TIMEOUT_MS} = 10 秒) で頭打ちになる。
     */
    private volatile boolean resolvingEnd;
    private int retriesLeft;

    RetryingAudioSource(IAudioSource inner, Opener opener, YoutubeSession session,
            Predicate<FailureReason> retryable, int maxRetries, long graceMs, LongSupplier clockMs) {
        this(inner, opener, session, retryable, maxRetries, graceMs, clockMs, DEFAULT_FAULT_AWAITER);
    }

    RetryingAudioSource(IAudioSource inner, Opener opener, YoutubeSession session,
            Predicate<FailureReason> retryable, int maxRetries, long graceMs, LongSupplier clockMs,
            Executor faultAwaiter) {
        this.inner = inner;
        this.opener = opener;
        this.session = session;
        this.retryable = retryable;
        this.retriesLeft = maxRetries;
        this.graceMs = graceMs;
        this.clockMs = clockMs;
        this.faultAwaiter = faultAwaiter;
    }

    @Override
    public int read(byte[] dst, int off, int len) {
        if (terminated) {
            return -1;
        }
        while (true) {
            final IAudioSource current = inner;
            final int n = current.read(dst, off, len);
            if (n > 0) {
                emitted = true;
                return n;
            }
            if (n == 0) {
                return 0;
            }
            if (closed) {
                return terminal();
            }
            current.onPlaybackFault(relay::record);
            if (emitted) {
                final PlaybackFault immediateFault = current.playbackFault();
                if (immediateFault != null) {
                    relay.record(immediateFault);
                }
                return terminal();
            }
            if (!resolvingEnd) {
                resolvingEnd = true;
                faultAwaiter.execute(() -> resolveSilentEnd(current));
            }
            if (!terminated && inner != current) {
                continue;
            }
            if (!terminated && resolvingEnd) {
                return 0; // やり直しが飛行中。終端はまだ確定していない
            }
            return -1;
        }
    }

    /** 終端を確定させて {@code -1} を返す。 */
    private int terminal() {
        terminated = true;
        return -1;
    }

    /** 理由待ちと再オープンは Sound engine の外で行う。 */
    private void resolveSilentEnd(IAudioSource current) {
        final PlaybackFault fault = awaitFault(current);
        if (fault != null) {
            if (!canRetry(fault)) {
                relay.record(fault);
                terminated = true;
            } else {
                final PlaybackFault giveUp = reopen(fault);
                if (giveUp != null) {
                    relay.record(giveUp);
                    terminated = true;
                }
            }
        } else if (!closed) {
            endedWithoutReason();
            terminated = true;
        }
        resolvingEnd = false;
    }

    /**
     * 理由が付かないまま終端に達した時の答え。
     *
     * <p>PCM を 1 バイトでも渡していれば「最後まで鳴った」で正しい。渡していないなら違う —
     * <b>開けたのに一音も鳴らないまま終わった</b>のであって、理由が付いていないだけ。
     * 従来はこの 2 つを同じ {@code -1} に潰していたので、上の層は正常終了として扱い、
     * 利用者にもログにも何も出ないまま無音になっていた。
     *
     * <p>{@link #closed} を見るのは、{@link #awaitFault} が理由の到着を待っている間 (最大
     * {@link #graceMs}) に停止されうるから。その {@code null} は失敗ではなく停止の結果なので、
     * 失敗を捏造しない。
     */
    private void endedWithoutReason() {
        if (emitted || closed) {
            return;
        }
        LOGGER.warn("The stream ended without producing any audio and without reporting a reason");
        relay.record(new PlaybackFault(FailureReason.UNKNOWN, "stream ended without audio"));
    }

    /** やり直す価値があるか。 */
    private boolean canRetry(PlaybackFault fault) {
        return !emitted && !closed && retriesLeft > 0 && retryable.test(fault.reason());
    }

    /** セッションを入れ替えて開き直す。 */
    private PlaybackFault reopen(PlaybackFault fault) {
        retriesLeft--;
        final long seen = session.generation();
        if (!session.rollIfStale(seen)) {
            return fault;
        }
        closeQuietly(inner);
        try {
            final IAudioSource fresh = opener.open();
            if (fresh == null) {
                return fault;
            }
            inner = fresh;
            if (closed) {
                closeQuietly(fresh);
                return fault;
            }
            LOGGER.info("Playback died, so the YouTube session was rotated and the stream reopened ({})", fault.reason());
            return null;
        } catch (final ResolveException ex) {
            return new PlaybackFault(ex.reason(), "retry: " + ex.reason());
        } catch (final Throwable t) {
            LOGGER.warn("Failed to reopen the stream", t);
            return fault;
        }
    }

    /** 理由が確定するのを少しだけ待つ (終端の方が先に来るため)。 */
    private PlaybackFault awaitFault(IAudioSource current) {
        final long deadline = clockMs.getAsLong() + graceMs;
        while (true) {
            final PlaybackFault fault = current.playbackFault();
            if (fault != null) {
                return fault;
            }
            if (closed || clockMs.getAsLong() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return current.playbackFault();
            }
        }
    }

    private static void closeQuietly(IAudioSource source) {
        try {
            source.close();
        } catch (final Throwable ignored) {
            // 閉じ損ねても再生は止める
        }
    }

    @Override
    public PlaybackFault playbackFault() {
        return relay.fault();
    }

    @Override
    public void onPlaybackFault(Consumer<PlaybackFault> sink) {
        relay.sink(sink);
    }

    @Override
    public int sampleRate() {
        return inner.sampleRate();
    }

    @Override
    public int channels() {
        return inner.channels();
    }

    @Override
    public int bitsPerSample() {
        return inner.bitsPerSample();
    }

    @Override
    public boolean bigEndian() {
        return inner.bigEndian();
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(inner);
    }
}
