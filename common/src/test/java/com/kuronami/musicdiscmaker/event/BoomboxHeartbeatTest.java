package com.kuronami.musicdiscmaker.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * ブームボックスの心拍 1 発ぶんの判断を固定する。
 *
 * <h2>ここで守っているもの</h2>
 * <ul>
 *   <li><b>1 tick ごとに payload を撃たない</b>: 間隔が空いていなければ黙る</li>
 *   <li><b>末尾を切らない</b>: 尺を過ぎてから猶予のあいだは撃たず、猶予を過ぎてから止める。
 *       猶予帯で撃つと「尺を過ぎた offset で開く → 即 EOF → 次の心拍で START」を繰り返す</li>
 *   <li><b>ラジオを尺で切らない</b>: 無限長は終端を持たない</li>
 *   <li><b>再生位置に遅れの見積もりを混ぜない</b>: 混ぜると client のシーク判定
 *       ({@code PlaybackSessions#isSeekRequest} の許容 1.2 秒) を毎心拍で誤らせて鳴らし直す</li>
 * </ul>
 */
class BoomboxHeartbeatTest {

    private static final long HEARTBEAT = 1_000L;
    private static final long TAIL_GRACE = 8_000L;
    private static final long DURATION = 60_000L;

    @Test
    void startsWhenNoSessionExists() {
        assertEquals(BoomboxHeartbeat.Action.START,
                BoomboxHeartbeat.decide(false, false, 0L, DURATION, 0L, HEARTBEAT, TAIL_GRACE));
    }

    @Test
    void restartsWhenTheDiscChanged() {
        assertEquals(BoomboxHeartbeat.Action.START,
                BoomboxHeartbeat.decide(true, true, 30_000L, DURATION, 0L, HEARTBEAT, TAIL_GRACE));
    }

    @Test
    void staysQuietBeforeTheHeartbeatInterval() {
        assertEquals(BoomboxHeartbeat.Action.IDLE,
                BoomboxHeartbeat.decide(true, false, 5_000L, DURATION, HEARTBEAT - 1, HEARTBEAT, TAIL_GRACE));
    }

    @Test
    void sendsOnceTheIntervalElapsed() {
        assertEquals(BoomboxHeartbeat.Action.KEEP_ALIVE,
                BoomboxHeartbeat.decide(true, false, 5_000L, DURATION, HEARTBEAT, HEARTBEAT, TAIL_GRACE));
    }

    @Test
    void doesNotSendInsideTheTailGrace() {
        assertEquals(BoomboxHeartbeat.Action.IDLE, BoomboxHeartbeat.decide(true, false,
                DURATION + TAIL_GRACE - 1, DURATION, HEARTBEAT * 10, HEARTBEAT, TAIL_GRACE));
    }

    @Test
    void endsAfterTheTailGrace() {
        assertEquals(BoomboxHeartbeat.Action.END, BoomboxHeartbeat.decide(true, false,
                DURATION + TAIL_GRACE, DURATION, HEARTBEAT * 10, HEARTBEAT, TAIL_GRACE));
    }

    @Test
    void endsExactlyAtTheDurationWhenThereIsNoGrace() {
        assertEquals(BoomboxHeartbeat.Action.END,
                BoomboxHeartbeat.decide(true, false, DURATION, DURATION, HEARTBEAT, HEARTBEAT, 0L));
    }

    @Test
    void neverEndsAnEndlessStream() {
        assertEquals(BoomboxHeartbeat.Action.KEEP_ALIVE,
                BoomboxHeartbeat.decide(true, false, 3_600_000L, 0L, HEARTBEAT, HEARTBEAT, TAIL_GRACE));
    }

    @Test
    void endlessStreamsAlwaysOpenAtTheHead() {
        assertEquals(0L, BoomboxHeartbeat.offsetFor(true, 3_600_000L));
    }

    @Test
    void finiteTracksCarryTheRawElapsed() {
        assertEquals(12_345L, BoomboxHeartbeat.offsetFor(false, 12_345L));
        assertEquals(0L, BoomboxHeartbeat.offsetFor(false, -5L));
    }
}
