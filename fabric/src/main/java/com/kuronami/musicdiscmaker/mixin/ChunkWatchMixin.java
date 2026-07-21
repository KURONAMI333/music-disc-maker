package com.kuronami.musicdiscmaker.mixin;

import java.util.List;

import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

import com.kuronami.musicdiscmaker.block.EnhancedJukeboxBlockEntity;

/**
 * 後から jukebox の chunk に入った player へ、経過 offset 付きで再生 packet を送る (途中から同期再生)。
 *
 * <p>Fabric には NeoForge/Forge の {@code ChunkWatchEvent.Watch} 相当が無いため、その chunk が player へ
 * 送られる瞬間＝vanilla {@code ChunkMap.updateChunkTracking(ServerPlayer, ChunkPos, MutableObject, boolean, boolean)}
 * を intercept する (Forge 1.20.1 もこの method を patch して Watch イベントを発火する)。{@code load && !wasLoaded}
 * = chunk が新規に追跡開始される瞬間のみ送る。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkWatchMixin {

    @Inject(
            method = "updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;Lorg/apache/commons/lang3/mutable/MutableObject;ZZ)V",
            at = @At("HEAD"))
    private void musicDiscMaker$onChunkTrack(ServerPlayer player, ChunkPos chunkPos, MutableObject<?> packetCache,
            boolean wasLoaded, boolean load, CallbackInfo ci) {
        if (!load || wasLoaded) {
            return; // chunk が新規に player へ送られる瞬間のみ
        }
        final ServerLevel level = player.serverLevel();
        final long now = System.currentTimeMillis();
        final List<ActiveDiscRegistry.Playing> playing =
                ActiveDiscRegistry.activeInChunk(level.dimension(), chunkPos, now);
        for (final ActiveDiscRegistry.Playing p : playing) {
            // 撤去済み jukebox の stale エントリを late-joiner に送らない (爆発/ピストン/コマンド除去の掃除)。
            if (!(level.getBlockEntity(p.pos()) instanceof JukeboxBlockEntity jb)
                    || !jb.getFirstItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
                ActiveDiscRegistry.stop(level.dimension(), p.pos());
                continue;
            }
            final long elapsed = Math.max(0L, now - p.startMillis());
            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(p.pos(), p.track(), elapsed));
        }

        final var chunk = level.getChunk(chunkPos.x, chunkPos.z);
        for (final BlockPos bePos : chunk.getBlockEntitiesPos()) {
            if (!new ChunkPos(bePos).equals(chunkPos)) continue;
            // 強化版ジュークボックスは BE 自身が権威。現在位置で per-block 設定つきの再送を任せる。
            if (chunk.getBlockEntity(bePos) instanceof EnhancedJukeboxBlockEntity enhanced) {
                enhanced.resendTo(player);
                continue;
            }
            if (ActiveDiscRegistry.isTracked(level.dimension(), bePos)) continue;
            if (!(chunk.getBlockEntity(bePos) instanceof JukeboxBlockEntity jukebox)) continue;
            final ItemStack disc = jukebox.getFirstItem();
            if (!disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) continue;
            if (!CustomMusicDiscItem.hasTrack(disc)) continue;
            final CustomTrackData track = CustomMusicDiscItem.getTrack(disc);
            if (track == null || track.isEmpty()) continue;
            ActiveDiscRegistry.start(level.dimension(), bePos, track, now);
            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(bePos, track, 0L));
        }
    }
}
