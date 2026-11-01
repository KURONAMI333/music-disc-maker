package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 白紙の盤が無い間、選択済みの取込を保持して待つ規則。 */
class DiscWaitTest {

    @Test
    void sendsOnceWhenDiscArrives() {
        DiscWait wait = new DiscWait();
        wait.begin(false); // 盤が無いままファイルを選んだ
        assertFalse(wait.poll(false));
        assertFalse(wait.poll(false));
        assertTrue(wait.poll(true)); // 盤を入れた tick
        assertFalse(wait.waiting());
        assertFalse(wait.poll(true)); // 二度は撃たない
    }

    @Test
    void serverRejectionWhileClientLooksReadyDoesNotRefire() {
        DiscWait wait = new DiscWait();
        wait.begin(true); // client は整っているのにサーバーが "disc" を返した
        for (int i = 0; i < 100; i++) assertFalse(wait.poll(true));
        assertTrue(wait.waiting());
        // 条件がいったん崩れて整い直した時だけ撃ち直す。
        assertFalse(wait.poll(false));
        assertTrue(wait.poll(true));
        assertFalse(wait.poll(true));
    }

    @Test
    void notWaitingNeverFires() {
        DiscWait wait = new DiscWait();
        assertFalse(wait.poll(true));
        wait.begin(false);
        wait.clear(); // ×・画面 close・別ファイル選択
        assertFalse(wait.poll(true));
    }

    @Test
    void onlyDiscRejectionBeforeAnyBytesReturnsToWaiting() {
        assertTrue(LocalImportDecisions.waitsForDisc("disc", 0));
        assertFalse(LocalImportDecisions.waitsForDisc("disc", 16384)); // 送信後の拒否は終える
        assertFalse(LocalImportDecisions.waitsForDisc("busy", 0));
        assertFalse(LocalImportDecisions.waitsForDisc("quota", 0));
        assertFalse(LocalImportDecisions.waitsForDisc(null, 0));
    }
}
