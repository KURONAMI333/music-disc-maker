package com.kuronami.musicdiscmaker.compat.aeronautics;

import java.util.List;
import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.platform.Services;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * server 側: Create Aeronautics の物理 sub-level に載った強化版ジュークボックスの再生を、その sub-level を
 * tracking 中の player へ届ける。
 *
 * <h2>なぜ既存の trigger を使えないか</h2>
 * MDM の通常再生 trigger は vanilla の chunk-tracking に乗る ({@code sendToPlayersTrackingChunk} +
 * {@code ChunkWatchEvent.Watch} での late-join 再送)。しかし Sable の sub-level plot は off-map の専用領域で、
 * Sable 独自の TCP/UDP 同期 ({@code ClientboundStartTrackingSubLevelPacket} 等) で client へ配られ、vanilla の
 * player chunk-tracking を通らない。よって組立後の音源ブロックの再生開始は vanilla trigger では client へ
 * 届かない。ここが Sable ネイティブの tracking へ橋渡しする。
 *
 * <h2>方式 (捕獲式との違い)</h2>
 * 捕獲式 (Create 本家) は組立の一撃 (startMoving) で送れば済むが、変換式はブロックが実在し tick し続け、
 * player の sub-level tracking は動的に増減する。そこで server tick を間引き ({@link #SWEEP_INTERVAL} tick 毎)
 * で回し、各 sub-level の tracking player 全員へ現在再生中の custom disc を送る。client 側 ({@link SableAudioClient})
 * が plot 座標 key で dedup するため、周期再送はコスト無く late-tracking (後から飛空艇に近づいた player) を拾う。
 * 走査対象は plot の loaded chunk のみ = 飛空艇1機ぶんで軽い。sub-level が無い level では即 return。
 *
 * <p>このクラスは Sable 型を参照するため、game event bus への登録は {@link SableCompat} の isLoaded gate 通過後に
 * だけ行う (登録されなければ class-load されない = Sable 非導入で NoClassDefFoundError にならない)。
 */
public final class SableServerAudio {

    /** server tick 何 tick 毎に sub-level を走査するか (20 = 1 秒)。組立→再生開始の遅延はこの範囲。 */
    private static final int SWEEP_INTERVAL = 20;

    /** Sable 内部が例外を投げた時の警告を 1 度だけ warn、以後は debug に落として tick ログを溢れさせない。 */
    private static boolean warnedOnce = false;

    private SableServerAudio() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();
        if ((server.getTickCount() % SWEEP_INTERVAL) != 0) {
            return;
        }
        for (final ServerLevel level : server.getAllLevels()) {
            try {
                sweepLevel(server, level);
            } catch (final Throwable t) {
                // Sable 内部 API が未検証環境で throw しても server tick を巻き込まない (soft-compat 隔離)。
                if (!warnedOnce) {
                    warnedOnce = true;
                    MusicDiscMaker.LOGGER.warn("Sable sub-level 走査で例外 (以後は debug)", t);
                } else {
                    MusicDiscMaker.LOGGER.debug("Sable sub-level 走査で例外", t);
                }
            }
        }
    }

    private static void sweepLevel(MinecraftServer server, ServerLevel level) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        final List<ServerSubLevel> subLevels = container.getAllSubLevels();
        if (subLevels == null || subLevels.isEmpty()) {
            return; // この level に物理 sub-level が無い = 通常世界。走査不要。
        }
        for (final ServerSubLevel sub : subLevels) {
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            final var trackers = sub.getTrackingPlayers();
            if (trackers == null || trackers.isEmpty()) {
                continue; // 誰も見ていない sub-level には送らない。
            }
            for (final PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
                final LevelChunk chunk = holder.getChunk();
                if (chunk == null) {
                    continue;
                }
                for (final BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof GoldenJukeboxBlockEntity jukebox) {
                        sendJukebox(server, trackers, jukebox);
                    }
                }
            }
        }
    }

    private static void sendJukebox(MinecraftServer server, Iterable<UUID> trackers,
            GoldenJukeboxBlockEntity jukebox) {
        if (!jukebox.hasDisc() || jukebox.isPaused()) {
            return;
        }
        final CustomTrackData track = jukebox.currentTrack();
        if (track == null || track.isEmpty()) {
            return; // vanilla ディスク / 空 track = LavaPlayer 追従の対象外。
        }
        // 頭出し位置は壁時計基準 (syncElapsedMs) で送る。tick 基準の currentElapsedMs() だと TPS が
        // 20 を割った分だけずれる (実音・曲送りの判定はどちらも壁時計なので、tick 基準はここだけ
        // 実態とずれた値になる)。hasDisc && !isPaused だけでは「実際に再生中」を保証しない
        // (非リピートアルバムの再生完了直後は disc が入ったまま・pause もしていないのに再生は止まって
        // いる) ので、syncElapsedMs() が -1 (= 再生していない) を返す窓はここで弾いて送らない。
        final long elapsedMs = jukebox.syncElapsedMs();
        if (elapsedMs < 0L) {
            return;
        }
        final SubLevelPlayDiscPayload payload = new SubLevelPlayDiscPayload(
                jukebox.getBlockPos(), track, elapsedMs,
                jukebox.getRangeBlocks(), jukebox.getVolumePercent(), jukebox.isDirectional());
        for (final UUID uuid : trackers) {
            final ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                Services.NETWORK.sendToPlayer(player, payload);
            }
        }
    }
}
