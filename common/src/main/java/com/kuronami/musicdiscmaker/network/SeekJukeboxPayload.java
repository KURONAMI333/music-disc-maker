package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * client → server: 強化版ジュークボックス(pos) の再生位置を offsetMs へ頭出しする。
 * GUI のシークバー操作から送られ、server 側で有限尺 (非ラジオ) の時だけ適用する。
 */
public record SeekJukeboxPayload(BlockPos pos, long offsetMs) implements CustomPacketPayload {

    public static final Type<SeekJukeboxPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "seek_jukebox"));

    public static final StreamCodec<ByteBuf, SeekJukeboxPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SeekJukeboxPayload::pos,
            ByteBufCodecs.VAR_LONG, SeekJukeboxPayload::offsetMs,
            SeekJukeboxPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
