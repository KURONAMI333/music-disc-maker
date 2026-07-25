package com.kuronami.musicdiscmaker.network;

import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * client → server: ブームボックス専用 GUI の音量・指向性の適用。
 *
 * <p>宛先をアイテム個体の UUID で指すので、GUI を開いたままスタックがスロット間を動いても効く。
 * server は component を書き換えたうえで、鳴っていれば即座に keep-alive を 1 通撃って
 * <b>鳴らし直さずに</b> client へ届ける (次の定期 keep-alive を待たない)。
 */
public record BoomboxConfigPayload(UUID boomboxId, int volumePercent, boolean directional)
        implements CustomPacketPayload {

    public static final Type<BoomboxConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "boombox_config"));

    public static final StreamCodec<ByteBuf, BoomboxConfigPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, BoomboxConfigPayload::boomboxId,
            ByteBufCodecs.VAR_INT, BoomboxConfigPayload::volumePercent,
            ByteBufCodecs.BOOL, BoomboxConfigPayload::directional,
            BoomboxConfigPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
