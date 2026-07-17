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

        // server → client (再生制御)。
        registrar.playToClient(PlayDiscPayload.TYPE, PlayDiscPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ModNetwork.handlePlay(payload)));
        registrar.playToClient(StopDiscPayload.TYPE, StopDiscPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ModNetwork.handleStop(payload)));

        // Sophisticated Backpacks の Jukebox Upgrade 互換 (SC 非依存ペイロード、無条件登録)。
        com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat.registerPayload(registrar);
    }

    private static void handleResolveUrl(ResolveUrlPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> ModNetwork.handleResolveUrl(payload, player));
    }
}
