package com.kuronami.musicdiscmaker.client.audio;

/**
 * サーバーが "busy" (同時転送の上限・前の転送の掃除待ち・連投制限) で BEGIN を断った時の撃ち直しの予定。
 * 待てば通る失敗なので取込を捨てず、1 秒から倍々 (最大 8 秒) で間を空け、最初の busy から約 60 秒で諦める。
 * Minecraft に依存しない。時刻はミリ秒の単調時計を呼び出し側が渡す。
 */
final class BusyRetry {
    enum Poll { NONE, RETRY, GIVE_UP }

    static final long FIRST_DELAY_MS = 1_000L;
    static final long MAX_DELAY_MS = 8_000L;
    static final long GIVE_UP_MS = 60_000L;

    private boolean waiting;
    /** 最初の busy から続く一連の撃ち直しの中か (諦めるまでの時計と間隔を引き継ぐ)。 */
    private boolean chain;
    private long start, due, delay;

    /** busy を受けた。連続した busy なら最初の時刻と伸びた間隔を引き継ぐ。 */
    void onBusy(long nowMs) {
        if (!chain) { chain = true; start = nowMs; delay = FIRST_DELAY_MS; }
        waiting = true;
        due = nowMs + delay;
    }

    boolean waiting() { return waiting; }

    void clear() { waiting = false; chain = false; }

    /** 毎 tick の観測。撃ち直す瞬間は RETRY、待ち切れなければ GIVE_UP (一度だけ)。 */
    Poll poll(long nowMs) {
        if (!waiting) return Poll.NONE;
        if (nowMs - start >= GIVE_UP_MS) { clear(); return Poll.GIVE_UP; }
        if (nowMs < due) return Poll.NONE;
        waiting = false; // 次の busy まで待たない。間隔だけ伸ばして chain は保つ
        delay = Math.min(delay * 2, MAX_DELAY_MS);
        return Poll.RETRY;
    }
}
