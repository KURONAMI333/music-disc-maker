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

    @Test
    void restoreIsAcceptedInAFreeSlotWithAValidFile() {
        String hash = "a".repeat(64);
        assertNull(LocalServerDecisions.restoreRejection(true, true, false, hash, 10, 100));
    }

    /** 枠・本人の同時 1 本・同じ hash の先着 1 本。すべて「待てば通る」なので busy。 */
    @Test
    void restoreIsBusyWhenAnotherTransferHoldsTheSlotOrTheHash() {
        String hash = "a".repeat(64);
        assertEquals("busy", LocalServerDecisions.restoreRejection(false, true, false, hash, 10, 100));
        assertEquals("busy", LocalServerDecisions.restoreRejection(true, false, false, hash, 10, 100));
        assertEquals("busy", LocalServerDecisions.restoreRejection(true, true, true, hash, 10, 100));
    }

    /** 形式の不正は待っても通らないので取込失敗として終える。 */
    @Test
    void restoreRejectsMalformedRequests() {
        String hash = "a".repeat(64);
        assertEquals("import_failed", LocalServerDecisions.restoreRejection(true, true, false, "xyz", 10, 100));
        assertEquals("import_failed", LocalServerDecisions.restoreRejection(true, true, false, null, 10, 100));
        assertEquals("import_failed", LocalServerDecisions.restoreRejection(true, true, false, hash, 0, 100));
        assertEquals("import_failed", LocalServerDecisions.restoreRejection(true, true, false, hash, 101, 100));
    }

    /** 完成原本が要求された hash の名前で置かれたか。中身の一致は store 側が digest で見る。 */
    @Test
    void storedNameMustBeTheRequestedHash() {
        String hash = "b".repeat(64);
        assertTrue(LocalServerDecisions.storedUnderRequestedHash(hash, hash));
        assertTrue(LocalServerDecisions.storedUnderRequestedHash(hash, hash + ".mp3"));
        assertFalse(LocalServerDecisions.storedUnderRequestedHash(hash, "c".repeat(64)));
        assertFalse(LocalServerDecisions.storedUnderRequestedHash(hash, null));
        assertFalse(LocalServerDecisions.storedUnderRequestedHash(null, hash));
    }
}
