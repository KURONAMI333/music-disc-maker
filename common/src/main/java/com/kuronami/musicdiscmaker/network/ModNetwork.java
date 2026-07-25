package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * loader 非依存の payload ハンドラ本体。payload 型の登録・受信配線は各ローダーの entry で行い
 * (NeoForge={@code NeoForgePayloads} / Fabric)、受信時 (main thread) にここへ委譲する。
 *
 * <p>GUI はボタンレスなので client は URL をコミットするだけ ({@link ResolveUrlPayload})、
 * 解決と disc 生成は {@code DiscFabrication} が「URL + 空ディスク」が揃ったら自動で行う。
 * S→C 再生 packet は {@link ClientPlaybackHandler} 経由で client のみロード。
 */
public final class ModNetwork {

    private static final double MAX_REACH_SQR = 64.0;

    private ModNetwork() {
    }

    // ── client → server ハンドラ (loader が ServerPlayer を渡す) ──

    public static void handleResolveUrl(ResolveUrlPayload payload, ServerPlayer player) {
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        if (player.level().getBlockEntity(pos) instanceof MusicDiscMakerBlockEntity maker) {
            maker.setCurrentUrl(payload.url()); // setCurrentUrl が DiscFabrication.process を呼ぶ
        }
    }

    public static void handleConfigureJukebox(ConfigureJukeboxPayload payload, ServerPlayer player) {
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        if (player.level().getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be) {
            be.setRangeBlocks(payload.rangeBlocks());
            be.setVolumePercent(payload.volumePercent());
            be.setRepeat(payload.repeat());
            be.setPaused(payload.paused());
        }
    }

    public static void handleSeekJukebox(SeekJukeboxPayload payload, ServerPlayer player) {
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        if (player.level().getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be) {
            be.seekTo(payload.offsetMs());
        }
    }

    // ── server → client ハンドラ (client でのみ invoke される。dedicated server では never-load) ──

    public static void handlePlay(PlayDiscPayload payload) {
        ClientPlaybackHandler.play(payload);
    }

    public static void handleStop(StopDiscPayload payload) {
        ClientPlaybackHandler.stop(payload);
    }
}
