package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * payload 登録 + server 側ハンドラ。
 * GUI はボタンレスなので client は URL をコミットするだけ ({@link ResolveUrlPayload})、
 * 解決と disc 生成は {@code DiscFabrication} が「URL + 空ディスク」が揃ったら自動で行う。
 * S→C 再生 packet は {@code ClientPlaybackHandler} 経由で client のみロード。
 */
public final class ModNetwork {

    private static final double MAX_REACH_SQR = 64.0;

    private ModNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");

        // client → server: URL コミット (BlockEntity に保存 → 条件が揃えば自動生成)
        registrar.playToServer(ResolveUrlPayload.TYPE, ResolveUrlPayload.STREAM_CODEC, ModNetwork::handleSetUrl);

        // client → server: 強化版ジュークボックスの設定 (範囲/音量/リピート/再生停止) を適用
        registrar.playToServer(ConfigureJukeboxPayload.TYPE, ConfigureJukeboxPayload.STREAM_CODEC,
                ModNetwork::handleConfigureJukebox);

        // server → client (再生制御)。client 専用ハンドラは lambda 内参照なので server では never-load。
        registrar.playToClient(PlayDiscPayload.TYPE, PlayDiscPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler.play(payload)));
        registrar.playToClient(StopDiscPayload.TYPE, StopDiscPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler.stop(payload)));

        // Sophisticated Backpacks (Jukebox Upgrade) 互換の再生ペイロード。SC 非依存なので無条件登録してよい
        // (受信ハンドラ内の SC 参照は invoke 時のみ class-load される)。
        com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat.registerPayload(registrar);
    }

    private static void handleSetUrl(ResolveUrlPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        if (player.level().getBlockEntity(pos) instanceof MusicDiscMakerBlockEntity maker) {
            maker.setCurrentUrl(payload.url()); // setCurrentUrl が DiscFabrication.process を呼ぶ
        }
    }

    private static void handleConfigureJukebox(ConfigureJukeboxPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
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
}
