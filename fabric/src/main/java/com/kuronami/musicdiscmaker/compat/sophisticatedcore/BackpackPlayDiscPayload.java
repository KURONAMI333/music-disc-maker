package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.network.ModPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: Sophisticated Backpacks (Fabric port) の backpack 内 jukebox upgrade で
 * custom disc を再生する。{@code storageUuid} は SC が音声を識別する鍵 (port の停止と同じ uuid)。
 * {@code entityId} は backpack を背負う entity (追従用、固定ストレージなら -1)。
 */
public record BackpackPlayDiscPayload(UUID storageUuid, CustomTrackData track, BlockPos pos, int entityId)
        implements ModPayload {

    public static final ResourceLocation ID = new ResourceLocation(MusicDiscMaker.MODID, "backpack_play_disc");

    public static BackpackPlayDiscPayload read(FriendlyByteBuf buf) {
        return new BackpackPlayDiscPayload(
                buf.readUUID(), CustomTrackData.read(buf), buf.readBlockPos(), buf.readVarInt());
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(storageUuid);
        track.write(buf);
        buf.writeBlockPos(pos);
        buf.writeVarInt(entityId);
    }
}
