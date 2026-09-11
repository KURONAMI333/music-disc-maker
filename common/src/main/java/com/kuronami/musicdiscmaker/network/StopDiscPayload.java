package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** server → client: jukebox(pos) の再生停止 (disc 取り出し / ブロック破壊時)。 */
public record StopDiscPayload(BlockPos jukeboxPos, PlaybackSourceStamp identity) implements ModPayload {

    public StopDiscPayload(BlockPos pos) { this(pos, PlaybackSourceStamp.NONE); }

    public static final String PATH = "stop_disc";

    public static StopDiscPayload read(FriendlyByteBuf buf) {
        return new StopDiscPayload(BlockPos.of(buf.readLong()), PlaybackSourceStamp.read(buf));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(jukeboxPos.asLong());
        identity.write(buf);
    }
}
