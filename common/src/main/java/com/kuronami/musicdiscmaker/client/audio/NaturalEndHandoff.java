package com.kuronami.musicdiscmaker.client.audio;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * 曲が終わって次へ進む時に、この client の前の音を自分の再生位置で鳴らし切ってから次を始めるための待ち
 * (MC 非依存。ブームボックスと金のジュークボックスで共有する)。
 *
 * <h2>なぜ要るか</h2>
 * server はいちばん遅い client の「鳴り始めの遅れ」を待ってから次の曲を送る。ただし遅れは最初の実音の時に
 * 1回だけ報告されるので、曲の途中で読み込みが詰まって後ろへずれた分 (詰まりは 1 秒単位の無音になる) は
 * server に届かない。そのまま次の曲で前の音を畳むと、詰まった分だけ曲末が切れる。
 *
 * <h2>server の時計は待たせない</h2>
 * 待つのは client だけで、server はいつもどおり次の曲へ進み、他の聴き手はすぐ次の曲を聴く。この client
 * だけが他より遅れて次の曲を始める。10/12 に不採用になった「client が鳴らし切るまで server が次の曲を
 * 待つ」案とは別物 (MDM_DECISIONS)。読み込みの遅れは今までどおり server が待つので、ここで鳴らし切る
 * のは途中の詰まりで増えた分だけになる。
 *
 * <h2>何を待ち、何で打ち切るか</h2>
 * <ul>
 *   <li>server が「曲の終わりによる停止」を送ってから、前の音が自然に止まるまで (最長 {@link #MAX_WAIT_MS})。</li>
 *   <li>待っている間に届いた次の再生 (前の音より新しい世代) は最初の1つだけ預かり、前の音が止まってから
 *       始める ({@link Arrival#HOLD})。同じ世代の送り直しも預かる。</li>
 *   <li>預かった世代と違う、さらに新しい世代が来たら、それは手動の操作 (次へ・前へ等) なので待ちを畳む
 *       ({@link Arrival#SUPERSEDE})。手動の停止も呼び出し側が待ちを畳む。</li>
 *   <li>前の音と同じ世代か古い世代の payload は待ちと関係ない ({@link Arrival#PASS})。</li>
 * </ul>
 */
public final class NaturalEndHandoff {

    /** 前の音を待つ上限 (ms)。金のジュークボックスの自然終了の待ち (3.0 系から) と同じ 10 秒。 */
    public static final long MAX_WAIT_MS = 10_000L;

    /** 前の音が止まったかを見に行く間隔 (ms)。1 tick。 */
    public static final long POLL_MS = 50L;

    private static final long NONE = Long.MIN_VALUE;

    /** 届いた payload の扱い。 */
    public enum Arrival {
        /** 待ちと関係ない (前の音と同じか古い世代)。今すぐ処理してよい。 */
        PASS,
        /** 次の曲として預かった。前の音が止まってから処理する。 */
        HOLD,
        /** 預かった次の曲とは別の再生 = 手動の操作。待ちを畳んで今すぐ処理する。 */
        SUPERSEDE
    }

    private final long endedGeneration;
    private final long startedAtMs;
    private long heldGeneration = NONE;
    private long heldSinceMs = NONE;
    private final List<Runnable> held = new ArrayList<>();

    /**
     * @param endedGeneration 曲の終わりで止まる前の音の世代
     * @param startedAtMs     待ちを始めた時刻 (再生時計)
     */
    public NaturalEndHandoff(long endedGeneration, long startedAtMs) {
        this.endedGeneration = endedGeneration;
        this.startedAtMs = startedAtMs;
    }

    /**
     * 待っている間に payload が届いた。
     *
     * @param generation その payload の世代
     * @param nowMs      現在 (再生時計)
     * @param action     預かった時に後で実行する処理 (null なら何も積まない)
     * @return 扱い
     */
    public Arrival arrive(long generation, long nowMs, @Nullable Runnable action) {
        if (generation <= endedGeneration) {
            return Arrival.PASS;
        }
        if (heldGeneration == NONE) {
            heldGeneration = generation;
            heldSinceMs = nowMs;
        }
        if (heldGeneration != generation) {
            return Arrival.SUPERSEDE;
        }
        if (action != null) {
            held.add(action);
        }
        return Arrival.HOLD;
    }

    /**
     * @param previousStillPlaying 前の音がまだ鳴っているか
     * @param nowMs                現在 (再生時計)
     * @return まだ待つなら {@code true}
     */
    public boolean keepWaiting(boolean previousStillPlaying, long nowMs) {
        return previousStillPlaying && nowMs - startedAtMs < MAX_WAIT_MS;
    }

    /** 次の曲を預かっているか。 */
    public boolean holding() {
        return heldGeneration != NONE;
    }

    /** 預かった次の曲の世代。預かっていなければ {@link Long#MIN_VALUE}。 */
    public long heldGeneration() {
        return heldGeneration;
    }

    /**
     * 次の曲が届いてから今までに待った長さ (ms)。この client は次の曲をこの分だけ遅れて始める。
     *
     * @param nowMs 現在 (再生時計)
     * @return 預かっていなければ 0
     */
    public long heldForMs(long nowMs) {
        return heldSinceMs == NONE ? 0L : Math.max(0L, nowMs - heldSinceMs);
    }

    /** 預かった処理を取り出す (届いた順)。 */
    public List<Runnable> drainHeld() {
        final List<Runnable> out = List.copyOf(held);
        held.clear();
        return out;
    }
}
