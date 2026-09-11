package com.kuronami.musicdiscmaker.platform.services;

import com.kuronami.musicdiscmaker.network.ModPayload;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * payload 送信の loader 抽象。受け取るのは版に依存しない {@link ModPayload} で、各版の packet 機構
 * (1.20.5+ の {@code CustomPacketPayload} / 1.20.1 の生 channel) への適合は実装側が行う。
 * payload 型の登録・受信ハンドラ配線も各ローダーの entry で行い、受信時は common の
 * {@code ModNetwork} のハンドラへ委譲する。
 */
public interface INetworkHelper {

    /** client → server。client 専用経路 (dedicated server では呼ばれない)。 */
    void sendToServer(ModPayload payload);

    /** server → 特定 player。 */
    void sendToPlayer(ServerPlayer player, ModPayload payload);

    /** server → 指定 chunk を追跡中の全 player。 */
    void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, ModPayload payload);
}
