package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * client → server: スピーカー(pos) の音量・可聴範囲を適用する。
 * {@link ConfigureJukeboxPayload} と同型で、server 側で clamp・到達距離チェックしてから BE に反映する。
 */
public record SpeakerConfigPayload(BlockPos pos, int volumePercent, int rangeBlocks)
        implements CustomPacketPayload {

    public static final Type<SpeakerConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "configure_speaker"));

    public static final StreamCodec<ByteBuf, SpeakerConfigPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SpeakerConfigPayload::pos,
            ByteBufCodecs.VAR_INT, SpeakerConfigPayload::volumePercent,
            ByteBufCodecs.VAR_INT, SpeakerConfigPayload::rangeBlocks,
            SpeakerConfigPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
