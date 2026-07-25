package com.kuronami.musicdiscmaker.event;

import java.util.HashSet;
import java.util.Set;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * スピーカーの chunk が player へ送られる瞬間に、その音源の現在の再生とスピーカー集合を送る。
 *
 * <p>{@link GoldenJukeboxLateJoin} は音源チャンクを起点に走査するため、音源から遠く離れたスピーカーの
 * 傍にいる player には届かない。スピーカー側の永続参照 ({@code sourcePos}) から音源を引くことで、
 * 配送の非対称を解消する。
 *
 * <p>音源チャンクが未ロードなら何もしない (強制ロードはしない)。同じ player に音源チャンク側からも
 * 届いた場合は client 側の再生 dedup が吸収する。
 */
public final class SpeakerLateJoin {

    private SpeakerLateJoin() {
    }

    public static void resend(ServerLevel level, ChunkPos chunkPos, ServerPlayer player) {
        final LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        Set<BlockPos> sent = null;
        for (final BlockPos pos : chunk.getBlockEntitiesPos()) {
            if (!new ChunkPos(pos).equals(chunkPos)) {
                continue;
            }
            if (!(chunk.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker)) {
                continue;
            }
            final BlockPos sourcePos = speaker.getSourcePos();
            if (sourcePos == null || !level.isLoaded(sourcePos)) {
                continue;
            }
            if (sent == null) {
                sent = new HashSet<>();
            }
            if (!sent.add(sourcePos)) {
                continue; // 同一音源に複数スピーカーがぶら下がっている
            }
            if (level.getBlockEntity(sourcePos) instanceof GoldenJukeboxBlockEntity jukebox) {
                // resendTo は再生中のときだけ packet を出し、その直後にスピーカー集合も送る。
                jukebox.resendTo(player);
            }
        }
    }
}
