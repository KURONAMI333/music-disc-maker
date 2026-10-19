package com.kuronami.musicdiscmaker.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * server → client: ブームボックス {@code boomboxId} の再生を止める。
 *
 * <p>keep-alive の途絶でも client は自己停止するが ({@code BoomboxAnchor})、こちらは即時。
 * ディスクを抜いた・曲が自然終了した・インベントリから出た (落とした / チェストへ移した /
 * 死亡ドロップ)・設置していたブロックが壊れた、の各経路で撃つ。
 *
 * <p>{@code naturalEnd} は曲を鳴らし切ったことによる停止 (並びの最後で止まる・次の曲へ進む直前)。
 * client は自分の音が途中の読み込みの詰まりで server より後ろにいる時、鳴り終わるまで止めるのを待ち、
 * 続けて届いた次の曲もその後で始める ({@code NaturalEndHandoff})。一時停止・取り出し・破壊などは
 * すぐ止める。
 *
 * @param boomboxId  機体の識別子
 * @param naturalEnd 曲を鳴らし切ったことによる停止か
 */
public record BoomboxStopPayload(long boomboxId, boolean naturalEnd) implements ModPayload {

    public static final String PATH = "boombox_stop";

    /** すぐ止める停止 (手動の操作・機体が消えた)。 */
    public BoomboxStopPayload(long boomboxId) {
        this(boomboxId, false);
    }

    /**
     * @param buf 読み出し元
     * @return 復元した payload
     */
    public static BoomboxStopPayload read(FriendlyByteBuf buf) {
        return new BoomboxStopPayload(buf.readLong(), buf.readBoolean());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(boomboxId);
        buf.writeBoolean(naturalEnd);
    }
}
