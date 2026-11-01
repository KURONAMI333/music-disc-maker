package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 原本を持っている client が、サーバーへ上げ直してよいかの判定。
 * missing を受けた時だけ・キャッシュが一致する時だけ・1 本だけ・連打しない。
 */
class LocalRestoreDecisionsTest {

    private static final UUID REQUEST = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SERVER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Test
    void restoresOnlyWhenEveryConditionHolds() {
        assertTrue(LocalRestoreDecisions.shouldRestore(true, true, true, false, Long.MAX_VALUE, 60_000));
        assertTrue(LocalRestoreDecisions.shouldRestore(true, true, true, false, 60_000, 60_000));
    }

    /** サーバーが「原本が無い」と答えた hash 以外は送らない (任意の hash で store を埋めさせない)。 */
    @Test
    void doesNotRestoreWithoutAMissingAnswer() {
        assertFalse(LocalRestoreDecisions.shouldRestore(false, true, true, false, Long.MAX_VALUE, 60_000));
    }

    /** 中身が手元に無ければ送れない。 */
    @Test
    void doesNotRestoreWithoutAVerifiedCopy() {
        assertFalse(LocalRestoreDecisions.shouldRestore(true, false, true, false, Long.MAX_VALUE, 60_000));
    }

    /** 旧サーバーは RESTORE を復号できず接続が切れるので、版が食い違う相手には送らない。 */
    @Test
    void doesNotRestoreWhenTheServerCannotDecodeIt() {
        assertFalse(LocalRestoreDecisions.shouldRestore(true, true, false, false, Long.MAX_VALUE, 60_000));
    }

    /** 同じ hash の上げ直しは 1 本だけ。 */
    @Test
    void doesNotStartASecondTransferForTheSameHash() {
        assertFalse(LocalRestoreDecisions.shouldRestore(true, true, true, true, Long.MAX_VALUE, 60_000));
    }

    /** 直前に試した hash は、間隔が過ぎるまで送り直さない。 */
    @Test
    void waitsAfterAFailedAttempt() {
        assertFalse(LocalRestoreDecisions.shouldRestore(true, true, true, false, 0, 60_000));
        assertFalse(LocalRestoreDecisions.shouldRestore(true, true, true, false, 59_999, 60_000));
        assertTrue(LocalRestoreDecisions.shouldRestore(true, true, true, false, 60_001, 60_000));
    }

    /**
     * サーバーは RESTORE_READY に自分が store に開いた id を載せる。client が振った request とは
     * 違うので、最初の READY は id を見ずに受ける (見落とすと上げ直しが一度も始まらない)。
     */
    @Test
    void acceptsTheFirstReadyWithTheServersOwnId() {
        assertTrue(LocalRestoreDecisions.acceptsRestoreReply(true, null, REQUEST, SERVER_ID));
    }

    /** READY を受けた後は、その id の応答だけを受ける (別の転送の包を巻き取らない)。 */
    @Test
    void acceptsOnlyTheTransferIdAfterReady() {
        assertTrue(LocalRestoreDecisions.acceptsRestoreReply(false, SERVER_ID, REQUEST, SERVER_ID));
        assertFalse(LocalRestoreDecisions.acceptsRestoreReply(false, SERVER_ID, REQUEST, REQUEST));
        assertFalse(LocalRestoreDecisions.acceptsRestoreReply(true, SERVER_ID, REQUEST, REQUEST));
    }

    /** READY より前の ACK・DONE・ERROR は、従来どおりこちらが振った request で照合する。 */
    @Test
    void matchesTheRequestBeforeTheTransferStarts() {
        assertTrue(LocalRestoreDecisions.acceptsRestoreReply(false, null, REQUEST, REQUEST));
        assertFalse(LocalRestoreDecisions.acceptsRestoreReply(false, null, REQUEST, SERVER_ID));
    }
}
