package com.kuronami.musicdiscmaker.event;

/**
 * ブームボックスの走査 1 回ぶんの判断を取り出した純ロジック (MC 非依存)。
 *
 * <p>{@code BoomboxPlayback} から分けてあるのは、あちらが {@code ServerPlayer} と payload 送信を
 * 掴んでいて素の単体判定ができないため ({@code PlaybackSessions} と同じ規律)。ここには
 * 「いつ撃つか・いつ終わるか」の規則だけを置く。
 *
 * <p><b>このクラスだけは帯で切っていない</b> (MC の型を 1 つも参照しない)。common の JUnit は
 * 全ノードで同じソースを走らせるので、帯で切ると 1.20.1 ノードのテストがコンパイルできず、
 * 判断ロジックが<b>どのノードでも検査されない</b>ことになる。1.20.1 の jar には class が 1 本
 * 入るが、そこから呼ぶものは何も無い。
 */
public final class BoomboxHeartbeat {

    /** 走査 1 回でその再生に対して取る行動。 */
    public enum Action {
        /** 新しい再生セッションを起こして offset 0 から撃つ (曲が差し替わった)。 */
        START,
        /** 現在位置を載せて撃ち直す (late-join・生存確認を兼ねる)。 */
        KEEP_ALIVE,
        /** 何もしない (前回の送信から間隔が空いていない)。 */
        IDLE,
        /** 尺を使い切った。停止する。 */
        END
    }

    private BoomboxHeartbeat() {
    }

    /**
     * @param sessionLive     この個体の再生セッションが生きているか
     * @param urlChanged      セッションの曲と、いま入っているディスクの曲が違うか
     * @param elapsedMs       セッション開始からの経過 (壁時計)。基準は<b>server が撃った時刻</b>
     * @param durationMs      曲の尺。0 以下 = 無限長 (ライブ/ラジオ) で終端が無い
     * @param sinceLastSendMs 前回 payload を撃ってからの経過
     * @param heartbeatMs     keep-alive の間隔
     * @param tailGraceMs     終端を待つ猶予。{@code elapsedMs} が server 送信時刻基準なのに対し
     *                        client の音はストリーム確立ぶん遅れて立つので、これが 0 だと
     *                        <b>毎曲の末尾がその遅れぶん切れる</b>
     * @return 取るべき行動
     */
    public static Action decide(boolean sessionLive, boolean urlChanged, long elapsedMs, long durationMs,
            long sinceLastSendMs, long heartbeatMs, long tailGraceMs) {
        if (!sessionLive || urlChanged) {
            return Action.START;
        }
        // 無限長 (ラジオ/ライブ) は終端を持たない。尺で切ると放送の途中で黙る。
        if (durationMs > 0L) {
            if (elapsedMs >= durationMs + Math.max(0L, tailGraceMs)) {
                return Action.END;
            }
            if (elapsedMs >= durationMs) {
                // 猶予帯: 鳴っている client に末尾を鳴らし切らせる。ここで撃たないのが肝で、
                // 撃つと「尺を過ぎた offset で開く → 即 EOF → 次の心拍で START」を猶予のあいだ
                // 1Hz で繰り返す client が出る。
                return Action.IDLE;
            }
        }
        return sinceLastSendMs >= heartbeatMs ? Action.KEEP_ALIVE : Action.IDLE;
    }

    /**
     * payload に載せる再生位置。
     *
     * <p>無限長ストリーム (ライブ/ラジオ) に位置の概念は無い。経過を載せると、後から近づいた
     * player の late-join が「10 分地点から」ストリームを開こうとする。常にライブ先頭へ繋ぐ。
     *
     * <p><b>ここで {@code tailGraceMs} のような遅れの見積もりを引かないこと。</b> client の
     * {@code PlaybackSessions#isSeekRequest} は「載っている offset」と「自分が推定する現在位置」を
     * {@code SEEK_TOLERANCE_MS} (1.2 秒) で突き合わせる。数秒ずらした値を載せると keep-alive の
     * たびに本物のシークと誤判定され、1 秒ごとに鳴らし直す。猶予は<b>終端判定にだけ</b>効かせる。
     *
     * @param live      無限長ストリームか
     * @param elapsedMs セッション開始からの経過
     * @return payload の {@code startOffsetMs}
     */
    public static long offsetFor(boolean live, long elapsedMs) {
        return live ? 0L : Math.max(0L, elapsedMs);
    }
}
