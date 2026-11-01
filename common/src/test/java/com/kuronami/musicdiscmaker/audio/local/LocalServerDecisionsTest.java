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
    void onlyVmErrorsAreFatalForLogging() {
        assertFalse(LocalServerDecisions.vmFatal(new IOException("x")));
        assertFalse(LocalServerDecisions.vmFatal(new LinkageError("x")));
        assertFalse(LocalServerDecisions.vmFatal(new NoClassDefFoundError("x")));
        assertTrue(LocalServerDecisions.vmFatal(new OutOfMemoryError()));
        assertTrue(LocalServerDecisions.vmFatal(new StackOverflowError()));
    }

    @Test
    void beginRejectionsAreNotDiscWaits() {
        String hash = "a".repeat(64);
        assertNull(LocalServerDecisions.beginRejection(true, hash, 10, 100));
        assertEquals("expired", LocalServerDecisions.beginRejection(false, hash, 10, 100));
        assertEquals("import_failed", LocalServerDecisions.beginRejection(true, "xyz", 10, 100));
        assertEquals("import_failed", LocalServerDecisions.beginRejection(true, null, 10, 100));
        assertEquals("import_failed", LocalServerDecisions.beginRejection(true, hash, 0, 100));
        assertEquals("import_failed", LocalServerDecisions.beginRejection(true, hash, 101, 100));
    }
}
