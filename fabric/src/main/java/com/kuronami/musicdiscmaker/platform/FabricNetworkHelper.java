package com.kuronami.musicdiscmaker.platform;

import java.util.Collection;

import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/** Fabric 実装: payload 送信を Fabric Networking API に委譲する。 */
public class FabricNetworkHelper implements INetworkHelper {

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        // client 専用経路。dedicated server では呼ばれないので、client 専用 API はこの method 本体内でのみ参照する。
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(payload);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, CustomPacketPayload payload) {
        for (final ServerPlayer player : PlayerLookup.tracking(level, chunk)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    @Override
    public Collection<ServerPlayer> playersTrackingChunk(ServerLevel level, ChunkPos chunk) {
        return PlayerLookup.tracking(level, chunk);
    }

    @Override
    public void sendToPlayersTrackingEntityAndSelf(Entity entity, CustomPacketPayload payload) {
        // PlayerLookup.tracking は entity が player のとき本人を含まない (Fabric API の明記された仕様)。
        // 本人を足さないと、手持ちブームボックスが持ち主にだけ聞こえなくなる。
        for (final ServerPlayer player : PlayerLookup.tracking(entity)) {
            ServerPlayNetworking.send(player, payload);
        }
        if (entity instanceof ServerPlayer self) {
            ServerPlayNetworking.send(self, payload);
        }
    }
}
