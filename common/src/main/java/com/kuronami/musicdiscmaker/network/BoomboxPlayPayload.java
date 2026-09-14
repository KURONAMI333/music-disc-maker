package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * server → client: ブームボックス {@code boomboxId} の再生。
 *
 * <h2>鍵は機体の識別子であって座標でも持ち主でもない</h2>
 * 携帯プレイヤーは持ち替えてもインベントリの中を動いても鳴り続けるので、スロットや entity では
 * 音源を指せない。同じ曲を積んだブームボックスが 2 台あっても独立に鳴り独立に止まる、という
 * 要件もこの鍵でしか満たせない ({@code BoomboxContents#id})。
 *
 * <p>{@code ownerEntityId} / {@code pos} は<b>どこから鳴っているか</b>で、鍵とは別の軸。
 *
 * <ul>
 *   <li>{@code ownerEntityId >= 0} — 持ち歩かれている。その entity に追従する</li>
 *   <li>{@code ownerEntityId < 0} — 設置されている。{@code pos} に固定</li>
 * </ul>
 *
 * <h2>keep-alive</h2>
 * server は再生中のあいだ一定間隔で撃ち続ける。これが 3 役を兼ねる:
 * ① 再生開始 ② 後から近づいた player への late-join ③ client 側の生存確認 — 一定時間届かなければ
 * 「落とした / しまった / ブロックが壊れた」とみなして client が自己停止する
 * ({@code BoomboxAnchor})。
 *
 * <p><b>可聴範囲・音量・指向性は載せない。</b> 携帯はゲーム内から範囲を伸ばせず指向性も持たない
 * (設計上の決定「範囲を伸ばせず、指向性も持たない」)。範囲の正本は client の
 * {@code Config#boomboxRange()} (既定 {@code BoomboxContents#RANGE_DEFAULT})。
 * 載せると「server が範囲を決める」経路が生えて、聴く側の設定より server が勝つ形になる。
 * これは client config で調整できるという裁定 (2026-09-07) と噛み合わない。
 *
 * @param boomboxId     機体の識別子
 * @param ownerEntityId 追従先の entity id。負なら設置
 * @param pos           設置されている位置 (追従時は未使用)
 * @param track         鳴らす曲
 * @param startOffsetMs 再生開始位置 (ms)。無限長ストリームは常に 0
 */
public record BoomboxPlayPayload(long boomboxId, int ownerEntityId, BlockPos pos, CustomTrackData track,
        long startOffsetMs, long audioGeneration, int volumePercent) implements ModPayload {

    public BoomboxPlayPayload(long boomboxId, int ownerEntityId, BlockPos pos, CustomTrackData track, long startOffsetMs) {
        this(boomboxId, ownerEntityId, pos, track, startOffsetMs, 0L, 100);
    }

    public BoomboxPlayPayload {
        volumePercent = Math.max(0, Math.min(100, volumePercent));
    }

    public static BoomboxPlayPayload carried(long id, int entityId, CustomTrackData track,
            long offset, long audioGeneration, int volumePercent) {
        return new BoomboxPlayPayload(id, entityId, BlockPos.ZERO, track, offset, audioGeneration, volumePercent);
    }

    public static BoomboxPlayPayload placed(long id, BlockPos pos, CustomTrackData track,
            long offset, long audioGeneration, int volumePercent) {
        return new BoomboxPlayPayload(id, PLACED, pos, track, offset, audioGeneration, volumePercent);
    }

    public static final String PATH = "boombox_play";

    /** 設置されている機体を指す {@code ownerEntityId}。 */
    public static final int PLACED = -1;

    /**
     * 追従する再生。
     *
     * @param boomboxId     機体の識別子
     * @param entityId      追従先 entity
     * @param track         曲
     * @param startOffsetMs 再生開始位置 (ms)
     * @return payload
     */
    public static BoomboxPlayPayload carried(long boomboxId, int entityId, CustomTrackData track,
            long startOffsetMs) {
        return new BoomboxPlayPayload(boomboxId, entityId, BlockPos.ZERO, track, startOffsetMs);
    }

    /**
     * 設置されている機体の再生。
     *
     * @param boomboxId     機体の識別子
     * @param pos           設置位置
     * @param track         曲
     * @param startOffsetMs 再生開始位置 (ms)
     * @return payload
     */
    public static BoomboxPlayPayload placed(long boomboxId, BlockPos pos, CustomTrackData track,
            long startOffsetMs) {
        return new BoomboxPlayPayload(boomboxId, PLACED, pos, track, startOffsetMs);
    }

    /** 追従再生か (偽なら {@link #pos} に固定)。 */
    public boolean isCarried() {
        return ownerEntityId >= 0;
    }

    /**
     * @param buf 読み出し元
     * @return 復元した payload
     */
    public static BoomboxPlayPayload read(FriendlyByteBuf buf) {
        return new BoomboxPlayPayload(buf.readLong(), buf.readVarInt(), BlockPos.of(buf.readLong()),
                CustomTrackData.read(buf), buf.readVarLong(), buf.readVarLong(), buf.readVarInt());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(boomboxId);
        buf.writeVarInt(ownerEntityId);
        buf.writeLong(pos.asLong());
        track.write(buf);
        buf.writeVarLong(startOffsetMs);
        buf.writeVarLong(audioGeneration);
        buf.writeVarInt(volumePercent);
    }
}
