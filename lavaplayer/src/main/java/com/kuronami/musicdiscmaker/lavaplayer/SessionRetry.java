package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;

/**
 * 「失敗したら YouTube セッションを入れ替えて、もう一度だけやり直す」を上限つきで回す。
 *
 * <p>入れ替え無しの再試行は<b>効果ゼロ</b>と実測されている (対照 0/10。根拠は
 * {@link YoutubeSession} の javadoc)。だからこのクラスは再試行と入れ替えを分離せず、
 * 常にセットで進める。上限を使い切ったら最後の {@link ResolveException} をそのまま投げ、
 * 呼び出し側の既存の失敗分類 ({@code PlaybackFailure}) に載せる。
 */
final class SessionRetry {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRetry.class);

    private SessionRetry() {
    }

    /** 1 回分の試行。失敗は {@link ResolveException} で投げる。 */
    @FunctionalInterface
    interface Attempt<T> {
        T run();
    }

    /**
     * 再試行の上限。
     *
     * @param maxAttempts 試行回数の上限 (1 = 再試行しない)
     * @param deadlineMs  全体の壁時計上限。<b>次の試行を始めてよいかの判定にだけ</b>使う
     *                    (走り出した試行を途中で切らない = 個々の timeout は呼び出し側の責任)
     * @param backoffMs   試行の間に置く待ち時間
     */
    record Policy(int maxAttempts, long deadlineMs, long backoffMs) {
    }

    /**
     * 失敗するたびにセッションを入れ替えながら {@code attempt} を回す。
     *
     * @param attempt   1 回分の試行
     * @param session   visitorId の入れ替え口
     * @param policy    上限
     * @param retryable 再試行して意味のある失敗理由か (非公開・年齢制限のような確定的な失敗は
     *                  何度やっても同じなので即座に投げ返す)
     * @param clockMs   現在時刻 (テストが差し替える)
     * @return 成功した試行の戻り値
     * @throws ResolveException 上限・締切・入れ替えの余力切れで諦めた時 (理由は最後の失敗のもの)
     */
    static <T> T run(Attempt<T> attempt, YoutubeSession session, Policy policy,
            Predicate<FailureReason> retryable, LongSupplier clockMs) {
        final long startedMs = clockMs.getAsLong();
        ResolveException last = null;
        for (int i = 1; i <= Math.max(1, policy.maxAttempts()); i++) {
            // 試行を始める前の世代を握る。失敗した時「自分が見ていたセッションがまだ現役か」を
            // これで判定し、他スレッドが既に入れ替えていれば二重に入れ替えない。
            final long seen = session.generation();
            try {
                return attempt.run();
            } catch (final ResolveException ex) {
                last = ex;
                if (!retryable.test(ex.reason())) {
                    throw ex;
                }
                if (i >= policy.maxAttempts()) {
                    LOGGER.warn("Used up the retry limit ({} attempts): {}", policy.maxAttempts(), ex.reason());
                    break;
                }
                if (clockMs.getAsLong() - startedMs >= policy.deadlineMs()) {
                    LOGGER.warn("Hit the retry deadline ({}ms): {}", policy.deadlineMs(), ex.reason());
                    break;
                }
                if (!session.rollIfStale(seen)) {
                    // 入れ替えられないなら、もう一度試しても同じ visitorId で同じ失敗になる。
                    break;
                }
                sleep(policy.backoffMs());
            }
        }
        throw last == null ? new ResolveException(FailureReason.UNKNOWN) : last;
    }

    private static void sleep(long ms) {
        if (ms <= 0L) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
