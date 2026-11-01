package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** "busy" で断られた取込を、間を空けて撃ち直す予定。 */
class BusyRetryTest {

    @Test
    void retriesAfterOneSecondThenDoubles() {
        BusyRetry r = new BusyRetry();
        r.onBusy(0);
        assertEquals(BusyRetry.Poll.NONE, r.poll(999));
        assertEquals(BusyRetry.Poll.RETRY, r.poll(1000));
        assertEquals(BusyRetry.Poll.NONE, r.poll(1001)); // 次の busy まで撃たない
        r.onBusy(1100);
        assertEquals(BusyRetry.Poll.NONE, r.poll(3099));
        assertEquals(BusyRetry.Poll.RETRY, r.poll(3100)); // 2 秒後
        r.onBusy(3200);
        assertEquals(BusyRetry.Poll.RETRY, r.poll(7200)); // 4 秒後
        r.onBusy(7300);
        assertEquals(BusyRetry.Poll.RETRY, r.poll(15300)); // 8 秒後
        r.onBusy(15400);
        assertEquals(BusyRetry.Poll.NONE, r.poll(23399));
        assertEquals(BusyRetry.Poll.RETRY, r.poll(23400)); // 上限 8 秒のまま
    }

    @Test
    void givesUpOnceAfterAboutSixtySeconds() {
        BusyRetry r = new BusyRetry();
        r.onBusy(0);
        r.poll(1000);
        r.onBusy(1000);
        assertEquals(BusyRetry.Poll.GIVE_UP, r.poll(60_000));
        assertEquals(BusyRetry.Poll.NONE, r.poll(61_000));
        assertFalse(r.waiting());
    }

    @Test
    void clearedOrIdleNeverFires() {
        BusyRetry r = new BusyRetry();
        assertEquals(BusyRetry.Poll.NONE, r.poll(1_000_000));
        r.onBusy(0);
        r.clear(); // READY を受けた・取消
        assertEquals(BusyRetry.Poll.NONE, r.poll(1_000_000));
        r.onBusy(5000); // 新しい連鎖は 1 秒から
        assertEquals(BusyRetry.Poll.RETRY, r.poll(6000));
    }

    @Test
    void onlyBusyBeforeAnyBytesIsRetried() {
        assertTrue(LocalImportDecisions.retriesBusy("busy", 0));
        assertFalse(LocalImportDecisions.retriesBusy("busy", 16384));
        assertFalse(LocalImportDecisions.retriesBusy("disc", 0));
        assertFalse(LocalImportDecisions.retriesBusy(null, 0));
    }

    @Test
    void sessionTickRetriesAndGivesUp() {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        LocalAudioClient.Upload u = new LocalAudioClient.Upload(null, 1, session.connectionGeneration);
        session.upload = u;
        u.busyRetry.onBusy(0);
        assertEquals(LocalAudioClient.Session.Step.NONE, session.tick(null, null, true, 500));
        assertEquals(LocalAudioClient.Session.Step.RETRY, session.tick(null, null, true, 1000));
        u.busyRetry.onBusy(1000);
        assertEquals(LocalAudioClient.Session.Step.GIVE_UP, session.tick(null, null, true, 61_000));
    }

    @Test
    void discWaitDropsTheEarlierBusyChain() {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        LocalAudioClient.Upload u = new LocalAudioClient.Upload(null, 1, session.connectionGeneration);
        session.upload = u;
        u.busyRetry.onBusy(0);
        u.enterDiscWait(true); // 盤が外れて "disc" の待ちに入った
        assertFalse(u.busyRetry.waiting());
        u.busyRetry.onBusy(100_000); // 長い待ちの後の busy は新しい連鎖
        assertEquals(BusyRetry.Poll.NONE, u.busyRetry.poll(100_500));
        assertEquals(BusyRetry.Poll.RETRY, u.busyRetry.poll(101_000));
    }
}
