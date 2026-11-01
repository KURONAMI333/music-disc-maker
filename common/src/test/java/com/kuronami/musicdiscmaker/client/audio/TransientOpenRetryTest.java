package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * 時間を置いた開き直し ({@link TransientOpenRetry}) の上限と打ち切りを固定する。
 *
 * <p>盤を入れてから鳴り出すまで待たせる以上、「いつまで待つか」「いつ止めるか」が
 * 決まっていないと、抜いた盤のために通信を続けたり、待ち続けて再生が始まらない。
 * 時計と待ち方を差し替えられるので、実時間を掛けずに境界を確かめられる。
 *
 * <p>{@link TransientOpenRetry#run} は<b>既に失敗した結果</b> ({@code previous}) を受け取り、
 * 最初の試行の前にも間隔を置く。呼び出し側は即時の再試行を使い切った直後なので、
 * 間を置かずに叩き直しても同じ visitorId で同じ答えが返るだけになる。
 */
class TransientOpenRetryTest {

    private static final String YOUTUBE = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String DIRECT = "https://example.com/song.mp3";
    private static final TransientOpenRetry.Policy POLICY =
            new TransientOpenRetry.Policy(30_000L, 7_500L, 6);

    private static final class FakeSource implements IAudioSource {
        @Override public int sampleRate() { return 44_100; }
        @Override public int channels() { return 2; }
        @Override public int bitsPerSample() { return 16; }
        @Override public boolean bigEndian() { return false; }
        @Override public int read(byte[] dst, int off, int len) { return -1; }
        @Override public void close() { }
    }

    private static final FakeSource SOURCE = new FakeSource();

    private static PlaybackFailure failure(FailureReason reason) {
        return PlaybackFailure.ofReason(reason, "");
    }

    /** 試行回数と待ち時間を記録する土台。 */
    private static final class Harness {
        final AtomicLong clock = new AtomicLong(1_000L);
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger sleeps = new AtomicInteger();
        final List<Long> sleptMs = new ArrayList<>();
        final AtomicBoolean stillCurrent = new AtomicBoolean(true);

        TransientOpenRetry.Sleeper sleeper() {
            return ms -> {
                sleeps.incrementAndGet();
                sleptMs.add(ms);
                clock.addAndGet(ms);
                return true;
            };
        }

        /** 最初の {@code failures} 回は失敗し、その後は成功する試行。 */
        TransientOpenRetry.Attempt failsThenSucceeds(int failures, FailureReason reason) {
            return () -> attempts.incrementAndGet() <= failures
                    ? TransientOpenRetry.Outcome.failed(failure(reason))
                    : TransientOpenRetry.Outcome.ok(SOURCE);
        }

        /** 常に同じ理由で失敗する試行。 */
        TransientOpenRetry.Attempt alwaysFails(FailureReason reason) {
            return () -> {
                attempts.incrementAndGet();
                return TransientOpenRetry.Outcome.failed(failure(reason));
            };
        }

        TransientOpenRetry.Outcome run(TransientOpenRetry.Policy policy, FailureReason previousReason,
                TransientOpenRetry.Attempt attempt) {
            return run(policy, YOUTUBE, TransientOpenRetry.Outcome.failed(failure(previousReason)), attempt);
        }

        TransientOpenRetry.Outcome run(TransientOpenRetry.Policy policy, String url,
                TransientOpenRetry.Outcome previous, TransientOpenRetry.Attempt attempt) {
            return TransientOpenRetry.run(policy, clock::get, sleeper(), stillCurrent::get, url,
                    previous, attempt);
        }
    }

    @Test
    void theFirstAttemptWaitsForTheIntervalBeforeItRuns() {
        final Harness h = new Harness();
        final var outcome = h.run(POLICY, FailureReason.BOT_CHECK,
                h.failsThenSucceeds(1, FailureReason.BOT_CHECK));

        assertTrue(outcome.isOk());
        assertSame(SOURCE, outcome.source());
        assertNull(outcome.failure());
        // 即時の再試行を使い切った直後に空打ちしない。最初の試行の前にも間隔を置く。
        assertEquals(2, h.attempts.get());
        assertEquals(List.of(7_500L, 7_500L), h.sleptMs);
    }

    @Test
    void theBudgetEndsTheRetriesAndTheLastFailureIsReported() {
        final Harness h = new Harness();
        final var outcome = h.run(POLICY, FailureReason.BOT_CHECK,
                h.alwaysFails(FailureReason.BOT_CHECK));

        assertFalse(outcome.isOk());
        assertNull(outcome.source());
        assertEquals(PlaybackFailure.Kind.BOT_CHECK, outcome.failure().kind());
        // 7.5 / 15 / 22.5 / 30 秒の 4 回。30 秒の次は 37.5 秒になり予算を越えるので止まる。
        assertEquals(4, h.attempts.get());
        assertEquals(List.of(7_500L, 7_500L, 7_500L, 7_500L), h.sleptMs);
    }

    @Test
    void aBudgetThatCannotCoverTheFirstWaitMakesNoAttempt() {
        final Harness h = new Harness();
        final var previous = TransientOpenRetry.Outcome.failed(failure(FailureReason.BOT_CHECK));
        final var outcome = h.run(new TransientOpenRetry.Policy(5_000L, 7_500L, 6), YOUTUBE, previous,
                h.alwaysFails(FailureReason.BOT_CHECK));

        // 待てないなら叩かない。作った失敗を返さず、渡された結果をそのまま返す。
        assertSame(previous, outcome);
        assertEquals(0, h.attempts.get());
        assertEquals(0, h.sleeps.get());
    }

    @Test
    void aCancelledPlaybackStopsWithoutWaitingOrTryingAgain() {
        final Harness h = new Harness();
        h.stillCurrent.set(false);
        final var previous = TransientOpenRetry.Outcome.failed(failure(FailureReason.CONNECTION_FAILED));
        final var outcome = h.run(POLICY, YOUTUBE, previous, () -> {
            h.attempts.incrementAndGet();
            return TransientOpenRetry.Outcome.ok(SOURCE);
        });

        assertSame(previous, outcome);
        assertEquals(0, h.attempts.get());
        assertEquals(0, h.sleeps.get());
    }

    @Test
    void aCancellationDuringTheWaitStopsBeforeTheNextAttempt() {
        final Harness h = new Harness();
        final TransientOpenRetry.Outcome outcome = TransientOpenRetry.run(POLICY, h.clock::get,
                ms -> {
                    h.sleeps.incrementAndGet();
                    h.clock.addAndGet(ms);
                    h.stillCurrent.set(false); // 待っている間に差し替えられた
                    return true;
                }, h.stillCurrent::get, YOUTUBE,
                TransientOpenRetry.Outcome.failed(failure(FailureReason.SOURCE_REFUSED)),
                h.alwaysFails(FailureReason.SOURCE_REFUSED));

        assertFalse(outcome.isOk());
        assertEquals(0, h.attempts.get());
        assertEquals(1, h.sleeps.get());
    }

    @Test
    void anInterruptedWaitStopsTheRetries() {
        final Harness h = new Harness();
        final TransientOpenRetry.Outcome outcome = TransientOpenRetry.run(POLICY, h.clock::get,
                ms -> false, h.stillCurrent::get, YOUTUBE,
                TransientOpenRetry.Outcome.failed(failure(FailureReason.CONNECTION_FAILED)),
                h.alwaysFails(FailureReason.CONNECTION_FAILED));

        assertFalse(outcome.isOk());
        assertEquals(0, h.attempts.get());
    }

    @Test
    void aPermanentFailureIsNotRetried() {
        final Harness h = new Harness();
        final var previous = TransientOpenRetry.Outcome.failed(failure(FailureReason.PRIVATE_OR_REMOVED));
        final var outcome = h.run(POLICY, YOUTUBE, previous, h.alwaysFails(FailureReason.PRIVATE_OR_REMOVED));

        assertSame(previous, outcome);
        assertEquals(0, h.attempts.get());
        assertEquals(0, h.sleeps.get());
    }

    @Test
    void aRetryThatFailsPermanentlyEndsTheLoop() {
        final Harness h = new Harness();
        final var outcome = h.run(POLICY, FailureReason.BOT_CHECK,
                h.alwaysFails(FailureReason.PRIVATE_OR_REMOVED));

        assertFalse(outcome.isOk());
        assertEquals(PlaybackFailure.Kind.PRIVATE_OR_REMOVED, outcome.failure().kind());
        // 1 回だけ試して、その答えが恒久的なら待ち直さない。
        assertEquals(1, h.attempts.get());
        assertEquals(1, h.sleeps.get());
    }

    @Test
    void theAttemptCapEndsTheRetriesBeforeTheBudget() {
        final Harness h = new Harness();
        final var outcome = h.run(new TransientOpenRetry.Policy(1_000_000L, 1_000L, 3),
                FailureReason.CONNECTION_FAILED, h.alwaysFails(FailureReason.CONNECTION_FAILED));

        assertFalse(outcome.isOk());
        assertEquals(3, h.attempts.get());
        assertEquals(3, h.sleeps.get());
    }

    @Test
    void onlyFailuresThatTimeCanFixAreTransient() {
        for (final FailureReason reason : List.of(FailureReason.CONNECTION_FAILED,
                FailureReason.SOURCE_REFUSED, FailureReason.UNKNOWN)) {
            assertTrue(TransientOpenRetry.isTransient(failure(reason), YOUTUBE), reason.name());
        }
        for (final FailureReason reason : List.of(FailureReason.PRIVATE_OR_REMOVED,
                FailureReason.REGION_LOCKED, FailureReason.AGE_RESTRICTED, FailureReason.PREVIEW_ONLY,
                FailureReason.DRM_PROTECTED, FailureReason.BLOCKED_URL, FailureReason.UNSUPPORTED_URL,
                FailureReason.NO_ALTERNATIVE_FOUND)) {
            assertFalse(TransientOpenRetry.isTransient(failure(reason), YOUTUBE), reason.name());
        }
        assertFalse(TransientOpenRetry.isTransient(null, YOUTUBE));
        assertFalse(TransientOpenRetry.isTransient(PlaybackFailure.concurrentLimit(8), YOUTUBE));
        assertFalse(TransientOpenRetry.isTransient(PlaybackFailure.soundMuted(), YOUTUBE));
        assertFalse(TransientOpenRetry.isTransient(PlaybackFailure.soundEngineRejected(), YOUTUBE));
    }

    @Test
    void aBotCheckIsOnlyRetriedWhenTheTargetIsYoutube() {
        final PlaybackFailure botCheck = failure(FailureReason.BOT_CHECK);

        assertTrue(TransientOpenRetry.isTransient(botCheck, YOUTUBE));
        assertTrue(TransientOpenRetry.isTransient(botCheck, "https://youtu.be/dQw4w9WgXcQ"));
        assertTrue(TransientOpenRetry.isTransient(botCheck, "ytsearch:Rick Astley Never Gonna Give You Up"));
        // 直リンクの 403 も BOT_CHECK に寄せてある (MDM_DECISIONS D8)。待っても変わらない。
        assertFalse(TransientOpenRetry.isTransient(botCheck, DIRECT));
        assertFalse(TransientOpenRetry.isTransient(botCheck, null));
    }

    @Test
    void thePolicyRejectsAnUnusableLimit() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TransientOpenRetry.Policy(0L, 7_500L, 6));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TransientOpenRetry.Policy(30_000L, 0L, 6));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TransientOpenRetry.Policy(30_000L, 7_500L, 0));
    }
}
