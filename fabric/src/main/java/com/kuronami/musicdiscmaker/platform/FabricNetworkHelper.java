package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/** Fabric 実装: payload 送信を Fabric Networking API (1.20.1 旧 API: id + FriendlyByteBuf) に委譲する。 */
public class FabricNetworkHelper implements INetworkHelper {

    @Override
    public void sendToServer(ModPayload payload) {
        // client 専用経路。dedicated server では呼ばれないので client 専用 API はこの method 本体内でのみ参照する。
        final FriendlyByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(payload.id(), buf);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ModPayload payload) {
        final FriendlyByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        ServerPlayNetworking.send(player, payload.id(), buf);
    }

    @Override
    public void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, ModPayload payload) {
        for (final ServerPlayer player : PlayerLookup.tracking(level, chunk)) {
            final FriendlyByteBuf buf = PacketByteBufs.create();
            payload.write(buf);
            ServerPlayNetworking.send(player, payload.id(), buf);
        }
    }
}
