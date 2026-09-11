package com.kuronami.musicdiscmaker.mixin;

import java.util.List;

//? if >=1.21 {
//?} else {
/*import org.apache.commons.lang3.mutable.MutableObject;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
//?} else {
//?}
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.event.ActiveDiscPersistence;
import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
//? if >=1.21 {
//?} else {
/*import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
*///?}
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?} else {
//?}
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
//? if >=1.21 {
import net.minecraft.world.level.chunk.LevelChunk;
//?} else {

/*import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
*///?}

/**
 * 後から jukebox の chunk に入った player へ、経過 offset 付きで再生 packet を送る (途中から同期再生)。
 *
 * <p>Fabric には NeoForge の {@code ChunkWatchEvent.Watch} 相当が無いため、その chunk が player へ
 * 送られる正確な瞬間＝vanilla {@code ChunkMap.markChunkPendingToSend(ServerPlayer, LevelChunk)}（static）
 * を intercept する。NeoForge はこの同一 method を patch して Watch イベントを発火している。
 * ({@code (ServerPlayer, ChunkPos)} overload と区別するため full descriptor を指定。)
 *
 * <p>処理内容は NeoForge の {@code JukeboxHandler.onChunkWatch} と同一 (common API:
 * ActiveDiscRegistry / GoldenJukeboxBlockEntity.resendTo)。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkWatchMixin {

    @Inject(
            //? if >=1.21 {
            method = "markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            //?} else {
            /*method = "updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;Lorg/apache/commons/lang3/mutable/MutableObject;ZZ)V",
            *///?}
            at = @At("HEAD"))
    //? if >=1.21.2 {
    private static void musicDiscMaker$onChunkSend(ServerPlayer player, LevelChunk chunk, CallbackInfo ci) {
        final ServerLevel level = player.level();
        final ChunkPos chunkPos = chunk.getPos();
    //?} elif >=1.21 {
    /*private static void musicDiscMaker$onChunkSend(ServerPlayer player, LevelChunk chunk, CallbackInfo ci) {
        final ServerLevel level = player.serverLevel();
        final ChunkPos chunkPos = chunk.getPos();
    *///?} else {
    /*private void musicDiscMaker$onChunkTrack(ServerPlayer player, ChunkPos chunkPos, MutableObject<?> packetCache,
            boolean wasLoaded, boolean load, CallbackInfo ci) {
        if (!load || wasLoaded) {
            return; // chunk が新規に player へ送られる瞬間のみ
        }
        final ServerLevel level = player.serverLevel();
    *///?}
        ActiveDiscPersistence.hydrate(level);
        final long now = System.currentTimeMillis();
        //? if >=1.21.2 {
        //?} elif >=1.21 {

        /*// 強化版ジュークボックス (BE 権威・ActiveDiscRegistry 非使用) の late-join 再送。
        com.kuronami.musicdiscmaker.event.GoldenJukeboxLateJoin.resend(level, chunkPos, player);

        *///?} else {
        //?}
        final List<ActiveDiscRegistry.Playing> known =
                ActiveDiscRegistry.knownInChunk(level.dimension(), chunkPos);
        for (final ActiveDiscRegistry.Playing p : known) {
            // 撤去済み jukebox の stale エントリを late-joiner に送らない (爆発/ピストン/コマンド除去の掃除)。
            // 鳴り終わったエントリもここで捨てる (これが registry の掃除経路)。
            if (!(level.getBlockEntity(p.pos()) instanceof JukeboxBlockEntity jb)
                    //? if >=1.21 {
                    || !jb.getTheItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
                    //?} else {
                    /*|| !jb.getFirstItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
                    *///?}
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

        //? if >=1.21 {
        //?} else {
        /*final var chunk = level.getChunk(chunkPos.x, chunkPos.z);
        *///?}
        for (final BlockPos bePos : chunk.getBlockEntitiesPos()) {
            //? if >=26.2 {
            if (!ChunkPos.containing(bePos).equals(chunkPos)) continue;
            if (ActiveDiscRegistry.isTracked(level.dimension(), bePos)) continue;
            //?} elif >=1.21.2 {
            /*if (!new ChunkPos(bePos).equals(chunkPos)) continue;
            if (ActiveDiscRegistry.isTracked(level.dimension(), bePos)) continue;
            *///?} elif >=1.21 {
            /*if (!new ChunkPos(bePos).equals(chunkPos)) continue;
            if (ActiveDiscRegistry.isActive(level.dimension(), bePos)) continue;
            *///?} else {
            /*if (!new ChunkPos(bePos).equals(chunkPos)) continue;
            // 強化版ジュークボックスは BE 自身が権威。現在位置で per-block 設定つきの再送を任せる。
            if (chunk.getBlockEntity(bePos) instanceof GoldenJukeboxBlockEntity enhanced) {
                enhanced.resendTo(player);
                continue;
            }
            if (ActiveDiscRegistry.isTracked(level.dimension(), bePos)) continue;
            *///?}
            if (!(chunk.getBlockEntity(bePos) instanceof JukeboxBlockEntity jukebox)) continue;
            //? if >=1.21 {
            final ItemStack disc = jukebox.getTheItem();
            //?} else {
            /*final ItemStack disc = jukebox.getFirstItem();
            *///?}
            if (!disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) continue;
            //? if >=1.21 {
            final CustomTrackData track = disc.get(ModDataComponents.CUSTOM_TRACK.get());
            //?} else {
            /*if (!CustomMusicDiscItem.hasTrack(disc)) continue;
            final CustomTrackData track = CustomMusicDiscItem.getTrack(disc);
            *///?}
            if (track == null || track.isEmpty()) continue;
            ActiveDiscRegistry.start(level.dimension(), bePos, track, now);
            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(bePos, track, 0L));
        }
        //? if >=26.2 {

        // 強化版ジュークボックスは ActiveDiscRegistry を使わず BE が権威。chunk 内の BE を直接走査して
        // 現在の再生位置 + per-block 設定で追従再生させる。
        for (final BlockPos bePos : chunk.getBlockEntitiesPos()) {
            if (!ChunkPos.containing(bePos).equals(chunkPos)) continue;
            if (chunk.getBlockEntity(bePos) instanceof GoldenJukeboxBlockEntity enhanced) {
                enhanced.resendTo(player);
            }
        }
        //?} elif >=1.21.2 {

        /*// 強化版ジュークボックスは ActiveDiscRegistry を使わず BE が権威。chunk 内の BE を直接走査して
        // 現在の再生位置 + per-block 設定で追従再生させる。
        for (final BlockPos bePos : chunk.getBlockEntitiesPos()) {
            if (!new ChunkPos(bePos).equals(chunkPos)) continue;
            if (chunk.getBlockEntity(bePos) instanceof GoldenJukeboxBlockEntity enhanced) {
                enhanced.resendTo(player);
            }
        }
        *///?} else {
        //?}
    }
}

