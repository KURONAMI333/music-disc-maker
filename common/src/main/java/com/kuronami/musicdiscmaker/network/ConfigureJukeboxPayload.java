package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * client → server: 強化版ジュークボックス(pos) の設定を適用する。
 * server 側で clamp・到達距離チェックした上で BE に反映し、再生を調停する。
 * 音量/範囲/リピート/指向性は値の保存、paused は再生/一時停止のトグルに使う。
 */
public record ConfigureJukeboxPayload(BlockPos pos, int rangeBlocks, int volumePercent, boolean repeat,
        boolean paused, boolean directional) implements CustomPacketPayload {

    public static final Type<ConfigureJukeboxPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "configure_jukebox"));

    public static final StreamCodec<ByteBuf, ConfigureJukeboxPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ConfigureJukeboxPayload::pos,
            ByteBufCodecs.VAR_INT, ConfigureJukeboxPayload::rangeBlocks,
            ByteBufCodecs.VAR_INT, ConfigureJukeboxPayload::volumePercent,
            ByteBufCodecs.BOOL, ConfigureJukeboxPayload::repeat,
            ByteBufCodecs.BOOL, ConfigureJukeboxPayload::paused,
            ByteBufCodecs.BOOL, ConfigureJukeboxPayload::directional,
            ConfigureJukeboxPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
