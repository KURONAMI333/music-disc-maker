package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * client → server: 強化版ジュークボックス(pos) の設定を適用する。
 * server 側で clamp・到達距離チェックした上で BE に反映し、再生を調停する。
 * 音量/範囲/リピート/指向性は値の保存、paused は再生/一時停止のトグルに使う。
 */
public record ConfigureJukeboxPayload(BlockPos pos, int rangeBlocks, int volumePercent, boolean repeat,
        boolean paused, boolean directional) implements ModPayload {

    public static final String PATH = "configure_jukebox";

    public static ConfigureJukeboxPayload read(FriendlyByteBuf buf) {
        return new ConfigureJukeboxPayload(BlockPos.of(buf.readLong()), buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeVarInt(rangeBlocks);
        buf.writeVarInt(volumePercent);
        buf.writeBoolean(repeat);
        buf.writeBoolean(paused);
        buf.writeBoolean(directional);
    }
}
