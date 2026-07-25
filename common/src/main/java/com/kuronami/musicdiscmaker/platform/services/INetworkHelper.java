package com.kuronami.musicdiscmaker.platform.services;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

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

    /** 指定 chunk を追跡中の player。{@link #sendToPlayersTrackingChunks} の材料。 */
    Collection<ServerPlayer> playersTrackingChunk(ServerLevel level, ChunkPos chunk);

    /**
     * server → 指定 chunk 群を追跡中の player へ、<b>player ごとに 1 通だけ</b>送る。
     *
     * <p>チャンク単位で {@link #sendToPlayersTrackingChunk} を繰り返すと、複数のチャンクを追跡して
     * いる player (音源とスピーカーが両方視距離内にある通常の配置) が同じ payload を複数通受け取る。
     * 受け取った client は「停止を挟まない 2 連続の再生要求」を見ることになり、非同期ロードの窓を
     * すり抜けて再生インスタンスが二重に立つ。宛先の集合はチャンクではなく<b>最終受信者である
     * player</b> の粒度で畳む必要がある。
     *
     * <p>{@code ServerPlayer} の {@code equals}/{@code hashCode} は同一性なので、集合へ入れるだけで
     * 畳める。
     */
    default void sendToPlayersTrackingChunks(ServerLevel level, Collection<ChunkPos> chunks,
            CustomPacketPayload payload) {
        final Set<ServerPlayer> recipients = new LinkedHashSet<>();
        for (final ChunkPos chunk : chunks) {
            recipients.addAll(playersTrackingChunk(level, chunk));
        }
        for (final ServerPlayer player : recipients) {
            sendToPlayer(player, payload);
        }
    }

    /**
     * server → 指定 entity を追跡中の全 player <b>+ その entity 自身が player ならその player</b>。
     *
     * <p>「自身を含む」が要件。両ローダーの素の「追跡中の player」は entity が player のとき本人を
     * 含まないので、手持ちブームボックスが持ち主にだけ聞こえない状態になる。
     */
    void sendToPlayersTrackingEntityAndSelf(Entity entity, CustomPacketPayload payload);
}
