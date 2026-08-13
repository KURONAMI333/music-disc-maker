package com.kuronami.musicdiscmaker.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 後から jukebox の chunk に入った player へ、経過 offset 付きで再生 packet を送る (途中から同期再生)。
 *
 * <p>Fabric には NeoForge の {@code ChunkWatchEvent.Watch} 相当が無いため、その chunk が player へ
 * 送られる正確な瞬間＝vanilla {@code ChunkMap.markChunkPendingToSend(ServerPlayer, LevelChunk)}（static）
 * を intercept する。NeoForge はこの同一 method を patch して Watch イベントを発火している。
 * ({@code (ServerPlayer, ChunkPos)} overload と区別するため full descriptor を指定。)
 */
@Mixin(ChunkMap.class)
public abstract class ChunkWatchMixin {

    @Inject(
            method = "markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At("HEAD"))
    private static void musicDiscMaker$onChunkSend(ServerPlayer player, LevelChunk chunk, CallbackInfo ci) {
        final ServerLevel level = player.serverLevel();
        final ChunkPos chunkPos = chunk.getPos();
        final long now = System.currentTimeMillis();

        // 強化版ジュークボックス (BE 権威・ActiveDiscRegistry 非使用) の late-join 再送。
        com.kuronami.musicdiscmaker.event.GoldenJukeboxLateJoin.resend(level, chunkPos, player);

        final List<ActiveDiscRegistry.Playing> known =
                ActiveDiscRegistry.knownInChunk(level.dimension(), chunkPos);
        for (final ActiveDiscRegistry.Playing p : known) {
            // 撤去済み jukebox の stale エントリを late-joiner に送らない (爆発/ピストン/コマンド除去の掃除)。
            // 鳴り終わったエントリもここで捨てる (これが registry の掃除経路)。
            if (!(level.getBlockEntity(p.pos()) instanceof JukeboxBlockEntity jb)
                    || !jb.getTheItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
                ActiveDiscRegistry.stop(level.dimension(), p.pos());
                continue;
            }
            // 鳴り終わったディスクは覚えたまま送らない (送ると頭出しで鳴り直す)。
            if (p.finishedBy(now)) {
                continue;
            }
            final long elapsed = Math.max(0L, now - p.startMillis());
            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(p.pos(), p.track(), elapsed));
        }

        for (final BlockPos bePos : chunk.getBlockEntitiesPos()) {
            if (!new ChunkPos(bePos).equals(chunkPos)) continue;
            if (ActiveDiscRegistry.isActive(level.dimension(), bePos)) continue;
            if (!(chunk.getBlockEntity(bePos) instanceof JukeboxBlockEntity jukebox)) continue;
            final ItemStack disc = jukebox.getTheItem();
            if (!disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) continue;
            final CustomTrackData track = disc.get(ModDataComponents.CUSTOM_TRACK.get());
            if (track == null || track.isEmpty()) continue;
            ActiveDiscRegistry.start(level.dimension(), bePos, track, now);
            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(bePos, track, 0L));
        }
    }
}
