package com.kuronami.musicdiscmaker.client.audio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *   <li>待つのは前の音の残り ({@link #tailCapMs}、曲の長さから詰まりの無音を除いた実音の位置を引いた長さ)
 *       がある間だけ。詰まっていない client は 3.1.1 までと同じく曲の長さの位置で切る。</li>
 *   <li>待っている間に世界が変わったら (次元の移動) 待ちを捨てる ({@link #sameScope})。</li>
 * </ul>
 */
public final class NaturalEndHandoff {

    /** 前の音を待つ上限 (ms)。金のジュークボックスの自然終了の待ち (3.0 系から) と同じ 10 秒。 */
    public static final long MAX_WAIT_MS = 10_000L;

    /** 前の音が止まったかを見に行く間隔 (ms)。1 tick。 */
    public static final long POLL_MS = 50L;

    /**
     * 曲の長さで切る時の余白 (ms)。実音の位置は「最初の PCM を渡してからの経過」で数えるので、出力の
     * バッファぶん耳に届くのが遅い。その分だけ待ってから切る。
     */
    public static final long END_MARGIN_MS = 500L;

    private static final long NONE = Long.MIN_VALUE;

    /** 届いた payload の扱い。 */
    public enum Arrival {
        /** 待ちと関係ない (前の音と同じか古い世代)。今すぐ処理してよい。 */
        PASS,
        /** 次の曲として預かった。前の音が止まってから処理する。 */
        HOLD,
        /** server では待っている間に終わった次の曲の送り直し。捨てる。 */
        DROP,
        /** 預かった次の曲とは別の再生 = 手動の操作。待ちを畳んで今すぐ処理する。 */
        SUPERSEDE
    }

    private final long endedGeneration;
    private final long startedAtMs;
    @Nullable
    private final Object scope;
    private long heldGeneration = NONE;
    private long heldSinceMs = NONE;
    private long droppedUpTo = NONE;
    private final Map<String, Runnable> held = new LinkedHashMap<>();

    /**
     * @param endedGeneration 曲の終わりで止まる前の音の世代
     * @param startedAtMs     待ちを始めた時刻 (再生時計)
     */
    public NaturalEndHandoff(long endedGeneration, long startedAtMs) {
        this(endedGeneration, startedAtMs, null);
    }

    /**
     * @param scope 待ちを始めた時の世界 (client の level)。違う世界になったら待ちを捨てる
     */
    public NaturalEndHandoff(long endedGeneration, long startedAtMs, @Nullable Object scope) {
        this.endedGeneration = endedGeneration;
        this.startedAtMs = startedAtMs;
        this.scope = scope;
    }

    /**
     * この client の前の音の残り (ms)。曲の長さから、詰まりの無音を除いた実音の位置を引く。待っている間も
     * 毎回計り直す (詰まりが続いている間は残りが減らない)。
     *
     * <p>詰まっていない client は、実音が曲の長さの位置へ来たところで切る (盤に書かれた長さが実際の音声
     * より短い場合に、全員が最長 10 秒余分に鳴らさないため。3.1.1 までと同じ切り方)。詰まった client は
     * 詰まった分だけ後ろまで待つ。
     *
     * @param durationMs       盤に書かれた曲の長さ
     * @param audioPositionMs  最初の実音からの経過で数えた位置 (詰まりの無音を含む)。不明なら負
     * @param paddedSilenceMs  読み込みが間に合わずに無音で埋めた長さ
     * @return 残り。0 以下ならもう待たずに止めてよい
     */
    public static long tailCapMs(long durationMs, long audioPositionMs, long paddedSilenceMs) {
        if (durationMs <= 0L || audioPositionMs < 0L) {
            return MAX_WAIT_MS;
        }
        final long tail = durationMs - (audioPositionMs - Math.max(0L, paddedSilenceMs)) + END_MARGIN_MS;
        return Math.min(MAX_WAIT_MS, tail);
    }

    /**
     * 待っている間に payload が届いた。最初の新しい世代を次の曲として預かる。同じ世代は最初の1本
     * ({@code keepLatest=false}) か最新の1本 ({@code keepLatest=true}) を {@code slot} ごとに残す。
     *
     * @param generation その payload の世代
     * @param nowMs      現在 (再生時計)
     * @param slot       payload の種類 (同じ種類の送り直しを畳む鍵)
     * @param keepLatest 同じ種類の送り直しで最新を残すか (偽なら最初を残す)
     * @param action     預かった時に後で実行する処理 (null なら何も積まない)
     * @return 扱い
     */
    public Arrival arrive(long generation, long nowMs, String slot, boolean keepLatest, @Nullable Runnable action) {
        if (generation <= endedGeneration) {
            return Arrival.PASS;
        }
        if (droppedUpTo != NONE && generation <= droppedUpTo) {
            return Arrival.DROP;
        }
        if (heldGeneration == NONE) {
            heldGeneration = generation;
            heldSinceMs = nowMs;
        }
        if (heldGeneration != generation) {
            return Arrival.SUPERSEDE;
        }
        if (action != null) {
            if (keepLatest) {
                held.put(slot, action);
            } else {
                held.putIfAbsent(slot, action);
            }
        }
        return Arrival.HOLD;
    }

    /** {@link #arrive(long, long, String, boolean, Runnable)} の、種類を分けない版 (最初の1本を残す)。 */
    public Arrival arrive(long generation, long nowMs, @Nullable Runnable action) {
        return arrive(generation, nowMs, "", false, action);
    }

    /**
     * 預かった次の曲が、待っている間に server ではもう終わった (その曲の「曲の終わりの停止」が届いた)。
     * 預かったものを捨て、その世代までの送り直しも以後は捨てる。前の音は鳴らし切り、続く曲があれば
     * それを新しく預かる。この client はその曲を飛ばす (一人だけで鳴らすと、さらに遅れるため)。
     *
     * @param generation 届いた停止の世代 (世代を運ばない停止なら {@link #heldGeneration()})
     * @return 捨てたなら {@code true}
     */
    public boolean dropHeldEndedBy(long generation) {
        if (heldGeneration == NONE || generation < heldGeneration) {
            return false;
        }
        droppedUpTo = heldGeneration;
        heldGeneration = NONE;
        heldSinceMs = NONE;
        held.clear();
        return true;
    }

    /**
     * @param previousStillPlaying 前の音がまだ鳴っているか
     * @param nowMs                現在 (再生時計)
     * @return まだ待つなら {@code true}
     */
    public boolean keepWaiting(boolean previousStillPlaying, long nowMs) {
        return keepWaiting(previousStillPlaying, nowMs, MAX_WAIT_MS);
    }

    /**
     * @param tailMs 前の音の残り ({@link #tailCapMs})。0 以下になったら、音がまだ鳴っていても止める
     */
    public boolean keepWaiting(boolean previousStillPlaying, long nowMs, long tailMs) {
        return previousStillPlaying && tailMs > 0L && nowMs - startedAtMs < MAX_WAIT_MS;
    }

    /** 待ちを始めた時と同じ世界か (次元を移った・ワールドを出たら偽)。 */
    public boolean sameScope(@Nullable Object current) {
        return scope == current;
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

    /** 預かった処理を取り出す (最初に届いた順)。 */
    public List<Runnable> drainHeld() {
        final List<Runnable> out = List.copyOf(held.values());
        held.clear();
        return out;
    }
}
