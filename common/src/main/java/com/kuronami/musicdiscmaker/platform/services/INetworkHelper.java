package com.kuronami.musicdiscmaker.platform.services;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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

    /**
     * server → 指定 entity を追跡中の全 player <b>+ その entity 自身が player ならその player</b>。
     *
     * <p>「自身を含む」が要件。両ローダーの素の「追跡中の player」は entity が player のとき本人を
     * 含まないので、手持ちブームボックスが持ち主にだけ聞こえない状態になる。
     */
    void sendToPlayersTrackingEntityAndSelf(Entity entity, CustomPacketPayload payload);
}
