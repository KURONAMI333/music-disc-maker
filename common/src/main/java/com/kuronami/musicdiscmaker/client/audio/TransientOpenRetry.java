package com.kuronami.musicdiscmaker.client.audio;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * 一時的な失敗で開けなかった既存の盤を、間隔を置いて開き直す純ロジック。
 *
 * <h2>なぜ要るか</h2>
 * 制作時には鳴っていた盤の URL が、後から YouTube に弾かれることがある。bot 判定は
 * <b>同じ URL でも時機と visitorId で結果が変わる</b>ので、1 回の失敗で確定させると
 * 盤が永久に鳴らない。ローダー側 ({@code SessionRetry}) も visitorId を入れ替えて
 * 何度か試すが、それを使い切った後に<b>時間を置いて</b>もう一度試す層がここ。
 *
 * <h2>なぜ client 側なのか</h2>
 * キャンセル (盤を抜いた・別の曲に差し替えた) を {@code PlaybackSessions#isLive} で
 * そのまま見られること、待つのは再生用の pool thread で MC の main thread を塞がないこと。
 * ローダーには「この再生はまだ要るか」を聞く口が無く、待っている間に抜かれた盤の URL を
 * 開き続けてしまう。
 *
 * <h2>待ち時間</h2>
 * {@code YoutubeTokenSession} の visitorId は {@code REFILL_INTERVAL_MS} ごとに 1 回分
 * 戻る。それより短い間隔で叩き直しても同じ visitorId で同じ失敗をするだけなので、
 * 間隔はその補充間隔に合わせてある。
 *
 * <p>{@code Minecraft} を掴まないので headless テストに載る ({@link PlaybackFailure} と
 * 同じ seam の切り方)。時計と待ち方は引数で差し替えられる。
 */
final class TransientOpenRetry {

    private TransientOpenRetry() {
    }

    /** 待つ手段。テストでは時計を進めるだけの実装に差し替える。 */
    @FunctionalInterface
    interface Sleeper {

        /**
         * @param ms 待つ時間
         * @return 待てたら {@code true}。中断されたら {@code false} (呼び出し側は諦める)
         */
        boolean sleep(long ms);
    }

    /** 1 回開く試行。 */
    @FunctionalInterface
    interface Attempt {

        /** @return 開けたソース、または失敗 */
        Outcome attempt();
    }

    /**
     * 再試行の上限。
     *
     * @param budgetMs    最初の試行から数えた全体の予算 (ms)
     * @param delayMs     試行と試行の間に置く時間 (ms)
     * @param maxAttempts 試行回数の上限 (最初の 1 回を含む)
     */
    record Policy(long budgetMs, long delayMs, int maxAttempts) {

        Policy {
            if (budgetMs <= 0L || delayMs <= 0L || maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "budgetMs=" + budgetMs + " delayMs=" + delayMs + " maxAttempts=" + maxAttempts);
            }
        }
    }

    /**
     * 1 回の結果。開けたか失敗かはどちらか一方だけを持つ。
     *
     * @param source  開けたソース。失敗なら {@code null}
     * @param failure 失敗。開けたなら {@code null}
     */
    record Outcome(IAudioSource source, PlaybackFailure failure) {

        static Outcome ok(IAudioSource source) {
            return new Outcome(source, null);
        }

        static Outcome failed(PlaybackFailure failure) {
            return new Outcome(null, failure);
        }

        boolean isOk() {
            return source != null;
        }
    }

    /**
     * その失敗は時間を置けば直りうるか。
     *
     * <p>入れるのは 4 つだけ。{@link PlaybackFailure.Kind#BOT_CHECK} (ローダーの
     * {@code BOT_CHECK})・{@link PlaybackFailure.Kind#NETWORK} ({@code CONNECTION_FAILED})・
     * {@link PlaybackFailure.Kind#REFUSED} ({@code SOURCE_REFUSED})・
     * {@link PlaybackFailure.Kind#STREAM_UNAVAILABLE} ({@code UNKNOWN} と、理由なしの
     * {@code null})。
     *
     * <p><b>入れないもの</b>: 非公開・削除済み・年齢制限・地域制限は、待っても相手の答えが
     * 変わらない。{@code PREVIEW_ONLY} / {@code DRM_PROTECTED} は配信の形そのもの。
     * {@code BLOCKED_URL} はこちらが拒んだ URL。{@code CONCURRENT_LIMIT} /
     * {@code MUTED} / {@code SOUND_ENGINE} / {@code LOCAL_AUDIO} は音源の外側の話なので、
     * 開き直しても同じ結果になる。
     *
     * @param failure 失敗 ({@code null} 可)
     * @return 開き直して意味があるなら {@code true}
     */
    static boolean isTransient(@Nullable PlaybackFailure failure) {
        if (failure == null) {
            return false;
        }
        return switch (failure.kind()) {
            case BOT_CHECK, NETWORK, REFUSED, STREAM_UNAVAILABLE -> true;
            default -> false;
        };
    }

    /**
     * 最初の失敗を受けて、必要なら間隔を置いて開き直す。
     *
     * <p>予算は<b>壁時計ではなく渡された時計</b>で測る。試行そのものが数十秒かかることが
     * あり、その時間も予算に含めるため (含めないと「最後の試行が長引いて、気付いたら
     * 何分も待たされていた」になる)。
     *
     * <p>待つ前と後に {@code stillCurrent} を見る。待っている間に盤が抜かれた・別の曲へ
     * 差し替えられた時は、そこまでの失敗をそのまま返して終わる (鳴らない盤のために
     * 通信を続けない)。
     *
     * @param policy       上限
     * @param clockMs      経過を測る時計 (ms)。単調であること
     * @param sleeper      待つ手段
     * @param stillCurrent この再生がまだ要るか
     * @param attempt      1 回開く試行 (最初の 1 回もここで呼ばれる)
     * @return 開けた結果、または最後の失敗
     */
    static Outcome run(Policy policy, LongSupplier clockMs, Sleeper sleeper,
            BooleanSupplier stillCurrent, Attempt attempt) {
        final long startedMs = clockMs.getAsLong();
        Outcome outcome = attempt.attempt();
        for (int attemptNumber = 1; attemptNumber < policy.maxAttempts(); attemptNumber++) {
            if (outcome.isOk() || !isTransient(outcome.failure()) || !stillCurrent.getAsBoolean()) {
                return outcome;
            }
            final long elapsedMs = Math.max(0L, clockMs.getAsLong() - startedMs);
            if (elapsedMs + policy.delayMs() > policy.budgetMs()) {
                return outcome;
            }
            if (!sleeper.sleep(policy.delayMs()) || !stillCurrent.getAsBoolean()) {
                return outcome;
            }
            outcome = attempt.attempt();
        }
        return outcome;
    }
}
