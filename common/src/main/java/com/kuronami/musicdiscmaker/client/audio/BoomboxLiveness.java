package com.kuronami.musicdiscmaker.client.audio;

/**
 * ブームボックスで「server がまだ居るか」の判定 (MC 非依存)。
 *
 * <h2>なぜ末尾だけ別扱いが要るか</h2>
 * server は尺を使い切ったあと {@code BoomboxPlayback.TAIL_GRACE_MS} (8 秒) のあいだ<b>意図的に
 * payload を撃たない</b> ({@code BoomboxHeartbeat.decide} の猶予帯)。撃つと「尺を過ぎた offset で
 * 開く → 即 EOF → 次の心拍で START」を 1 Hz で繰り返す client が出るため。
 *
 * <p>一方 client の生存判定は {@link BoomboxAnchor#TIMEOUT_MS} (3 秒) の途絶で切れる。ここが揃って
 * いないと <b>猶予 8 秒のうち 3 秒で音が止まり、毎曲の末尾が切れる</b>。かといって無条件に
 * 伸ばすと、機械を外した時・持ち主がログアウトした時に余韻が長く残る。
 *
 * <p>そこで<b>曲の想定終端の前後だけ</b>沈黙を許す窓を置く。窓の内側では何秒黙っていても生存、
 * 窓の外は従来どおり {@code TIMEOUT_MS} で停止する。尺を持たないラジオ/ライブは窓を作らない
 * (終端が無いので、黙った時点で server が消えたと見るしかない)。
 */
public final class BoomboxLiveness {

    /** 想定終端の手前から窓を開く余白 (心拍 1 発ぶん + 網の揺れ)。 */
    public static final long TAIL_MARGIN_MS = 2_000L;

    /**
     * 末尾の沈黙を生存と見なす窓 (同一時計の ms)。
     *
     * @param fromMillis  この時刻から窓が開く
     * @param untilMillis この時刻まで窓が開く。{@code <= 0} は窓なし
     */
    public record TailSilence(long fromMillis, long untilMillis) {

        /** 窓なし = 従来の判定 (末尾の例外を作らない)。 */
        public static final TailSilence NONE = new TailSilence(Long.MAX_VALUE, 0L);

        /**
         * @param nowMillis 現在 (client 時計)
         * @return いまが窓の内側か
         */
        public boolean covers(long nowMillis) {
            return untilMillis > 0L && nowMillis >= fromMillis && nowMillis <= untilMillis;
        }
    }

    private BoomboxLiveness() {
    }

    /**
     * 曲の想定終端から、沈黙を許す窓を組む。
     *
     * @param playbackEndsAtMillis client 時計での想定終端。{@code <= 0} は終端なし
     * @param tailGraceMillis      server が終端後も黙って待つ長さ
     * @return 窓 (終端が無ければ {@link TailSilence#NONE})
     */
    public static TailSilence tailSilence(long playbackEndsAtMillis, long tailGraceMillis) {
        if (playbackEndsAtMillis <= 0L) {
            return TailSilence.NONE;
        }
        final long grace = Math.max(0L, tailGraceMillis);
        return new TailSilence(playbackEndsAtMillis - TAIL_MARGIN_MS,
                playbackEndsAtMillis + grace + TAIL_MARGIN_MS);
    }

    /**
     * keep-alive の途絶から生存を判定する。
     *
     * <p>窓は「途絶の許容」を<b>延ばす</b>だけで、窓の外の途絶を救わない。窓が開く前 (曲の途中) に
     * server が消えれば従来どおり止まる。窓の内側でも、その沈黙が「末尾だから」と言えるのは
     * <b>最後の心拍が窓の開く直前まで届いていた時だけ</b> — 曲の途中で消えた server を、あとから
     * 末尾の窓が救ってしまうのを防ぐ。
     *
     * @param nowMillis      現在 (client 時計)
     * @param lastSeenMillis 最後に keep-alive を受けた時刻
     * @param timeoutMillis  途絶の許容
     * @param tailSilence    末尾の沈黙を許す窓 (null 可 = 窓なし)
     * @return 生存と見なせるか
     */
    public static boolean isAlive(long nowMillis, long lastSeenMillis, long timeoutMillis,
            TailSilence tailSilence) {
        if (nowMillis - lastSeenMillis <= timeoutMillis) {
            return true;
        }
        if (tailSilence == null || !tailSilence.covers(nowMillis)) {
            return false;
        }
        return lastSeenMillis >= tailSilence.fromMillis() - timeoutMillis;
    }
}
