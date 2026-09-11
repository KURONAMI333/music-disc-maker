package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * client → server: 強化版ジュークボックス(pos) の再生位置を offsetMs へ頭出しする。
 * GUI のシークバー操作から送られ、server 側で有限尺 (非ラジオ) の時だけ適用する。
 */
public record SeekJukeboxPayload(BlockPos pos, long offsetMs) implements ModPayload {

    public static final String PATH = "seek_jukebox";

    public static SeekJukeboxPayload read(FriendlyByteBuf buf) {
        return new SeekJukeboxPayload(BlockPos.of(buf.readLong()), buf.readVarLong());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeVarLong(offsetMs);
    }
}
