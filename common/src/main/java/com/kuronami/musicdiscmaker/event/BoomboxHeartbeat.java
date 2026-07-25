package com.kuronami.musicdiscmaker.event;

/**
 * ブームボックスの走査 1 回ぶんの判断を取り出した純ロジック (MC 非依存)。
 *
 * <p>{@link BoomboxPlayback} から分けてあるのは、あちらが {@code ServerPlayer} と payload 送信を
 * 掴んでいて素の単体判定ができないため（{@code SpeakerSelection} / {@code AudioCacheGate} /
 * {@code PlaybackSessions} と同じ規律）。ここには「いつ撃つか・いつ終わるか」の規則だけを置く。
 */
public final class BoomboxHeartbeat {

    /** 走査 1 回でその再生に対して取る行動。 */
    public enum Action {
        /** 新しい再生セッションを起こして offset 0 から撃つ (曲が差し替わった)。 */
        START,
        /** 現在位置を載せて撃ち直す (late-join・生存確認・設定のライブ反映を兼ねる)。 */
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
     * @param elapsedMs       セッション開始からの経過 (wall-clock)
     * @param durationMs      曲の尺。0 以下 = 無限長 (ライブ/ラジオ) で終端が無い
     * @param sinceLastSendMs 前回 payload を撃ってからの経過
     * @param heartbeatMs     keep-alive の間隔
     */
    public static Action decide(boolean sessionLive, boolean urlChanged, long elapsedMs, long durationMs,
            long sinceLastSendMs, long heartbeatMs) {
        if (!sessionLive || urlChanged) {
            return Action.START;
        }
        // 無限長 (ラジオ/ライブ) は終端を持たない。尺で切ると放送の途中で黙る。
        if (durationMs > 0L && elapsedMs >= durationMs) {
            return Action.END;
        }
        return sinceLastSendMs >= heartbeatMs ? Action.KEEP_ALIVE : Action.IDLE;
    }

    /**
     * payload に載せる再生位置。
     *
     * <p>無限長ストリーム (ライブ/ラジオ) に位置の概念は無い。経過を載せると、後から近づいた
     * player の late-join が「10 分地点から」ストリームを開こうとする。常にライブ先頭へ繋ぐ
     * ({@code resumePlaybackAfterLoad} / {@code onRadioStreamEnded} と同じ規則)。
     */
    public static long offsetFor(boolean live, long elapsedMs) {
        return live ? 0L : Math.max(0L, elapsedMs);
    }
}
