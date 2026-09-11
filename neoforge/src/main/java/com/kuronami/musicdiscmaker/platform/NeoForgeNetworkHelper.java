package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.network.ModPayloadTypes;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/** NeoForge 実装: 中立 payload を {@code CustomPacketPayload} に包んで {@link PacketDistributor} へ委譲する。 */
public class NeoForgeNetworkHelper implements INetworkHelper {

    @Override
    public void sendToServer(ModPayload payload) {
        //? if >=1.21.2 {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(ModPayloadTypes.wrap(payload));
        //?} else {
        /*PacketDistributor.sendToServer(ModPayloadTypes.wrap(payload));
        *///?}
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ModPayload payload) {
        PacketDistributor.sendToPlayer(player, ModPayloadTypes.wrap(payload));
    }

    @Override
    public void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, ModPayload payload) {
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk, ModPayloadTypes.wrap(payload));
    }
}


