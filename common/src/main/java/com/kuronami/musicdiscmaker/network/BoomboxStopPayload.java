package com.kuronami.musicdiscmaker.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * server → client: ブームボックス {@code boomboxId} の再生を止める。
 *
 * <p>keep-alive の途絶でも client は自己停止するが ({@code BoomboxAnchor})、こちらは即時。
 * ディスクを抜いた・曲が自然終了した・インベントリから出た (落とした / チェストへ移した /
 * 死亡ドロップ)・設置していたブロックが壊れた、の各経路で撃つ。
 *
 * @param boomboxId 機体の識別子
 */
public record BoomboxStopPayload(long boomboxId) implements ModPayload {

    public static final String PATH = "boombox_stop";

    /**
     * @param buf 読み出し元
     * @return 復元した payload
     */
    public static BoomboxStopPayload read(FriendlyByteBuf buf) {
        return new BoomboxStopPayload(buf.readLong());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(boomboxId);
    }
}
