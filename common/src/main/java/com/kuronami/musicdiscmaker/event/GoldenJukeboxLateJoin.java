package com.kuronami.musicdiscmaker.event;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * chunk が player へ送られる瞬間に、その chunk 内で再生中の強化版ジュークボックスへ、現在の再生位置と
 * per-block 設定 (range/volume) を乗せた再生 packet を送る (途中から同期再生)。
 *
 * <p>強化版ジュークボックスは ActiveDiscRegistry を使わず BE が権威なので、chunk 内の
 * {@link GoldenJukeboxBlockEntity} を直接走査する。各ローダーの chunk-watch 経路
 * (NeoForge {@code ChunkWatchEvent.Watch} / Fabric {@code ChunkMap.markChunkPendingToSend} mixin)
 * から呼ぶ。
 */
public final class GoldenJukeboxLateJoin {

    private GoldenJukeboxLateJoin() {
    }

    public static void resend(ServerLevel level, ChunkPos chunkPos, ServerPlayer player) {
        final LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        for (final BlockPos pos : chunk.getBlockEntitiesPos()) {
            if (!new ChunkPos(pos).equals(chunkPos)) {
                continue;
            }
            if (chunk.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be) {
                be.resendTo(player);
            }
        }
    }
}
