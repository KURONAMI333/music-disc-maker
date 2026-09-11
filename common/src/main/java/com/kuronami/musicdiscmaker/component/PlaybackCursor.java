package com.kuronami.musicdiscmaker.component;

import java.util.Objects;

/**
 * 再生する器が持つ、盤と盤内の曲の位置および再生状態。
 *
 * <p>Minecraft の型を使わない値オブジェクトで、金ジュークとブームボックスが同じ遷移を使える。
 * 位置は 0 起点で、単曲盤は {@code discIndex/0}、プレイリスト盤は
 * {@code discIndex/trackIndex} と表す。媒体そのものにはこの値を書かない。
 *
 * <p>{@link #generation} は URL ではなく論理再生の世代。同じ URL が別の盤や曲として再登場しても、
 * 位置を移した時点で世代が進む。同じ位置の keep-alive だけは世代を保つ。pause/resume/stop を含む
 * 実際の状態遷移は、遅れて完了するロードを失効させるため世代を進める。
 */
public record PlaybackCursor(int discIndex, int trackIndex, State state, long generation) {

    /** 媒体がまだ選ばれていないことを示す位置。 */
    public static final int NO_INDEX = -1;

    /** 再生状態。 */
    public enum State {
        /** 音源を持たない。 */
        STOPPED,
        /** 選択位置を保ったまま一時停止している。 */
        PAUSED,
        /** 選択位置を再生している。 */
        PLAYING
    }

    /**
     * 値の不変条件を検査する。
     *
     * @throws IllegalArgumentException 位置の片方だけが無い場合、または世代が負の場合
     * @throws NullPointerException 状態が {@code null} の場合
     */
    public PlaybackCursor {
        Objects.requireNonNull(state, "state");
        final boolean noPosition = discIndex == NO_INDEX && trackIndex == NO_INDEX;
        final boolean validPosition = discIndex >= 0 && trackIndex >= 0;
        if (!noPosition && !validPosition) {
            throw new IllegalArgumentException("disc and track indices must both be set or both be absent");
        }
        if (state != State.STOPPED && !validPosition) {
            throw new IllegalArgumentException("paused or playing cursor requires a position");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
    }

    /** 初期状態。まだ位置を持たず、世代 0 で停止している。 */
    public static PlaybackCursor initial() {
        return new PlaybackCursor(NO_INDEX, NO_INDEX, State.STOPPED, 0L);
    }

    /** 位置を持っているか。 */
    public boolean hasPosition() {
        return discIndex >= 0;
    }

    /**
     * 指定位置を新しい論理再生として開始する。同じ位置を明示的に再開しても世代は進む。
     *
     * @param newDiscIndex 0 起点の盤位置
     * @param newTrackIndex 0 起点の盤内曲位置
     * @return 再生中になった新しいカーソル
     */
    public PlaybackCursor startAt(int newDiscIndex, int newTrackIndex) {
        requirePosition(newDiscIndex, newTrackIndex);
        return new PlaybackCursor(newDiscIndex, newTrackIndex, State.PLAYING, nextGeneration());
    }

    /**
     * 状態を保って別の位置へ移る。位置が変わった時だけ世代を進める。
     *
     * <p>pause 中の next は pause のまま次の位置を選ぶ。同じ位置への呼び出しは keep-alive として
     * このインスタンスを返す。
     *
     * @param newDiscIndex 0 起点の盤位置
     * @param newTrackIndex 0 起点の盤内曲位置
     * @return 移動後のカーソル
     */
    public PlaybackCursor moveTo(int newDiscIndex, int newTrackIndex) {
        requirePosition(newDiscIndex, newTrackIndex);
        if (discIndex == newDiscIndex && trackIndex == newTrackIndex) {
            return this;
        }
        return new PlaybackCursor(newDiscIndex, newTrackIndex, state, nextGeneration());
    }

    /** 再生中なら一時停止し、遅れた非同期完了を失効させる世代へ進む。それ以外では何もしない。 */
    public PlaybackCursor pause() {
        return state == State.PLAYING
                ? new PlaybackCursor(discIndex, trackIndex, State.PAUSED, nextGeneration())
                : this;
    }

    /** 一時停止中なら再開し、pause 中の非同期完了と区別する世代へ進む。それ以外では何もしない。 */
    public PlaybackCursor resume() {
        return state == State.PAUSED
                ? new PlaybackCursor(discIndex, trackIndex, State.PLAYING, nextGeneration())
                : this;
    }

    /**
     * 再生を止める。選択位置は再開表示のため保持し、進行中のロードを失効させるため世代を進める。
     * 既に停止している場合は何もしない。
     */
    public PlaybackCursor stop() {
        return state == State.STOPPED
                ? this
                : new PlaybackCursor(discIndex, trackIndex, State.STOPPED, nextGeneration());
    }

    /**
     * 媒体を取り出して位置を消す。既存の世代を再利用せず、遅れたロードを失効させる。
     * 既に初期相当の空状態なら何もしない。
     */
    public PlaybackCursor clear() {
        return state == State.STOPPED && !hasPosition()
                ? this
                : new PlaybackCursor(NO_INDEX, NO_INDEX, State.STOPPED, nextGeneration());
    }

    /**
     * 再生状態や位置を変えずに、保留中の非同期完了だけを失効させる。
     * 順序の切替のように音を鳴らし直さない状態変更で使う。
     */
    public PlaybackCursor invalidate() {
        return new PlaybackCursor(discIndex, trackIndex, state, nextGeneration());
    }

    private long nextGeneration() {
        return generation == Long.MAX_VALUE ? 1L : generation + 1L;
    }

    private static void requirePosition(int discIndex, int trackIndex) {
        if (discIndex < 0 || trackIndex < 0) {
            throw new IllegalArgumentException("disc and track indices must not be negative");
        }
    }
}
