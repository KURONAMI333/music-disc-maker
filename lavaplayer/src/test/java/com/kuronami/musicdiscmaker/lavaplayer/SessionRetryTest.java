package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;

/**
 * 解決 (同期側) の再試行が<b>セッションの入れ替えとセットで</b>動くことを固定する。
 *
 * <h2>何をモデルにしているか — 「待つ」ではなく「visitorId を替える」</h2>
 * ここの偽物は「リセットが呼ばれたら成功する」ではなく、<b>visitorId そのもの</b>を持つ。
 * ロードは「今の visitorId が YouTube に弾かれているか」だけを見て成否を決め、入れ替えは
 * 「visitorId を別の値にする」だけを担う。こうしないとテストが機構ではなく偽物の都合を
 * 確かめることになる。
 *
 * <p>実測 (2026-08-14、同一 manager で 5 回試行):
 *
 * <pre>
 *   2000ms 待って再試行するだけ ......... 回復 0 / 10
 *   tracker を差し替えてから再試行 ...... 回復 9 / 10
 * </pre>
 *
 * <p>この 2 行がそれぞれ {@link #retryWithoutRollingTheVisitorIdNeverRecovers} と
 * {@link #failureRollsTheVisitorIdBeforeTheNextAttempt} に対応する。
 */
class SessionRetryTest {

        private static final Predicate<FailureReason> RETRY_BOT_CHECK =
            reason -> reason == FailureReason.BOT_CHECK;

    /** 実機と同じ形の上限 (回数 5 / 締切 20 秒)。待ち時間はテストでは 0。 */
    private static final SessionRetry.Policy POLICY = new SessionRetry.Policy(5, 20_000L, 0L);

    /**
     * <b>これが直したかった挙動。</b> 弾かれた visitorId で失敗したら、入れ替えてから次を試す。
     * 順番が「試行 → 入れ替え → 試行」であること自体を固定する。
     */
    @Test
    void failureRollsTheVisitorIdBeforeTheNextAttempt() {
        final List<String> log = new ArrayList<>();
        final FakeYoutubeSession session = new FakeYoutubeSession(log, 8);
        final String played = SessionRetry.run(loader(session, log, FakeYoutubeSession.POISONED::equals),
                session, POLICY, RETRY_BOT_CHECK, System::currentTimeMillis);

        assertEquals("visitor-1", played, "入れ替え後の visitorId で再生できていない");
        assertEquals(List.of("attempt:visitor-0", "roll", "attempt:visitor-1"), log,
                "失敗 → 入れ替え → 再試行 の順になっていない");
        assertEquals(1, session.rolls, "入れ替えの回数が想定と違う");
    }

    /**
     * <b>対照実験をテストに落としたもの。</b> 「再試行はするが visitorId は替えない」= 実測 0/10 の条件。
     * 何回やっても回復しないこと、つまり<b>効いているのは再試行ではなく入れ替えの方</b>だと固定する。
     */
    @Test
    void retryWithoutRollingTheVisitorIdNeverRecovers() {
        final List<String> log = new ArrayList<>();
        // rollIfStale は true を返す (= 再試行は続く) のに visitorId は据え置き。
        final FakeYoutubeSession session = new FakeYoutubeSession(log, 8) {
            @Override
            public boolean rollIfStale(long seen) {
                log.add("roll(noop)");
                return true;
            }
        };

        final ResolveException thrown = assertThrows(ResolveException.class,
                () -> SessionRetry.run(loader(session, log, FakeYoutubeSession.POISONED::equals),
                        session, POLICY, RETRY_BOT_CHECK, System::currentTimeMillis));

        assertEquals(FailureReason.BOT_CHECK, thrown.reason(), "失敗の分類が失われている");
        assertEquals(5, count(log, "attempt:"), "上限まで再試行していない");
        assertTrue(log.stream().allMatch(line -> !line.startsWith("attempt:") || line.endsWith(FakeYoutubeSession.POISONED)),
                "visitorId が替わっていないはずなのに替わっている");
    }

    /** 上限を使い切ったら、最後の失敗の分類をそのまま投げること (握り潰さない)。 */
    @Test
    void exhaustedAttemptsThrowTheClassifiedFailure() {
        final List<String> log = new ArrayList<>();
        final FakeYoutubeSession session = new FakeYoutubeSession(log, 8);

        final ResolveException thrown = assertThrows(ResolveException.class,
                () -> SessionRetry.run(loader(session, log, id -> true), // 何を引いても弾かれる
                        session, POLICY, RETRY_BOT_CHECK, System::currentTimeMillis));

        assertEquals(FailureReason.BOT_CHECK, thrown.reason(), "分類つきで失敗していない");
        assertEquals(5, count(log, "attempt:"), "試行回数が上限と違う");
        assertEquals(4, session.rolls, "試行の間ごとに入れ替えていない");
    }

    /** 入れ替えの余力が尽きたら、そこで打ち切ること (同じ visitorId での空振りを続けない)。 */
    @Test
    void noRollBudgetMeansNoFurtherAttempts() {
        final List<String> log = new ArrayList<>();
        final FakeYoutubeSession session = new FakeYoutubeSession(log, 0); // 入れ替え不可

        final ResolveException thrown = assertThrows(ResolveException.class,
                () -> SessionRetry.run(loader(session, log, FakeYoutubeSession.POISONED::equals),
                        session, POLICY, RETRY_BOT_CHECK, System::currentTimeMillis));

        assertEquals(FailureReason.BOT_CHECK, thrown.reason());
        assertEquals(1, count(log, "attempt:"), "入れ替えられないのに再試行している");
    }

    /** 確定的な失敗 (非公開・削除済み等) は 1 回で諦めること。 */
    @Test
    void terminalFailuresAreNotRetried() {
        final List<String> log = new ArrayList<>();
        final FakeYoutubeSession session = new FakeYoutubeSession(log, 8);

        final ResolveException thrown = assertThrows(ResolveException.class,
                () -> SessionRetry.run(() -> {
                    log.add("attempt:" + session.visitorId);
                    throw new ResolveException(FailureReason.PRIVATE_OR_REMOVED);
                }, session, POLICY, reason -> MusicLoaderImpl.isRetryable(reason, true),
                        System::currentTimeMillis));

        assertEquals(FailureReason.PRIVATE_OR_REMOVED, thrown.reason());
        assertEquals(1, count(log, "attempt:"), "直らない失敗を再試行している");
        assertEquals(0, session.rolls, "直らない失敗でセッションを入れ替えている");
    }

    /** 締切を過ぎたら、回数が残っていても次の試行を始めないこと。 */
    @Test
    void deadlineStopsFurtherAttempts() {
        final List<String> log = new ArrayList<>();
        final FakeYoutubeSession session = new FakeYoutubeSession(log, 8);
        final AtomicLong now = new AtomicLong(1_000L);

        final ResolveException thrown = assertThrows(ResolveException.class,
                () -> SessionRetry.run(() -> {
                    log.add("attempt:" + session.visitorId);
                    now.addAndGet(25_000L); // 1 回で締切を超える遅い失敗
                    throw new ResolveException(FailureReason.CONNECTION_FAILED);
                }, session, POLICY, reason -> true, now::get));

        assertEquals(FailureReason.CONNECTION_FAILED, thrown.reason());
        assertEquals(1, count(log, "attempt:"), "締切を過ぎても再試行している");
    }

    /** {@link MusicLoaderImpl#isRetryable} の判定 (どの理由をやり直すか)。 */
    @Test
    void onlyRecoverableReasonsAreRetried() {
        assertTrue(MusicLoaderImpl.isRetryable(FailureReason.BOT_CHECK, false));
        assertTrue(MusicLoaderImpl.isRetryable(FailureReason.CONNECTION_FAILED, false));
        assertTrue(MusicLoaderImpl.isRetryable(FailureReason.UNKNOWN, false));
        // 5xx はここに落ちる。false に転ぶと、開き直せば通る失敗を 1 回で諦めることになる。
        assertTrue(MusicLoaderImpl.isRetryable(FailureReason.SOURCE_REFUSED, false));
        assertFalse(MusicLoaderImpl.isRetryable(FailureReason.PRIVATE_OR_REMOVED, true));
        assertFalse(MusicLoaderImpl.isRetryable(FailureReason.AGE_RESTRICTED, true));
        assertFalse(MusicLoaderImpl.isRetryable(FailureReason.REGION_LOCKED, true));
        // 検索が bot 判定で弾かれると 0 件 = noMatches として返るので、検索の時だけやり直す。
        assertTrue(MusicLoaderImpl.isRetryable(FailureReason.UNSUPPORTED_URL, true));
        assertFalse(MusicLoaderImpl.isRetryable(FailureReason.UNSUPPORTED_URL, false));
        // 相手が配信していないものは、開き直しても visitorId を替えても出てこない (C17)。
        assertFalse(MusicLoaderImpl.isRetryable(FailureReason.PREVIEW_ONLY, false));
        assertFalse(MusicLoaderImpl.isRetryable(FailureReason.PREVIEW_ONLY, true));
    }

    /** 再試行の対象になるのは YouTube 相手だけ (他サービスの visitorId は存在しない)。 */
    @Test
    void onlyYoutubeTargetsEnterTheRetryPath() {
        assertTrue(MusicLoaderImpl.isYoutubeTarget("https://www.youtube.com/watch?v=YOYeJn4mz8M"));
        assertTrue(MusicLoaderImpl.isYoutubeTarget("https://youtu.be/YOYeJn4mz8M"));
        assertTrue(MusicLoaderImpl.isYoutubeTarget("ytsearch:Daft Punk Around the World"));
        assertFalse(MusicLoaderImpl.isYoutubeTarget("https://soundcloud.com/artist/track"));
        assertFalse(MusicLoaderImpl.isYoutubeTarget("https://example.invalid/stream.mp3"));
    }

    /** 「今の visitorId が弾かれていれば失敗」だけを見るロード。 */
    private static SessionRetry.Attempt<String> loader(FakeYoutubeSession session, List<String> log,
            Predicate<String> blocked) {
        return () -> {
            log.add("attempt:" + session.visitorId);
            if (blocked.test(session.visitorId)) {
                throw new ResolveException(FailureReason.BOT_CHECK);
            }
            return session.visitorId;
        };
    }

    private static long count(List<String> log, String prefix) {
        return log.stream().filter(line -> line.startsWith(prefix)).count();
    }
}
