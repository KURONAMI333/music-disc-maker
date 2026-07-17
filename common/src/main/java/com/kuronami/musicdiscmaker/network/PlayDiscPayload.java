package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: jukebox(pos) で custom disc 再生開始。各 client が独立に LavaPlayer で再生する。
 * {@code startOffsetMs} は再生開始位置 (ミリ秒)。挿入時は 0、後から chunk に入った player への
 * 追従再生では経過時間を入れて途中から同期させる。
 */
public record PlayDiscPayload(BlockPos jukeboxPos, CustomTrackData track, long startOffsetMs) implements ModPayload {

    public static final ResourceLocation ID = new ResourceLocation(MusicDiscMaker.MODID, "play_disc");

    public static PlayDiscPayload read(FriendlyByteBuf buf) {
        return new PlayDiscPayload(buf.readBlockPos(), CustomTrackData.read(buf), buf.readVarLong());
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(jukeboxPos);
        track.write(buf);
        buf.writeVarLong(startOffsetMs);
    }
}
