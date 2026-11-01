package com.kuronami.musicdiscmaker.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * client が同じ (製作機, URL) を引き直す記録の寿命。焼けた後・URL 変更後・時間切れの後に、
 * もう一度試せることを見る (記録が残り続けると二度と引き直せない)。あわせて、同じ失敗を
 * 何度も繰り返さないための上限 (回数・取得中・間隔) も見る。
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

    /** 取得中は次の試行を始めない (同じ URL を並行して引かない)。 */
    @Test void refusesASecondClaimWhileTheFirstIsRunning() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        assertTrue(attempts.claim(POS, URL, 0L));
        // 取得には数秒かかる。その間に画面が tick しても始めない。
        assertFalse(attempts.claim(POS, URL, 5_000L));
        assertFalse(attempts.claim(POS, URL, ClientResolveAttempts.RETRY_AFTER_MS * 10L));
        attempts.finish(POS, URL, ClientResolveAttempts.RETRY_AFTER_MS * 10L);
        // 終わった直後も、間隔が空くまでは始めない。
        assertFalse(attempts.claim(POS, URL, ClientResolveAttempts.RETRY_AFTER_MS * 10L + 1L));
    }

    /** 間隔は取得の開始ではなく終了から数える (取得に時間がかかった分だけ待つ)。 */
    @Test void theRetryDelayRunsFromTheEndOfTheAttempt() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        assertTrue(attempts.claim(POS, URL, 0L));
        attempts.finish(POS, URL, 30_000L); // 取得に 30 秒かかった
        assertFalse(attempts.claim(POS, URL, 30_000L + ClientResolveAttempts.RETRY_AFTER_MS - 1L));
        assertTrue(attempts.claim(POS, URL, 30_000L + ClientResolveAttempts.RETRY_AFTER_MS));
    }

    /** 同じ組は 3 回まで。回数は release (焼けた・URL が変わった) で戻る。 */
    @Test void stopsAfterThreeAttemptsUntilTheRecordIsReleased() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        long now = 0L;
        for (int i = 0; i < ClientResolveAttempts.MAX_ATTEMPTS_PER_KEY; i++) {
            assertTrue(attempts.claim(POS, URL, now), "試行 " + (i + 1));
            attempts.finish(POS, URL, now);
            now += ClientResolveAttempts.RETRY_AFTER_MS;
        }
        assertFalse(attempts.claim(POS, URL, now), "上限の回数を超えて始めている");
        assertFalse(attempts.claim(POS, URL, now + ClientResolveAttempts.RETRY_AFTER_MS * 100L),
                "時間が経っても回数は増えない");

        attempts.release(POS);
        assertTrue(attempts.claim(POS, URL, now), "release で回数が戻る");
    }

    /** 接続が変わったら回数も戻る。 */
    @Test void clearResetsTheAttemptCount() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        long now = 0L;
        for (int i = 0; i < ClientResolveAttempts.MAX_ATTEMPTS_PER_KEY; i++) {
            assertTrue(attempts.claim(POS, URL, now));
            attempts.finish(POS, URL, now);
            now += ClientResolveAttempts.RETRY_AFTER_MS;
        }
        assertFalse(attempts.claim(POS, URL, now));

        attempts.clear();
        assertTrue(attempts.claim(POS, URL, now), "clear で回数が戻る");
    }

    /** release の後に届いた終了報告で記録を作り直さない (回数を戻したままにする)。 */
    @Test void finishingAReleasedAttemptDoesNotResurrectIt() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        assertTrue(attempts.claim(POS, URL, 0L));
        attempts.release(POS); // 取得中に画面が閉じた
        attempts.finish(POS, URL, 1_000L); // 遅れて届いた終了報告

        assertEquals(0, attempts.size());
        assertTrue(attempts.claim(POS, URL, 1_001L), "間隔も回数も持ち越さない");
    }

    /** 入れ直した記録は追い出し順の末尾へ移る (古い記録から捨てる)。 */
    @Test void reRegisteringMovesTheAttemptToTheEndOfTheEvictionOrder() {
        final ClientResolveAttempts attempts = new ClientResolveAttempts();
        for (int i = 0; i < ClientResolveAttempts.MAX_ATTEMPTS; i++) {
            assertTrue(attempts.claim(i, URL, 0L));
            attempts.finish(i, URL, 0L);
        }
        // 先頭の記録をもう一度試す。put だけで入れ直すと挿入順が先頭のまま残ってしまう。
        assertTrue(attempts.claim(0L, URL, ClientResolveAttempts.RETRY_AFTER_MS));
        attempts.finish(0L, URL, ClientResolveAttempts.RETRY_AFTER_MS);

        assertTrue(attempts.claim(ClientResolveAttempts.MAX_ATTEMPTS, URL, 0L), "上限を超えたら追い出す");
        assertTrue(attempts.claim(1L, URL, 0L), "追い出されたのは移す前の先頭 (pos 1)");
        assertFalse(attempts.claim(0L, URL, ClientResolveAttempts.RETRY_AFTER_MS + 1L),
                "試し直した pos 0 は残っている");
    }
}
