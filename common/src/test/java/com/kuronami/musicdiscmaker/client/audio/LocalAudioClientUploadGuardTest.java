package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * upload スロットの同一性ガードを固定する。×連打・picker 取消・画面 close・
 * 完了直前の取消で残る遅延 callback が、次の取込や状態表示を壊さないための規則。
 * Minecraft は test runtime に無いので menu 実体は使わず (null)、
 * MC 非依存の {@link LocalAudioClient.Session} を直接駆動する。
 */
class LocalAudioClientUploadGuardTest {

    @Test
    void doubleClearIsNoOp() {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        LocalAudioClient.Upload u = new LocalAudioClient.Upload(null, 1, session.connectionGeneration);
        session.upload = u;
        assertTrue(session.clearIfCurrent(u));
        // × の二重クリックや取消直後の遅延失敗 callback が同じ upload に二度来ても無害。
        assertFalse(session.clearIfCurrent(u));
        assertNull(session.upload);
    }

    @Test
    void staleUploadCannotDislodgeNewerTransfer() {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        LocalAudioClient.Upload stale = new LocalAudioClient.Upload(null, 1, session.connectionGeneration);
        session.upload = stale;
        LocalAudioClient.Upload fresh = new LocalAudioClient.Upload(null, 2, session.connectionGeneration);
        session.upload = fresh;
        // 完了直前に取消した旧操作の失敗通知が来ても、新しい操作と状態を守る。
        assertFalse(session.clearIfCurrent(stale));
        assertFalse(session.live(stale));
        assertTrue(session.live(fresh));
        assertSame(fresh, session.upload);
        assertTrue(session.clearIfCurrent(fresh));
        assertNull(session.upload);
    }

    @Test
    void clearedUploadIsNotLive() {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        LocalAudioClient.Upload u = new LocalAudioClient.Upload(null, 1, session.connectionGeneration);
        session.upload = u;
        session.clearIfCurrent(u);
        // picker 取消・画面 close の後に到着する遅延 ACK/失敗は live 判定で捨てる。
        assertFalse(session.live(u));
    }

    /**
     * worker から main queue へ載った遅延書込み ({@code ifLive}) は、それが実行されるまでに
     * 取消・切断・新しい選択が走っていれば捨てる。live 中は普通に通る。
     */
    @Test
    void ifLiveDropsLateWorkerWritesAfterAbortDisconnectAndReselect() {
        AtomicBoolean applied = new AtomicBoolean();
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        LocalAudioClient.Upload u = new LocalAudioClient.Upload(null, 1, session.connectionGeneration);
        session.upload = u;

        session.ifLive(u, () -> applied.set(true));
        assertTrue(applied.get()); // 取消前の正常系では書き込める
        applied.set(false);

        session.clearIfCurrent(u); // abort (取消) が main で先に走った後の worker 後着
        session.ifLive(u, () -> applied.set(true));
        assertFalse(applied.get());

        LocalAudioClient.Session disconnected = new LocalAudioClient.Session();
        LocalAudioClient.Upload cut = new LocalAudioClient.Upload(null, 1, disconnected.connectionGeneration);
        disconnected.upload = cut;
        disconnected.connectionGeneration++; // 切断 = 世代前進
        disconnected.ifLive(cut, () -> applied.set(true));
        assertFalse(applied.get());

        LocalAudioClient.Session reselected = new LocalAudioClient.Session();
        LocalAudioClient.Upload stale = new LocalAudioClient.Upload(null, 1, reselected.connectionGeneration);
        reselected.upload = stale;
        reselected.upload = new LocalAudioClient.Upload(null, 2, reselected.connectionGeneration); // 新しい選択
        reselected.ifLive(stale, () -> applied.set(true));
        assertFalse(applied.get());
    }

    @Test
    void disconnectGenerationInvalidatesInFlightUpload() {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        LocalAudioClient.Upload u = new LocalAudioClient.Upload(null, 1, session.connectionGeneration);
        session.upload = u;
        assertTrue(session.live(u));
        // 切断 (disconnect) と同じ世代前進。旧世代の upload は生きていないと見なす。
        session.connectionGeneration++;
        assertFalse(session.live(u));
        assertTrue(session.clearIfCurrent(u));
    }
}
