package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: entity(entityId) の手持ちブームボックス再生を止める。
 *
 * <p>keep-alive の途絶でも client は自己停止するが、こちらは即時。トグル OFF・ディスク抜き・
 * 曲の自然終了・手から離した (インベントリへ移した) の各経路で撃つ。
 */
public record BoomboxStopPayload(int entityId) implements CustomPacketPayload {

    public static final Type<BoomboxStopPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "boombox_stop"));

    public static final StreamCodec<ByteBuf, BoomboxStopPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BoomboxStopPayload::entityId,
            BoomboxStopPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
