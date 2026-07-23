package com.kuronami.musicdiscmaker.platform.services;

import com.kuronami.musicdiscmaker.network.ModPayload;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * payload 送信の loader 抽象。channel の登録・受信ハンドラ配線は各ローダーの entry で行い
 * (Forge=SimpleChannel / Fabric=ServerPlayNetworking 等)、受信時は common の {@code ModNetwork} の
 * ハンドラへ委譲する。
 */
public interface INetworkHelper {

    /** client → server。client 専用経路 (dedicated server では呼ばれない)。 */
    void sendToServer(ModPayload payload);

    /** server → 特定 player。 */
    void sendToPlayer(ServerPlayer player, ModPayload payload);

    /** server → 指定 chunk を追跡中の全 player。 */
    void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, ModPayload payload);

    /**
     * server → 指定 entity を追跡中の全 player。移動する entity (Create contraption 等) に載った音源の
     * 再生を、その entity を見ている client へ届けるのに使う。
     */
    void sendToPlayersTrackingEntity(Entity entity, ModPayload payload);
}
