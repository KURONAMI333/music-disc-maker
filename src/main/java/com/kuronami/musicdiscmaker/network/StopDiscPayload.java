package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** server → client: jukebox(pos) の再生停止 (disc 取り出し / ブロック破壊時)。 */
public record StopDiscPayload(BlockPos jukeboxPos) implements CustomPacketPayload {

    public static final Type<StopDiscPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "stop_disc"));

    public static final StreamCodec<ByteBuf, StopDiscPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StopDiscPayload::jukeboxPos,
            StopDiscPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
