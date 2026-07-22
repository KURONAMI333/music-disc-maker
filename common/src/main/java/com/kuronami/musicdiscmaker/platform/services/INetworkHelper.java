package com.kuronami.musicdiscmaker.platform.services;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * payload 送信の loader 抽象。payload 型の登録・受信ハンドラ配線は各ローダーの entry で行い、
 * 受信時は common の {@code ModNetwork} のハンドラへ委譲する。
 */
public interface INetworkHelper {

    /** client → server。client 専用経路 (dedicated server では呼ばれない)。 */
    void sendToServer(CustomPacketPayload payload);

    /** server → 特定 player。 */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** server → 指定 chunk を追跡中の全 player。 */
    void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, CustomPacketPayload payload);
}
