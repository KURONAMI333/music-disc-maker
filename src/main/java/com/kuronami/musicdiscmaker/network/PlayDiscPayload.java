package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** server → client: jukebox(pos) で custom disc 再生開始。各 client が独立に LavaPlayer で再生する。 */
public record PlayDiscPayload(BlockPos jukeboxPos, CustomTrackData track) implements CustomPacketPayload {

    public static final Type<PlayDiscPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "play_disc"));

    public static final StreamCodec<ByteBuf, PlayDiscPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlayDiscPayload::jukeboxPos,
            CustomTrackData.STREAM_CODEC, PlayDiscPayload::track,
            PlayDiscPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
