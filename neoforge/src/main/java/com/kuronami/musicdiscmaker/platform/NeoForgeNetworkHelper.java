package com.kuronami.musicdiscmaker.platform;

import java.util.Collection;

import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/** NeoForge 実装: payload 送信を {@link PacketDistributor} に委譲する。 */
public class NeoForgeNetworkHelper implements INetworkHelper {

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk, payload);
    }

    /**
     * {@code PacketDistributor} は宛先解決だけを取り出す口を持たないので、vanilla の {@code ChunkMap}
     * から直接引く ({@code sendToPlayersTrackingChunk} の内部と同じ集合)。
     */
    @Override
    public Collection<ServerPlayer> playersTrackingChunk(ServerLevel level, ChunkPos chunk) {
        return level.getChunkSource().chunkMap.getPlayers(chunk, false);
    }

    @Override
    public void sendToPlayersTrackingEntityAndSelf(Entity entity, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload);
    }
}
