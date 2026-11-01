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
 */
class TransientOpenRetryTest {

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

        TransientOpenRetry.Outcome run(TransientOpenRetry.Policy policy, TransientOpenRetry.Attempt attempt) {
            return TransientOpenRetry.run(policy, clock::get, sleeper(), stillCurrent::get, attempt);
        }
    }

    @Test
    void aRefusedOpenIsRetriedAfterTheIntervalAndThenSucceeds() {
        final Harness h = new Harness();
        final var outcome = h.run(new TransientOpenRetry.Policy(30_000L, 7_500L, 6),
                h.failsThenSucceeds(1, FailureReason.BOT_CHECK));

        assertTrue(outcome.isOk());
        assertSame(SOURCE, outcome.source());
        assertNull(outcome.failure());
        assertEquals(2, h.attempts.get());
        assertEquals(List.of(7_500L), h.sleptMs);
    }

    @Test
    void theBudgetEndsTheRetriesAndTheLastFailureIsReported() {
        final Harness h = new Harness();
        final var outcome = h.run(new TransientOpenRetry.Policy(30_000L, 7_500L, 6),
                h.alwaysFails(FailureReason.BOT_CHECK));

        assertFalse(outcome.isOk());
        assertNull(outcome.source());
        assertEquals(PlaybackFailure.Kind.BOT_CHECK, outcome.failure().kind());
        // 0 / 7.5 / 15 / 22.5 / 30 秒の 5 回。30 秒の次は 37.5 秒になり予算を越えるので止まる。
        assertEquals(5, h.attempts.get());
        assertEquals(List.of(7_500L, 7_500L, 7_500L, 7_500L), h.sleptMs);
    }

    @Test
    void aCancelledPlaybackStopsWithoutWaitingOrTryingAgain() {
        final Harness h = new Harness();
        final TransientOpenRetry.Outcome outcome = h.run(new TransientOpenRetry.Policy(30_000L, 7_500L, 6),
                () -> {
                    h.attempts.incrementAndGet();
                    h.stillCurrent.set(false); // 待つ前に盤が抜かれた
                    return TransientOpenRetry.Outcome.failed(failure(FailureReason.CONNECTION_FAILED));
                });

        assertFalse(outcome.isOk());
        assertEquals(1, h.attempts.get());
        assertEquals(0, h.sleeps.get());
    }

    @Test
    void aCancellationDuringTheWaitStopsBeforeTheNextAttempt() {
        final Harness h = new Harness();
        final TransientOpenRetry.Outcome outcome = TransientOpenRetry.run(
                new TransientOpenRetry.Policy(30_000L, 7_500L, 6), h.clock::get,
                ms -> {
                    h.sleeps.incrementAndGet();
                    h.clock.addAndGet(ms);
                    h.stillCurrent.set(false); // 待っている間に差し替えられた
                    return true;
                }, h.stillCurrent::get, h.alwaysFails(FailureReason.SOURCE_REFUSED));

        assertFalse(outcome.isOk());
        assertEquals(1, h.attempts.get());
        assertEquals(1, h.sleeps.get());
    }

    @Test
    void anInterruptedWaitStopsTheRetries() {
        final Harness h = new Harness();
        final TransientOpenRetry.Outcome outcome = TransientOpenRetry.run(
                new TransientOpenRetry.Policy(30_000L, 7_500L, 6), h.clock::get,
                ms -> false, h.stillCurrent::get, h.alwaysFails(FailureReason.CONNECTION_FAILED));

        assertFalse(outcome.isOk());
        assertEquals(1, h.attempts.get());
    }

    @Test
    void aPermanentFailureIsNotRetried() {
        final Harness h = new Harness();
        final var outcome = h.run(new TransientOpenRetry.Policy(30_000L, 7_500L, 6),
                h.alwaysFails(FailureReason.PRIVATE_OR_REMOVED));

        assertFalse(outcome.isOk());
        assertEquals(1, h.attempts.get());
        assertEquals(0, h.sleeps.get());
    }

    @Test
    void theAttemptCapEndsTheRetriesBeforeTheBudget() {
        final Harness h = new Harness();
        final var outcome = h.run(new TransientOpenRetry.Policy(1_000_000L, 1_000L, 3),
                h.alwaysFails(FailureReason.CONNECTION_FAILED));

        assertFalse(outcome.isOk());
        assertEquals(3, h.attempts.get());
        assertEquals(2, h.sleeps.get());
    }

    @Test
    void onlyFailuresThatTimeCanFixAreTransient() {
        for (final FailureReason reason : List.of(FailureReason.BOT_CHECK,
                FailureReason.CONNECTION_FAILED, FailureReason.SOURCE_REFUSED, FailureReason.UNKNOWN)) {
            assertTrue(TransientOpenRetry.isTransient(failure(reason)), reason.name());
        }
        for (final FailureReason reason : List.of(FailureReason.PRIVATE_OR_REMOVED,
                FailureReason.REGION_LOCKED, FailureReason.AGE_RESTRICTED, FailureReason.PREVIEW_ONLY,
                FailureReason.DRM_PROTECTED, FailureReason.BLOCKED_URL, FailureReason.UNSUPPORTED_URL,
                FailureReason.NO_ALTERNATIVE_FOUND)) {
            assertFalse(TransientOpenRetry.isTransient(failure(reason)), reason.name());
        }
        assertFalse(TransientOpenRetry.isTransient(null));
        assertFalse(TransientOpenRetry.isTransient(PlaybackFailure.concurrentLimit(8)));
        assertFalse(TransientOpenRetry.isTransient(PlaybackFailure.soundMuted()));
        assertFalse(TransientOpenRetry.isTransient(PlaybackFailure.soundEngineRejected()));
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
