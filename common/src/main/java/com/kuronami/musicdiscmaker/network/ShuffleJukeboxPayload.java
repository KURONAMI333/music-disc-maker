package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** 開いている金ジュークの、表示中の再生世代に対するシャッフル設定。 */
public record ShuffleJukeboxPayload(BlockPos pos, int containerId, long generation, boolean enabled)
        implements ModPayload {
    public static final String PATH = "shuffle_jukebox";

    public static ShuffleJukeboxPayload read(FriendlyByteBuf buf) {
        return new ShuffleJukeboxPayload(BlockPos.of(buf.readLong()), buf.readVarInt(),
                buf.readVarLong(), buf.readBoolean());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeVarInt(containerId);
        buf.writeVarLong(generation);
        buf.writeBoolean(enabled);
    }
}
