package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** 開いている金ジュークの、表示中の再生世代に対する前後送り。 */
public record NavigateJukeboxPayload(BlockPos pos, int containerId, long generation, boolean previous)
        implements ModPayload {
    public static final String PATH = "navigate_jukebox";

    public static NavigateJukeboxPayload read(FriendlyByteBuf buf) {
        return new NavigateJukeboxPayload(BlockPos.of(buf.readLong()), buf.readVarInt(),
                buf.readVarLong(), buf.readBoolean());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeVarInt(containerId);
        buf.writeVarLong(generation);
        buf.writeBoolean(previous);
    }
}
