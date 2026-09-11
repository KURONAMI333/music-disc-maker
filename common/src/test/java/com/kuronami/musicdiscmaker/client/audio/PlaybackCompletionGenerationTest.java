package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** ロード開始後に世代が変わった時、古い完了が新しい要求の状態を壊さないことを固定する。 */
class PlaybackCompletionGenerationTest {

    private static final long KEY = 42L;
    private static final String URL = "https://example.invalid/same";

    @Test
    void staleFailureForSameUrlCannotConsumeCurrentPendingBackoffOrReport() {
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(() -> 0L);
        final int staleToken = begin(sessions);
        begin(sessions); // 同じ機体・同じ URL の後続要求

        final Map<Long, String> pending = new HashMap<>();
        pending.put(KEY, URL);
        final LoadFailureBackoff<Long> backoff = new LoadFailureBackoff<>();
        for (int attempt = 1; attempt < LoadFailureBackoff.MAX_ATTEMPTS; attempt++) {
            backoff.recordFailure(KEY, URL);
        }
        final AtomicInteger reports = new AtomicInteger();

        final boolean applied = sessions.completeIfLive(KEY, staleToken, () -> {
            pending.remove(KEY, URL);
            if (backoff.recordFailure(KEY, URL)) {
                reports.incrementAndGet();
            }
        });

        assertFalse(applied);
        assertEquals(URL, pending.get(KEY), "旧完了が現世代の PENDING を消した");
        assertFalse(backoff.isExhausted(KEY, URL), "旧失敗が現世代の再試行予算を消費した");
        assertEquals(0, reports.get(), "旧失敗が利用者向け報告を出した");
    }

    @Test
    void staleSuccessForSameUrlCannotResetCurrentBackoff() {
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(() -> 0L);
        final int staleToken = begin(sessions);
        begin(sessions);

        final LoadFailureBackoff<Long> backoff = new LoadFailureBackoff<>();
        for (int attempt = 0; attempt < LoadFailureBackoff.MAX_ATTEMPTS; attempt++) {
            backoff.recordFailure(KEY, URL);
        }

        final boolean applied = sessions.completeIfLive(KEY, staleToken, () -> backoff.reset(KEY));

        assertFalse(applied);
        assertTrue(backoff.isExhausted(KEY, URL), "旧成功が現世代の失敗予算を返した");
    }

    @Test
    void currentCompletionIsApplied() {
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(() -> 0L);
        final int currentToken = begin(sessions);
        final AtomicInteger effects = new AtomicInteger();

        assertTrue(sessions.completeIfLive(KEY, currentToken, effects::incrementAndGet));
        assertEquals(1, effects.get());
    }

    private static int begin(PlaybackSessions<Long> sessions) {
        // 世代だけを作るテスト。完了処理は track を読まないため null で十分。
        return sessions.start(KEY, null, 0L, 0, 0, false).token();
    }
}
