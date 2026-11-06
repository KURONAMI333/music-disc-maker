package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;

import net.minecraft.core.BlockPos;

/**
 * ブームボックスの<b>直近の失敗</b>を、その client の中だけで覚えておく入れ物。
 *
 * <p>ストリームは client ごとに開くので、同じ音源でも<b>片方の client だけ</b>失敗しうる
 * ({@link PlaybackFailure} の javadoc 参照 — DNS・回線・ガード判定・同時再生上限はすべてローカル)。
 * BlockEntity に載せて同期すると<b>他人の失敗が自分の画面に出る</b>ので、client 側に持つ。
 *
 * <p>鍵は機体の置かれ方で分ける。設置中は {@link BlockPos}、持ち歩き・装備中は
 * {@link BoomboxPlayPayload#boomboxId()}。持ち歩きの payload は {@link BlockPos#ZERO} を運ぶので、
 * 座標だけでは複数台を区別できない。
 *
 * <p>消えるのは<b>その機体の再生が始まった時</b>とワールドを抜けた時だけ。時間では消さない
 * ({@link GoldenJukeboxFailures} と同じ理由 — 直っていない失敗が黙って消えると、画面を開いた時に
 * 何も無い状態へ戻る)。
 *
 * <p>{@code Minecraft} を掴まないので headless テストに載る。
 */
public final class BoomboxFailures {

    /** 画面が読む共有の入れ物。 */
    private static final BoomboxFailures INSTANCE = new BoomboxFailures();

    /** 設置位置 → その機体で最後に起きた失敗。 */
    private final Map<BlockPos, PlaybackFailure> placed = new ConcurrentHashMap<>();

    /** 機体の識別子 → その機体で最後に起きた失敗 (持ち歩き・装備中)。 */
    private final Map<Long, PlaybackFailure> carried = new ConcurrentHashMap<>();

    /**
     * 共有の入れ物。実際の再生経路が書き、画面が読む。
     *
     * @return 共有 instance
     */
    public static BoomboxFailures get() {
        return INSTANCE;
    }

    /**
     * 失敗を覚える (同じ機体の古い失敗は上書きする)。
     *
     * @param payload 失敗した再生要求 ({@code null} なら何もしない)
     * @param failure 分類済みの失敗 ({@code null} なら何もしない — 既に覚えているものを消さない)
     */
    public void record(@Nullable BoomboxPlayPayload payload, @Nullable PlaybackFailure failure) {
        if (payload == null || failure == null) {
            return;
        }
        if (payload.isCarried()) {
            if (payload.boomboxId() != BoomboxContents.UNASSIGNED) {
                carried.put(payload.boomboxId(), failure);
            }
        } else {
            placed.put(payload.pos().immutable(), failure);
        }
    }

    /**
     * その機体の記憶を捨てる (再生が始まった時)。
     *
     * @param payload 再生が始まった要求 ({@code null} なら何もしない)
     */
    public void clear(@Nullable BoomboxPlayPayload payload) {
        if (payload == null) {
            return;
        }
        if (payload.isCarried()) {
            carried.remove(payload.boomboxId());
        } else {
            placed.remove(payload.pos());
        }
    }

    /**
     * 設置中の機体の直近の失敗。
     *
     * @param pos 設置位置
     * @return 覚えていれば失敗、無ければ {@code null}
     */
    @Nullable
    public PlaybackFailure latestPlaced(@Nullable BlockPos pos) {
        return pos == null ? null : placed.get(pos);
    }

    /**
     * 持ち歩き・装備中の機体の直近の失敗。
     *
     * @param boomboxId 機体の識別子
     * @return 覚えていれば失敗、無ければ {@code null}
     */
    @Nullable
    public PlaybackFailure latestCarried(long boomboxId) {
        return boomboxId == BoomboxContents.UNASSIGNED ? null : carried.get(boomboxId);
    }

    /** 全部捨てる (ワールド離脱)。 */
    public void clearAll() {
        placed.clear();
        carried.clear();
    }
}
