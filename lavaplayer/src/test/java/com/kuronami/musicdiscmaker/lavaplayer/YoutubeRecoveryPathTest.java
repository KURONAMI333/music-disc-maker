package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;

import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.http.YoutubeHttpContextFilter;

/**
 * YouTube の復旧を<b>本番の呼び出し構造のまま</b>回す。
 *
 * <h2>{@code SessionRetryTest} と何が違うのか</h2>
 * あちらの {@link FakeYoutubeSession} は visitorId を持つ偽物で、機構の形は正しく写している。
 * ただし本番で {@link SessionRetry} が実際に組む相手 — <b>本物の {@link YoutubeTokenSession} と
 * 本物の {@code YoutubeAudioSourceManager}</b> — は一度も通らない。「入れ替えたつもりが
 * manager 側では何も起きていない」型の欠陥は、偽物の側では原理的に起きない。
 *
 * <p>ここでは偽物を 1 枚だけにする: <b>ネットワークへ出るロード</b>。それ以外
 * ({@code SessionRetry.run} / {@code YoutubeTokenSession.rollIfStale} /
 * {@code MusicLoaderImpl.isRetryable} / 本物の manager と tracker) は本番と同じものを通す。
 *
 * <p>ロードの成否は<b>その時点で manager に刺さっている tracker の同一性</b>だけで決める。
 * 「入れ替えが呼ばれたか」ではなく「入れ替えが manager に効いたか」を見るための形で、
 * ここが本番の構造を通す意味そのもの。
 *
 * <h2>1 回入れ替えれば必ず直る、とは書かない</h2>
 * 実測は 9/10 で、<b>新しい visitorId がまた弾かれることはある</b>。だから
 * {@link #recoveryCanTakeMoreThanOneRoll} は「1 回目の入れ替え先もまだ弾かれている」条件を
 * 明示的に作る。「roll したら成功」を偽物に埋め込むと、現実には起きない条件を固定してしまう。
 */
class YoutubeRecoveryPathTest {

    /** 本番と同じ上限 (回数 5 / 締切 20 秒)。待ち時間だけテストでは 0。 */
    private static final SessionRetry.Policy POLICY = new SessionRetry.Policy(5, 20_000L, 0L);

    /**
     * <b>これが今回の回帰テスト。</b> 本物の {@link YoutubeTokenSession} を通して、弾かれた
     * tracker が実際に manager 上で差し替わり、次の試行が新しい tracker で走ること。
     *
     * <p>「{@code rollIfStale} が true を返した」では足りない。ここが見るのは
     * <b>manager に刺さっている tracker が本当に別物になったか</b>。
     */
    @Test
    void aBlockedTrackerIsActuallyReplacedOnTheRealManager() throws Exception {
        final YoutubeAudioSourceManager youtube = newManager();
        try {
            final Object initial = tokenTracker(youtube);
            assertNotNull(initial,
                    "起動直後に tracker が刺さっていない = 同一性で成否を決めるこのテストが成立しない");

            final YoutubeTokenSession session =
                    new YoutubeTokenSession(youtube, new RollBudget(8, 7_500L, System::currentTimeMillis));
            final Blocked blocked = new Blocked(initial); // 起動時に引いた不運な visitorId
            final Attempts attempts = new Attempts(youtube, blocked);

            final Object played = SessionRetry.run(attempts, session, POLICY,
                    reason -> MusicLoaderImpl.isRetryable(reason, false), System::currentTimeMillis);

            assertEquals(2, attempts.log.size(), "試行の回数が想定と違う: " + attempts.log);
            assertSame(initial, attempts.log.get(0), "1 回目が起動時の tracker で走っていない");
            assertTrue(attempts.log.get(1) != initial,
                    "2 回目が同じ tracker で走っている (入れ替えが manager に効いていない)");
            assertSame(tokenTracker(youtube), played,
                    "成功した試行の tracker が、いま manager に刺さっているものと違う");
            assertEquals(1L, session.generation(), "世代が進んでいない");
        } finally {
            youtube.shutdown();
        }
    }

    /**
     * <b>「1 回入れ替えれば直る」を前提にしないための条件。</b> 入れ替えた先もまだ弾かれていて、
     * もう一度入れ替えてやっと通る形 (実測 9/10 の残り 1 に当たる)。
     *
     * <p>これが通らない実装は「1 回だけ入れ替えて諦める」= 実機で 1 割方の利用者が直らない。
     */
    @Test
    void recoveryCanTakeMoreThanOneRoll() throws Exception {
        final YoutubeAudioSourceManager youtube = newManager();
        try {
            final YoutubeTokenSession session =
                    new YoutubeTokenSession(youtube, new RollBudget(8, 7_500L, System::currentTimeMillis));
            // 起動時のものと「1 回目の入れ替え先」の 2 つを弾く。2 回目の入れ替え先で初めて通る。
            final Blocked blocked = new Blocked(tokenTracker(youtube));
            blocked.alsoBlockNextCount(1);
            final Attempts attempts = new Attempts(youtube, blocked);

            SessionRetry.run(attempts, session, POLICY,
                    reason -> MusicLoaderImpl.isRetryable(reason, false), System::currentTimeMillis);

            assertEquals(3, attempts.log.size(), "2 回目の入れ替えまで進んでいない: " + attempts.log);
            assertEquals(3, new HashSet<>(identities(attempts.log)).size(),
                    "3 回の試行が同じ tracker を使い回している");
            assertEquals(2L, session.generation(), "入れ替えが 2 回起きていない");
        } finally {
            youtube.shutdown();
        }
    }

    /**
     * 入れ替えの余力が尽きたら、本物の {@link RollBudget} が本物の {@link SessionRetry} を止めること。
     * 分類は最後の失敗のまま投げ返す (握り潰さない)。
     */
    @Test
    void anExhaustedRollBudgetStopsTheRealRetryLoop() throws Exception {
        final YoutubeAudioSourceManager youtube = newManager();
        try {
            // 入れ替え 1 回ぶんだけ許す。2 回目の入れ替えができず、そこで打ち切られる。
            final YoutubeTokenSession session =
                    new YoutubeTokenSession(youtube, new RollBudget(1, 7_500L, () -> 0L));
            final Blocked blocked = new Blocked(tokenTracker(youtube));
            blocked.alsoBlockNextCount(99); // 何に替えても弾かれる
            final Attempts attempts = new Attempts(youtube, blocked);

            final ResolveException thrown = assertThrows(ResolveException.class,
                    () -> SessionRetry.run(attempts, session, POLICY,
                            reason -> MusicLoaderImpl.isRetryable(reason, false), System::currentTimeMillis));

            assertEquals(FailureReason.BOT_CHECK, thrown.reason(), "分類が失われている");
            assertEquals(2, attempts.log.size(),
                    "入れ替えの余力が尽きたのに試行を続けている: " + attempts.log.size());
            assertEquals(1L, session.generation(), "予算を超えて入れ替えている");
        } finally {
            youtube.shutdown();
        }
    }

    /**
     * 確定的な失敗 (非公開・削除済み) では、本物の manager の tracker を触らないこと。
     * 直らない失敗で visitorId を捨てると、同時に鳴っている他の再生の足元まで動く。
     */
    @Test
    void terminalFailuresLeaveTheRealTrackerAlone() throws Exception {
        final YoutubeAudioSourceManager youtube = newManager();
        try {
            final Object initial = tokenTracker(youtube);
            final YoutubeTokenSession session =
                    new YoutubeTokenSession(youtube, new RollBudget(8, 7_500L, System::currentTimeMillis));
            final List<Object> log = new ArrayList<>();

            assertThrows(ResolveException.class, () -> SessionRetry.run(() -> {
                log.add(tokenTrackerUnchecked(youtube));
                throw new ResolveException(FailureReason.PRIVATE_OR_REMOVED);
            }, session, POLICY, reason -> MusicLoaderImpl.isRetryable(reason, false), System::currentTimeMillis));

            assertEquals(1, log.size(), "直らない失敗を再試行している");
            assertSame(initial, tokenTracker(youtube), "直らない失敗で tracker を差し替えている");
            assertEquals(0L, session.generation(), "直らない失敗で世代が進んでいる");
        } finally {
            youtube.shutdown();
        }
    }

    /** 「いま manager に刺さっている tracker が弾かれているか」だけを見るロード (本番の seam)。 */
    private static final class Attempts implements SessionRetry.Attempt<Object> {

        private final YoutubeAudioSourceManager youtube;
        private final Blocked blocked;
        final List<Object> log = new ArrayList<>();

        Attempts(YoutubeAudioSourceManager youtube, Blocked blocked) {
            this.youtube = youtube;
            this.blocked = blocked;
        }

        @Override
        public Object run() {
            final Object tracker = tokenTrackerUnchecked(youtube);
            log.add(tracker);
            if (blocked.rejects(tracker)) {
                throw new ResolveException(FailureReason.BOT_CHECK);
            }
            return tracker;
        }
    }

    /**
     * YouTube に弾かれている tracker の集合。起動時のものは常に弾き、追加で「これから出てくる
     * 新しい tracker を何個弾くか」を指定できる (入れ替えた先がまた弾かれる現実の再現)。
     */
    private static final class Blocked {

        private final Set<Object> known = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private int blockNextCount;

        Blocked(Object initial) {
            known.add(initial);
        }

        void alsoBlockNextCount(int count) {
            this.blockNextCount = count;
        }

        boolean rejects(Object tracker) {
            if (known.contains(tracker)) {
                return true;
            }
            if (blockNextCount > 0) {
                blockNextCount--;
                known.add(tracker);
                return true;
            }
            return false;
        }
    }

    private static List<Integer> identities(List<Object> objects) {
        final List<Integer> ids = new ArrayList<>();
        objects.forEach(o -> ids.add(System.identityHashCode(o)));
        return ids;
    }

    private static YoutubeAudioSourceManager newManager() {
        return new YoutubeAudioSourceManager(new AndroidVr());
    }

    private static Object tokenTracker(YoutubeAudioSourceManager youtube) throws Exception {
        final Field field = YoutubeHttpContextFilter.class.getDeclaredField("tokenTracker");
        field.setAccessible(true);
        return field.get(youtube.getContextFilter());
    }

    private static Object tokenTrackerUnchecked(YoutubeAudioSourceManager youtube) {
        try {
            return tokenTracker(youtube);
        } catch (final Exception ex) {
            throw new IllegalStateException("tracker を読めない", ex);
        }
    }
}
