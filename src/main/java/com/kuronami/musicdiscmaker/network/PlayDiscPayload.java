package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: jukebox(pos) で custom disc 再生開始。各 client が独立に LavaPlayer で再生する。
 * {@code startOffsetMs} は再生開始位置 (ミリ秒)。挿入時は 0、後から chunk に入った player への
 * 追従再生では経過時間を入れて途中から同期させる。
 */
public record PlayDiscPayload(BlockPos jukeboxPos, CustomTrackData track, long startOffsetMs) implements CustomPacketPayload {

    public static final Type<PlayDiscPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "play_disc"));

    public static final StreamCodec<ByteBuf, PlayDiscPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlayDiscPayload::jukeboxPos,
            CustomTrackData.STREAM_CODEC, PlayDiscPayload::track,
            ByteBufCodecs.VAR_LONG, PlayDiscPayload::startOffsetMs,
            PlayDiscPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
