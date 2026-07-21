package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * client → server: 強化版ジュークボックス(pos) の設定を適用する。
 * server 側で clamp・到達距離チェックした上で BE に反映し、再生を調停する。
 * 音量/範囲/リピートは値の保存、paused は再生/一時停止のトグルに使う。
 */
public record ConfigureJukeboxPayload(BlockPos pos, int rangeBlocks, int volumePercent, boolean repeat,
        boolean paused) implements ModPayload {

    public static final ResourceLocation ID = new ResourceLocation(MusicDiscMaker.MODID, "configure_jukebox");

    public static ConfigureJukeboxPayload read(FriendlyByteBuf buf) {
        return new ConfigureJukeboxPayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean());
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(rangeBlocks);
        buf.writeVarInt(volumePercent);
        buf.writeBoolean(repeat);
        buf.writeBoolean(paused);
    }
}
