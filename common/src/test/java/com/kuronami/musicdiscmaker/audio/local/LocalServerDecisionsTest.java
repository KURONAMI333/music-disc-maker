package com.kuronami.musicdiscmaker.audio.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class LocalServerDecisionsTest {

    @Test
    void dropOfLiveTransferToConnectedPlayerSendsExpired() {
        // menu を閉じた・差し替えた等でサーバーが破棄する転送は、client へ理由を返す。
        assertEquals("expired", LocalServerDecisions.dropReason(false, true));
    }

    @Test
    void noNoticeWhenAlreadyCancelledOrPlayerGone() {
        assertNull(LocalServerDecisions.dropReason(true, true)); // 取消・期限切れで通知済み
        assertNull(LocalServerDecisions.dropReason(false, false)); // 送り先が無い
        assertNull(LocalServerDecisions.dropReason(true, false));
    }


    @Test
    void errorsAreFoldedButVmErrorsAreNot() {
        assertTrue(LocalServerDecisions.recoverable(new IOException("x")));
        assertTrue(LocalServerDecisions.recoverable(new LinkageError("x")));
        assertTrue(LocalServerDecisions.recoverable(new NoClassDefFoundError("x")));
        assertFalse(LocalServerDecisions.recoverable(new OutOfMemoryError()));
        assertFalse(LocalServerDecisions.recoverable(new StackOverflowError()));
    }
}
