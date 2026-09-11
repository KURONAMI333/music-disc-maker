package com.kuronami.musicdiscmaker.platform;

//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.network.ModPayloadTypes;
//?} elif >=1.21 {
//?} else {
/*import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.network.LegacyPayloadIds;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
*/
//?}
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//? if >=1.21.2 {
//?} elif >=1.21 {
/*import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.network.ModPayloadTypes;
*/
//?} else {
//?}
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Fabric 実装。1.20.5+ は中立 payload を {@code CustomPacketPayload} に包んで送り、
 * 1.20.1 は channel id + {@code FriendlyByteBuf} の生経路へ流す。
 */
public class FabricNetworkHelper implements INetworkHelper {

    @Override
    public void sendToServer(ModPayload payload) {
        // client 専用経路。dedicated server では呼ばれないので、client 専用 API はこの method 本体内でのみ参照する。
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(ModPayloadTypes.wrap(payload));
        //?} else {
/*        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                LegacyPayloadIds.of(payload), buf(payload));
        */
        //?}
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ModPayload payload) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, ModPayloadTypes.wrap(payload));
        //?} else {
/*        ServerPlayNetworking.send(player, LegacyPayloadIds.of(payload), buf(payload));
        */
        //?}
    }

    @Override
    public void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, ModPayload payload) {
        for (final ServerPlayer player : PlayerLookup.tracking(level, chunk)) {
            //? if >=1.21 {
            ServerPlayNetworking.send(player, ModPayloadTypes.wrap(payload));
            //?} else {
/*            ServerPlayNetworking.send(player, LegacyPayloadIds.of(payload), buf(payload));
            */
            //?}
        }
    }
    //? if >=1.21 {
    //?} else {
/*    private static FriendlyByteBuf buf(ModPayload payload) {
        final FriendlyByteBuf b = PacketByteBufs.create();
        payload.write(b);
        return b;
    }
    */
    //?}
}


