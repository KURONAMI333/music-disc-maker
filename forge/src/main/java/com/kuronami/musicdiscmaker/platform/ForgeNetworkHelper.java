package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.network.ForgeNetwork;
import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.PacketDistributor;

/** Forge 実装: payload 送信を {@link ForgeNetwork#CHANNEL} (SimpleChannel) に委譲する。 */
public class ForgeNetworkHelper implements INetworkHelper {

    @Override
    public void sendToServer(ModPayload payload) {
        ForgeNetwork.CHANNEL.sendToServer(payload);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ModPayload payload) {
        ForgeNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    @Override
    public void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, ModPayload payload) {
        ForgeNetwork.CHANNEL.send(
                PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunk(chunk.x, chunk.z)), payload);
    }
}
