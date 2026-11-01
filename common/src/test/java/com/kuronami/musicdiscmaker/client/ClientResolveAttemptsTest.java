package com.kuronami.musicdiscmaker.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * client が同じ (製作機, URL) を引き直す記録の寿命。焼けた後・URL 変更後・時間切れの後に、
 * もう一度試せることを見る (記録が残り続けると二度と引き直せない)。
 */
class ClientResolveAttemptsTest {

    private static final long POS = 12_345L;
    private static final long OTHER_POS = 67_890L;
    private static final String URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String OTHER_URL = "https://www.youtube.com/watch?v=aaaaaaaaaaa";

    @Test void claimsOnceAndRefusesAgainUntilReleased() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        assertTrue(attempts.claim(POS, URL, 0L));
        // 同じ tick の次の呼び出し (画面の毎 tick) では引き直さない。
        assertFalse(attempts.claim(POS, URL, 1L));
        assertFalse(attempts.claim(POS, URL, ClientResolveAttempts.RETRY_AFTER_MS - 1L));
        attempts.release(POS);
        assertTrue(attempts.claim(POS, URL, 2L));
    }

    @Test void releaseOfAnotherMakerKeepsThisOne() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        assertTrue(attempts.claim(POS, URL, 0L));
        assertTrue(attempts.claim(OTHER_POS, URL, 0L));
        attempts.release(OTHER_POS);
        assertFalse(attempts.claim(POS, URL, 1L));
        assertTrue(attempts.claim(OTHER_POS, URL, 1L));
    }

    @Test void changingTheUrlAtTheSameMakerStartsANewAttempt() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        assertTrue(attempts.claim(POS, URL, 0L));
        assertTrue(attempts.claim(POS, OTHER_URL, 1L));
        assertFalse(attempts.claim(POS, OTHER_URL, 2L));
        // 製作機ごとに 1 件だけ持つ (前の URL の記録は捨てる)。
        assertEquals(1, attempts.size());
    }

    @Test void releaseOnlyDropsTheCurrentUrl() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        assertTrue(attempts.claim(POS, URL, 0L));
        assertTrue(attempts.claim(POS, OTHER_URL, 1L));
        attempts.release(POS);
        assertEquals(0, attempts.size());
        assertTrue(attempts.claim(POS, URL, 2L));
    }

    @Test void forgottenRecordExpiresSoTheClientCanTryAgain() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        assertTrue(attempts.claim(POS, URL, 0L));
        // 取得自体が失敗した時は release が届かない。時間で切れないと二度と引き直せない。
        assertFalse(attempts.claim(POS, URL, ClientResolveAttempts.RETRY_AFTER_MS - 1L));
        assertTrue(attempts.claim(POS, URL, ClientResolveAttempts.RETRY_AFTER_MS));
    }

    @Test void clearDropsEveryMaker() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        assertTrue(attempts.claim(POS, URL, 0L));
        assertTrue(attempts.claim(OTHER_POS, URL, 0L));
        attempts.clear();
        assertEquals(0, attempts.size());
        assertTrue(attempts.claim(POS, URL, 0L));
        assertTrue(attempts.claim(OTHER_POS, URL, 0L));
    }

    @Test void keepsTheRecordBounded() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        for (int i = 0; i < ClientResolveAttempts.MAX_ATTEMPTS; i++) {
            assertTrue(attempts.claim(i, URL, 0L), "pos=" + i);
        }
        assertEquals(ClientResolveAttempts.MAX_ATTEMPTS, attempts.size());
        // 上限を超えたら古い順に捨てるので、捨てられた位置はまた試せる。
        assertTrue(attempts.claim(ClientResolveAttempts.MAX_ATTEMPTS, URL, 0L));
        assertEquals(ClientResolveAttempts.MAX_ATTEMPTS, attempts.size());
        assertTrue(attempts.claim(0, URL, 0L));
        assertEquals(ClientResolveAttempts.MAX_ATTEMPTS, attempts.size());
    }
}
