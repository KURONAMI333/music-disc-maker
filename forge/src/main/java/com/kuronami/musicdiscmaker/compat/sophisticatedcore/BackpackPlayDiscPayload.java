package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.network.ModPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: Sophisticated Backpacks の Jukebox Upgrade から custom disc 再生開始。
 * SC のサウンドライフサイクル (停止・keep-alive) に乗せるため storageUuid を運ぶ。client は
 * DiscSoundInstance を SC の {@code StorageSoundHandler} に storageUuid で登録するので、SC の停止
 * パケットがそのまま MDM の LavaPlayer 音声を止める。{@code entityId >= 0} ならその entity に追従。
 *
 * <p>ペイロード自体は SC 非依存 (UUID/track/pos/entityId のみ) なので ForgeNetwork に無条件登録してよい。
 */
public record BackpackPlayDiscPayload(UUID storageUuid, CustomTrackData track, BlockPos pos, int entityId) implements ModPayload {

    public static final ResourceLocation ID = new ResourceLocation(MusicDiscMaker.MODID, "sc_backpack_play");

    public static BackpackPlayDiscPayload read(FriendlyByteBuf buf) {
        return new BackpackPlayDiscPayload(buf.readUUID(), CustomTrackData.read(buf), buf.readBlockPos(), buf.readVarInt());
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
