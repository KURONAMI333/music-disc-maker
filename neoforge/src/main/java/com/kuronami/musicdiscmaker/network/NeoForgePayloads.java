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
        // 版は common 側の 1 箇所が正本 (ModNetwork.PROTOCOL_VERSION)。optional() は付けない —
        // optional な channel は版が食い違うと黙って無効化され、「接続はできるのに何も鳴らない」
        // に化ける。ここでは接続を断らせる (fail-fast)。
        final PayloadRegistrar registrar = event.registrar(ModNetwork.PROTOCOL_VERSION);

        // client → server: URL コミット (BlockEntity に保存 → 条件が揃えば自動生成)
        registrar.playToServer(ModPayloadTypes.ResolveUrl.TYPE, ModPayloadTypes.ResolveUrl.STREAM_CODEC,
                NeoForgePayloads::handleResolveUrl);

        // client → server: 強化版ジュークボックスの設定適用
        registrar.playToServer(ModPayloadTypes.ConfigureJukebox.TYPE, ModPayloadTypes.ConfigureJukebox.STREAM_CODEC,
                NeoForgePayloads::handleConfigureJukebox);

        // client → server: 強化版ジュークボックスのシークバー頭出し
        registrar.playToServer(ModPayloadTypes.SeekJukebox.TYPE, ModPayloadTypes.SeekJukebox.STREAM_CODEC,
                NeoForgePayloads::handleSeekJukebox);
        registrar.playToServer(ModPayloadTypes.NavigateJukebox.TYPE, ModPayloadTypes.NavigateJukebox.STREAM_CODEC,
                NeoForgePayloads::handleNavigateJukebox);
        registrar.playToServer(ModPayloadTypes.ShuffleJukebox.TYPE, ModPayloadTypes.ShuffleJukebox.STREAM_CODEC,
                NeoForgePayloads::handleShuffleJukebox);
        registrar.playToServer(ModPayloadTypes.ControlBoombox.TYPE, ModPayloadTypes.ControlBoombox.STREAM_CODEC,
                NeoForgePayloads::handleControlBoombox);

        // server → client (再生制御)。
        registrar.playToClient(ModPayloadTypes.Play.TYPE, ModPayloadTypes.Play.STREAM_CODEC,
                (msg, context) -> context.enqueueWork(() -> ModNetwork.handlePlay(msg.inner())));
        registrar.playToClient(ModPayloadTypes.PlayVanilla.TYPE, ModPayloadTypes.PlayVanilla.STREAM_CODEC,
                (msg, context) -> context.enqueueWork(() -> ModNetwork.handleVanillaPlay(msg.inner())));
        registrar.playToClient(ModPayloadTypes.Stop.TYPE, ModPayloadTypes.Stop.STREAM_CODEC,
                (msg, context) -> context.enqueueWork(() -> ModNetwork.handleStop(msg.inner())));

        // server → client: 携帯ブームボックス (1 秒ごとの keep-alive を兼ねる)。
        registrar.playToClient(ModPayloadTypes.BoomboxPlay.TYPE, ModPayloadTypes.BoomboxPlay.STREAM_CODEC,
                (msg, context) -> context.enqueueWork(() -> ModNetwork.handleBoomboxPlay(msg.inner())));
        registrar.playToClient(ModPayloadTypes.BoomboxStop.TYPE, ModPayloadTypes.BoomboxStop.STREAM_CODEC,
                (msg, context) -> context.enqueueWork(() -> ModNetwork.handleBoomboxStop(msg.inner())));
        registrar.playToClient(ModPayloadTypes.BoomboxState.TYPE, ModPayloadTypes.BoomboxState.STREAM_CODEC,
                (msg, context) -> context.enqueueWork(() -> ModNetwork.handleBoomboxState(msg.inner())));
        registrar.playToClient(ModPayloadTypes.SpeakerSet.TYPE, ModPayloadTypes.SpeakerSet.STREAM_CODEC,
                (msg, context) -> context.enqueueWork(() -> ModNetwork.handleSpeakerSet(msg.inner())));

        // Sophisticated Backpacks の Jukebox Upgrade 互換 (SC 非依存ペイロード、無条件登録)。
        com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat.registerPayload(registrar);
        //? if >=1.21.2 {
        //?} else {
/*        com.kuronami.musicdiscmaker.compat.create.CreateCompat.registerPayload(registrar);

        com.kuronami.musicdiscmaker.compat.aeronautics.SableCompat.registerPayload(registrar);
        */
        //?}
    }

    private static void handleResolveUrl(ModPayloadTypes.ResolveUrl msg, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> ModNetwork.handleResolveUrl(msg.inner(), player));
    }

    private static void handleConfigureJukebox(ModPayloadTypes.ConfigureJukebox msg, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> ModNetwork.handleConfigureJukebox(msg.inner(), player));
    }

    private static void handleSeekJukebox(ModPayloadTypes.SeekJukebox msg, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> ModNetwork.handleSeekJukebox(msg.inner(), player));
    }

    private static void handleNavigateJukebox(ModPayloadTypes.NavigateJukebox msg, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> ModNetwork.handleNavigateJukebox(msg.inner(), player));
    }

    private static void handleShuffleJukebox(ModPayloadTypes.ShuffleJukebox msg, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> ModNetwork.handleShuffleJukebox(msg.inner(), player));
    }

    private static void handleControlBoombox(ModPayloadTypes.ControlBoombox msg, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> ModNetwork.handleControlBoombox(msg.inner(), player));
    }
}



