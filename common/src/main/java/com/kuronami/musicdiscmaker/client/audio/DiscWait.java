package com.kuronami.musicdiscmaker.client.audio;

/**
 * 製作機に白紙の盤が無い間、選択済みの取込を保持して待つ状態。Minecraft に依存しない。
 *
 * <p>盤の条件が整った時に一度だけ送信を許す。サーバーが「盤が無い」と返したのに
 * client の見た目の条件が既に整っていた (判定のずれ) 場合は、条件がいったん崩れて
 * 再び整うまで撃ち直さない。同じ BEGIN を無限に繰り返さないための規則。
 */
final class DiscWait {
    private boolean waiting;
    /** 待ち始めてから、条件が整っていない状態を一度でも見たか。 */
    private boolean armed;

    /** 待ちに入る。{@code ready} は今の見た目で条件が整っているか。 */
    void begin(boolean ready) {
        waiting = true;
        armed = !ready;
    }

    boolean waiting() { return waiting; }

    void clear() { waiting = false; armed = false; }

    /** 毎 tick の観測。送信してよい瞬間 (待ち中に整いに変わった時) だけ {@code true}。 */
    boolean poll(boolean ready) {
        if (!waiting) return false;
        if (!ready) { armed = true; return false; }
        if (!armed) return false;
        clear();
        return true;
    }
}
