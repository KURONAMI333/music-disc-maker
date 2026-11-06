package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 同じ音源の同じ失敗を 1 回だけ報告する。トロッコの失敗をアクションバーへ出す判定も
 * この抑止に乗せている ({@code GoldenCartClientPlayback#notifyFailureOnce})。
 */
class PlaybackFailureNoticesTest {

    @Test
    void theSameFailureIsReportedOncePerSource() {
        final PlaybackFailureNotices notices = new PlaybackFailureNotices();
        final PlaybackFailure dead = PlaybackFailure.streamUnavailable();

        assertTrue(notices.shouldReport("cart-a", dead));
        assertFalse(notices.shouldReport("cart-a", dead),
                "the 1 Hz keep-alive must not repeat the same action bar line");
        assertTrue(notices.shouldReport("cart-b", dead), "another source has its own memory");
    }

    @Test
    void aChangedReasonIsNewInformation() {
        final PlaybackFailureNotices notices = new PlaybackFailureNotices();

        assertTrue(notices.shouldReport("cart-a", PlaybackFailure.streamUnavailable()));
        assertTrue(notices.shouldReport("cart-a", PlaybackFailure.soundMuted()));
    }

    @Test
    void forgettingASourceAllowsTheSameFailureAgain() {
        final PlaybackFailureNotices notices = new PlaybackFailureNotices();
        final PlaybackFailure dead = PlaybackFailure.streamUnavailable();

        assertTrue(notices.shouldReport("cart-a", dead));
        notices.forget("cart-a");
        assertTrue(notices.shouldReport("cart-a", dead));

        assertFalse(notices.shouldReport("cart-a", dead));
        notices.forgetAll();
        assertTrue(notices.shouldReport("cart-a", dead));
    }
}
