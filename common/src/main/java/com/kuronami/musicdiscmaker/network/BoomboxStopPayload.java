package com.kuronami.musicdiscmaker.network;

import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: ブームボックス {@code boomboxId} の再生を止める。
 *
 * <p>keep-alive の途絶でも client は自己停止するが、こちらは即時。トグル OFF・ディスク抜き・
 * 曲の自然終了・インベントリから出た (落とした / チェストへ移した / 死亡ドロップ) の各経路で撃つ。
 */
public record BoomboxStopPayload(UUID boomboxId) implements CustomPacketPayload {

    public static final Type<BoomboxStopPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "boombox_stop"));

    public static final StreamCodec<ByteBuf, BoomboxStopPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, BoomboxStopPayload::boomboxId,
            BoomboxStopPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
