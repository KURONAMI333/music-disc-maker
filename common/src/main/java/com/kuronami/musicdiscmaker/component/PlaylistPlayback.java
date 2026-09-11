package com.kuronami.musicdiscmaker.component;

/**
 * Album など複数トラック媒体の曲送り判断。<b>いつ次へ進むか</b>と<b>どこへ進むか</b>だけを持つ。
 *
 * <p><b>Minecraft の型を 1 つも使わない。</b>{@code BoomboxHeartbeat#decide} と同じ流儀で、
 * 呼ぶ側 (BlockEntity) が状態を数と真偽に潰してから
 * ここへ渡す。こうしておくと判断だけを JUnit で固定できる。
 *
 * <p><b>ここは器の側の道具であって、盤の側の値ではない。</b>いま何曲目かを持つのは盤 (ItemStack) では
 * なく、その盤が刺さっている器 (金ジュークの BlockEntity)。盤に書くと、抜いて別の器へ挿した時に
 * 途中から鳴り出す。設計の正本は {@code DESIGN_PLAYLIST_SYNC.md} 凍結1。
 *
 * <p><b>判断するのはサーバだけ。</b>クライアントの「鳴り終わった」は受け取らない。クライアントは
 * 複数居て各自が独立に音を回しているので、どれの終端を採るかを決められないし、決めた瞬間に
 * その 1 台の回線が全員の再生を握ることになる (同 凍結4)。
 */
public final class PlaylistPlayback {

    /** 曲の列として扱っていない状態を表す位置。 */
    public static final int NO_TRACK = -1;

    private PlaylistPlayback() {
    }

    /**
     * 盤を挿した時に置く再生位置。
     *
     * <p>複数トラック媒体は1曲でも先頭位置を持つ。通常盤はこの位置を使わない。
     *
     * @param trackCount 盤が持つ曲数
     * @return 再生位置。プレイリストでなければ {@link #NO_TRACK}
     */
    public static int initialIndex(int trackCount) {
        return trackCount > 0 ? 0 : NO_TRACK;
    }

    /**
     * いまの曲が鳴り終わったか。
     *
     * <p><b>ラジオと尺不明は「終わらない」を返す</b> (KURONAMI333 裁定 2026-09-04「鳴って、そこで止まる」)。
     * 列の途中にラジオが入っていたら、そこまでは順に送られ、ラジオに当たったら鳴り続けて止まる。
     * 手で送るまで先へ進まない。既存の {@code nextAlbumTrack()} が同じ条件で {@code null} を
     * 返しているのと同じ線。
     *
     * @param elapsedMs  いまの曲を鳴らし始めてからの経過 (ms)
     * @param durationMs いまの曲の尺 (ms)。0 以下 = 不明
     * @param radio      ライブ / ラジオか
     * @return 次へ送ってよいなら {@code true}
     */
    public static boolean finished(long elapsedMs, long durationMs, boolean radio) {
        if (radio || durationMs <= 0L) {
            return false;
        }
        return elapsedMs >= durationMs;
    }

    /**
     * 次に鳴る曲の位置。
     *
     * <p>repeat は<b>列全体のループ</b>。1 曲のループではない (1 曲のループは列を持たない盤の挙動で、
     * そちらは呼ぶ側が別経路で扱う)。これは既存のアルバムの repeat と同じ意味に揃えてある。
     *
     * @param currentIndex いまの位置
     * @param trackCount   盤が持つ曲数
     * @param repeat       repeat が入っているか
     * @return 次の位置。末尾で repeat が無いなど、進めないなら {@link #NO_TRACK}
     */
    public static int nextIndex(int currentIndex, int trackCount, boolean repeat) {
        if (currentIndex < 0 || trackCount <= 0) {
            return NO_TRACK;
        }
        final int next = currentIndex + 1;
        if (next < trackCount) {
            return next;
        }
        return repeat ? 0 : NO_TRACK;
    }

    /**
     * その曲をクライアントの先読みへ渡してよいか。
     *
     * <p>先読みは「次の 1 曲を鳴らす直前に取りに行っておく」だけの仕掛けなので、鳴り終わりが
     * 来ない曲 (ラジオ・尺不明) を渡しても使い道が無い。既存の {@code nextAlbumTrack()} が
     * 同じ条件で弾いている。
     *
     * @param durationMs その曲の尺 (ms)
     * @param radio      ライブ / ラジオか
     * @return 渡してよいなら {@code true}
     */
    public static boolean prefetchable(long durationMs, boolean radio) {
        return !radio && durationMs > 0L;
    }
}
