package com.kuronami.musicdiscmaker.lavaplayer;

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
 * 再生が<b>始まった後</b>に落ちた失敗を、セッションを入れ替えて開き直すことで拾い直すソース。
 *
 * <h2>同期側で拾えない失敗が残る理由</h2>
 * client を {@code AndroidVr} 単独にすると大半の失敗は解決の時点 ({@code loadTrackSync}) に出る
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

    /** 届け先。<b>やり直しても駄目だった時だけ</b>ここへ流す。 */
    private final PlaybackFaultRelay relay = new PlaybackFaultRelay();

    private volatile IAudioSource inner;
    private volatile boolean emitted;
    private volatile boolean closed;
    private int retriesLeft;

    RetryingAudioSource(IAudioSource inner, Opener opener, YoutubeSession session,
            Predicate<FailureReason> retryable, int maxRetries, long graceMs, LongSupplier clockMs) {
        this.inner = inner;
        this.opener = opener;
        this.session = session;
        this.retryable = retryable;
        this.retriesLeft = maxRetries;
        this.graceMs = graceMs;
        this.clockMs = clockMs;
    }

    @Override
    public int read(byte[] dst, int off, int len) {
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
                return -1;
            }
            final PlaybackFault fault = awaitFault(current);
            if (fault == null) {
                return -1; // 理由が付かないまま終わった = 最後まで鳴った
            }
            if (!canRetry(fault)) {
                relay.record(fault);
                return -1;
            }
            final PlaybackFault giveUp = reopen(fault);
            if (giveUp != null) {
                relay.record(giveUp);
                return -1;
            }
            // 開き直せた → ループ先頭から新しいソースを読む (上の層は終端を見ない)
        }
    }

    /** やり直す価値があるか。 */
    private boolean canRetry(PlaybackFault fault) {
        return !emitted && !closed && retriesLeft > 0 && retryable.test(fault.reason());
    }

    /**
     * セッションを入れ替えて開き直す。
     *
     * @param fault やり直しの引き金になった失敗
     * @return 開き直せたら {@code null}、諦めるなら届け先へ流すべき失敗
     */
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
                // 開き直している間に停止された。開いたばかりのものを閉じて終わる。
                closeQuietly(fresh);
                return fault;
            }
            LOGGER.info("再生が落ちたので YouTube セッションを入れ替えて開き直した ({})", fault.reason());
            return null;
        } catch (final ResolveException ex) {
            return new PlaybackFault(ex.reason(), "retry: " + ex.reason());
        } catch (final Throwable t) {
            LOGGER.warn("開き直しに失敗", t);
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
