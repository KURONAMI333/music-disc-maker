package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * ブームボックスの生存判定を固定する。
 *
 * <h2>ここで守っているもの</h2>
 * <ul>
 *   <li><b>末尾を切らない</b>: server の猶予帯 (終端後 8 秒の沈黙) のあいだ client が止まらない。
 *       これが無いと毎曲の末尾が 3 秒で切れる</li>
 *   <li><b>無条件に伸ばさない</b>: 曲の途中で server が消えたら従来どおり止まる</li>
 *   <li><b>窓の外は救わない</b>: 猶予を過ぎたら止まる</li>
 *   <li><b>ラジオは窓を持たない</b>: 終端が無いので従来の途絶判定のまま</li>
 * </ul>
 */
class BoomboxLivenessTest {

    private static final long TIMEOUT = 3_000L;
    private static final long GRACE = 8_000L;
    private static final long MARGIN = BoomboxLiveness.TAIL_MARGIN_MS;
    private static final long END = 60_000L;

    private static BoomboxLiveness.TailSilence tail() {
        return BoomboxLiveness.tailSilence(END, GRACE);
    }

    @Test
    void windowOpensBeforeTheEndAndClosesAfterTheGrace() {
        assertEquals(new BoomboxLiveness.TailSilence(END - MARGIN, END + GRACE + MARGIN), tail());
    }

    @Test
    void noWindowWithoutAnEnd() {
        assertEquals(BoomboxLiveness.TailSilence.NONE, BoomboxLiveness.tailSilence(0L, GRACE));
        assertFalse(BoomboxLiveness.TailSilence.NONE.covers(END));
    }

    @Test
    void aliveWhileKeepAliveArrives() {
        assertTrue(BoomboxLiveness.isAlive(30_000L, 30_000L - TIMEOUT, TIMEOUT, tail()));
    }

    @Test
    void diesMidTrackWhenTheServerVanishes() {
        // 曲の途中 (10 秒地点) で心拍が止まった。あとから末尾の窓に入っても救わない。
        assertFalse(BoomboxLiveness.isAlive(END + 1_000L, 10_000L, TIMEOUT, tail()));
    }

    @Test
    void staysAliveThroughTheTailGrace() {
        // 最後の心拍は終端 1.5 秒前。猶予帯の内側では黙っていても生存と見なす。
        final long lastSeen = END - 1_500L;
        assertTrue(BoomboxLiveness.isAlive(END + 1_000L, lastSeen, TIMEOUT, tail()));
        assertTrue(BoomboxLiveness.isAlive(END + GRACE, lastSeen, TIMEOUT, tail()));
    }

    @Test
    void diesAfterTheWindow() {
        assertFalse(BoomboxLiveness.isAlive(END + GRACE + MARGIN + 1L, END - 1_500L, TIMEOUT, tail()));
    }

    @Test
    void diesBeforeTheWindowOpens() {
        // 窓が開く 5 秒前 (終端 5 秒前) から 4 秒黙った = 末尾の話ではない。
        assertFalse(BoomboxLiveness.isAlive(END - 5_000L, END - 9_000L, TIMEOUT, tail()));
    }

    @Test
    void radioHasNoWindow() {
        assertFalse(BoomboxLiveness.isAlive(30_000L, 20_000L, TIMEOUT, BoomboxLiveness.TailSilence.NONE));
    }
}
