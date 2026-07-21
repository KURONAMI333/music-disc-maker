package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * client → server: 強化版ジュークボックス(pos) の再生位置を offsetMs へ頭出しする。
 * GUI のシークバー操作から送られ、server 側で有限尺 (非ラジオ) の時だけ適用する。
 */
public record SeekJukeboxPayload(BlockPos pos, long offsetMs) implements ModPayload {

    public static final ResourceLocation ID = new ResourceLocation(MusicDiscMaker.MODID, "seek_jukebox");

    public static SeekJukeboxPayload read(FriendlyByteBuf buf) {
        return new SeekJukeboxPayload(buf.readBlockPos(), buf.readVarLong());
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarLong(offsetMs);
    }
}
