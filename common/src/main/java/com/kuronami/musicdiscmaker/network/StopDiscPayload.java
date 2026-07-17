package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** server → client: jukebox(pos) の再生停止 (disc 取り出し / ブロック破壊時)。 */
public record StopDiscPayload(BlockPos jukeboxPos) implements ModPayload {

    public static final ResourceLocation ID = new ResourceLocation(MusicDiscMaker.MODID, "stop_disc");

    public static StopDiscPayload read(FriendlyByteBuf buf) {
        return new StopDiscPayload(buf.readBlockPos());
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(jukeboxPos);
    }
}
