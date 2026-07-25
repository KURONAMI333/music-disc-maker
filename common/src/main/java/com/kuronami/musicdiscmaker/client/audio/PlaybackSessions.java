package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;

/**
 * client の再生要求の<b>世代管理</b>だけを取り出した純ロジック。
 *
 * <p>{@link ClientPlaybackManager} から分けてあるのは、あちらが {@code Minecraft} を掴んでいて
 * dedicated server では読めない = headless テストに載らないため（{@link SpeakerSelection} を
 * {@link MultiSpeakerAnchor} から切り出したのと同じ規律）。
 *
 * <h2>これが要る理由</h2>
 * 再生要求から実際に音が立つまでの間に URL 解決とストリーム確立が入る（別 thread・数百 ms〜数秒）。
 * その窓の中に 2 通目の再生要求が来ると、「今どのインスタンスが鳴っているか」だけを見る dedup は
 * 素通りする。両方のロードが完走すると、後から着地した方が前のインスタンスを bookkeeping から
 * 押し出し、押し出された方はどの map からも参照されない<b>孤児</b>になって停止できなくなる。
 *
 * <p>そこで要求ごとに単調増加のトークンを配り、ロード完了時に「そのトークンが今も最新か」で
 * 採否を決める。<b>最新の要求が勝つ</b>: 古いロードは破棄する。逆にすると、シーク要求
 * （別のオフセットで来る）が先行ロードに握り潰されて無視される。
 *
 * <h2>スレッドの約束</h2>
 * <b>{@link #begin} / {@link #cancel} / {@link #cancelAll} は main thread からだけ呼ぶこと。</b>
 * {@code startPlayback} の「停止してから始める」は {@code cancel} → {@code begin} の 2 手で、
 * この対は<b>不可分ではない</b>。読み取り側（{@link #isCurrent} / {@link #onLoadComplete} /
 * {@link #isWanted}）だけがロードスレッドから呼ばれるので、格納は並行コレクションにしてある。
 * 書き込みが main thread に閉じている限り、ロードスレッドから見えるのは「ある時点の最新トークン」
 * であり、それで採否を決めれば十分（勝者が 2 人になることは無い）。
 *
 * <p>{@link BlockPos} は dedicated server にも在るクラスなので headless で読める。
 */
public final class PlaybackSessions {

    /** ロード完了時の採否。 */
    public enum LoadOutcome {
        /** このロードを設置する（＝最新の要求）。 */
        INSTALL,
        /** 捨てる（停止済み、または後続の要求に追い越された）。 */
        DISCARD
    }

    /** 音源ごとの「最新の要求」のトークン。要求が生きていない位置はキーごと存在しない。 */
    private final Map<BlockPos, Long> current = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    /**
     * 新しい再生要求を登録し、その世代トークンを返す。以後、この位置の古いロードは全部 stale。
     *
     * @return 0 にはならない（0 は「トークン無し」の番兵として使える）
     */
    public long begin(BlockPos key) {
        final long token = counter.incrementAndGet();
        current.put(key.immutable(), token);
        return token;
    }

    /** 再生要求を取り消す（停止・撤去）。飛行中のロードは全部 stale になる。 */
    public void cancel(BlockPos key) {
        current.remove(key);
    }

    /** 全ての再生要求を取り消す（切断）。 */
    public void cancelAll() {
        current.clear();
    }

    /** この位置に生きた再生要求があるか（トークンは問わない）。ラジオ再接続の継続判定に使う。 */
    public boolean isWanted(BlockPos key) {
        return current.containsKey(key);
    }

    /**
     * この位置の現在のトークン。要求が生きていなければ 0。
     * ラジオ再接続のように「同じ要求の続き」としてロードし直す経路が読む。
     */
    public long currentToken(BlockPos key) {
        return current.getOrDefault(key, 0L);
    }

    /** そのトークンが今も最新か。 */
    public boolean isCurrent(BlockPos key, long token) {
        return token != 0L && current.getOrDefault(key, 0L) == token;
    }

    /**
     * ロードが完了した。設置してよいか捨てるかを返す。
     *
     * <p>判定をこのメソッドに閉じてあるので、呼び出し側の分岐は
     * {@code if (outcome == DISCARD) { resolved.close(); return; }} の 1 本になる。
     */
    public LoadOutcome onLoadComplete(BlockPos key, long token) {
        return isCurrent(key, token) ? LoadOutcome.INSTALL : LoadOutcome.DISCARD;
    }
}
