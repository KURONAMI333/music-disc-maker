package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 原本を持っている client が、サーバーへ上げ直してよいかの判定。
 * missing を受けた時だけ・キャッシュが一致する時だけ・1 本だけ・連打しない。
 */
class LocalRestoreDecisionsTest {

    @Test
    void restoresOnlyWhenEveryConditionHolds() {
        assertTrue(LocalRestoreDecisions.shouldRestore(true, true, false, Long.MAX_VALUE, 60_000));
        assertTrue(LocalRestoreDecisions.shouldRestore(true, true, false, 60_000, 60_000));
    }

    /** サーバーが「原本が無い」と答えた hash 以外は送らない (任意の hash で store を埋めさせない)。 */
    @Test
    void doesNotRestoreWithoutAMissingAnswer() {
        assertFalse(LocalRestoreDecisions.shouldRestore(false, true, false, Long.MAX_VALUE, 60_000));
    }

    /** 中身が手元に無ければ送れない。 */
    @Test
    void doesNotRestoreWithoutAVerifiedCopy() {
        assertFalse(LocalRestoreDecisions.shouldRestore(true, false, false, Long.MAX_VALUE, 60_000));
    }

    /** 同じ hash の上げ直しは 1 本だけ。 */
    @Test
    void doesNotStartASecondTransferForTheSameHash() {
        assertFalse(LocalRestoreDecisions.shouldRestore(true, true, true, Long.MAX_VALUE, 60_000));
    }

    /** 直前に試した hash は、間隔が過ぎるまで送り直さない。 */
    @Test
    void waitsAfterAFailedAttempt() {
        assertFalse(LocalRestoreDecisions.shouldRestore(true, true, false, 0, 60_000));
        assertFalse(LocalRestoreDecisions.shouldRestore(true, true, false, 59_999, 60_000));
        assertTrue(LocalRestoreDecisions.shouldRestore(true, true, false, 60_001, 60_000));
    }
}
