package com.kuronami.musicdiscmaker.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge での payload 型登録 + 受信配線。受信時は (main thread で) common の {@link ModNetwork}
 * ハンドラへ委譲する。client 専用ハンドラは {@code ClientPlaybackHandler} 経由 (lambda 内参照) なので
 * dedicated server では never-load。
 */
public final class NeoForgePayloads {

    private NeoForgePayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");

        // client → server: URL コミット (BlockEntity に保存 → 条件が揃えば自動生成)
        registrar.playToServer(ResolveUrlPayload.TYPE, ResolveUrlPayload.STREAM_CODEC,
                NeoForgePayloads::handleResolveUrl);

        // client → server: 強化版ジュークボックスの設定適用
        registrar.playToServer(ConfigureJukeboxPayload.TYPE, ConfigureJukeboxPayload.STREAM_CODEC,
                NeoForgePayloads::handleConfigureJukebox);

        // client → server: 強化版ジュークボックスのシークバー頭出し
        registrar.playToServer(SeekJukeboxPayload.TYPE, SeekJukeboxPayload.STREAM_CODEC,
                NeoForgePayloads::handleSeekJukebox);

        // client → server: スピーカーの音量・可聴範囲の適用
        registrar.playToServer(SpeakerConfigPayload.TYPE, SpeakerConfigPayload.STREAM_CODEC,
                NeoForgePayloads::handleConfigureSpeaker);

        // server → client (再生制御)。
        registrar.playToClient(PlayDiscPayload.TYPE, PlayDiscPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ModNetwork.handlePlay(payload)));
        registrar.playToClient(StopDiscPayload.TYPE, StopDiscPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ModNetwork.handleStop(payload)));
        // server → client: 音源にぶら下がる有効スピーカー集合 (再生 packet から分離)。
        registrar.playToClient(SpeakerSetPayload.TYPE, SpeakerSetPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ModNetwork.handleSpeakerSet(payload)));
        // server → client: 手持ちブームボックスの再生 (keep-alive 兼 late-join) と停止。
        registrar.playToClient(BoomboxPlayPayload.TYPE, BoomboxPlayPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ModNetwork.handleBoomboxPlay(payload)));
        registrar.playToClient(BoomboxStopPayload.TYPE, BoomboxStopPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ModNetwork.handleBoomboxStop(payload)));

        // Sophisticated Backpacks の Jukebox Upgrade 互換 (SC 非依存ペイロード、無条件登録)。
        com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat.registerPayload(registrar);

        // Create contraption 互換 (Create 非依存ペイロード、無条件登録)。
        com.kuronami.musicdiscmaker.compat.create.CreateCompat.registerPayload(registrar);

        // Create Aeronautics (Sable) sub-level 互換 (Sable 非依存ペイロード、無条件登録)。
        com.kuronami.musicdiscmaker.compat.aeronautics.SableCompat.registerPayload(registrar);
    }

    private static void handleResolveUrl(ResolveUrlPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> ModNetwork.handleResolveUrl(payload, player));
    }

    private static void handleConfigureJukebox(ConfigureJukeboxPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> ModNetwork.handleConfigureJukebox(payload, player));
    }

    private static void handleSeekJukebox(SeekJukeboxPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> ModNetwork.handleSeekJukebox(payload, player));
    }

    private static void handleConfigureSpeaker(SpeakerConfigPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> ModNetwork.handleConfigureSpeaker(payload, player));
    }
}
