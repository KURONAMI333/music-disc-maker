package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge での SimpleChannel 定義 + payload 登録。受信時は (main thread で) common の {@link ModNetwork}
 * ハンドラへ委譲する。{@code consumerMainThread} が enqueueWork + setPacketHandled を内部でやるので
 * ハンドラ本体は処理だけ書けばよい。S→C ハンドラは client 専用 ({@code ClientPlaybackHandler} 経由)
 * なので dedicated server では never-load。
 */
public final class ForgeNetwork {

    private static final String PROTOCOL_VERSION = "1";

    // acceptMissingOr: 相手側にこのチャンネルが無い場合 (ABSENT) でも接続を許す。
    // これが無いと MDM 入りクライアントがバニラ/MDM 無しサーバーに接続できない
    // (counterpart 不在を版不一致として弾くため)。
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MusicDiscMaker.MODID, "main"),
            () -> PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));

    private ForgeNetwork() {
    }

    public static void register() {
        int id = 0;

        // client → server: URL コミット。
        CHANNEL.messageBuilder(ResolveUrlPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ResolveUrlPayload::write)
                .decoder(ResolveUrlPayload::read)
                .consumerMainThread((msg, ctx) -> {
                    final ServerPlayer sender = ctx.get().getSender();
                    if (sender != null) {
                        ModNetwork.handleResolveUrl(msg, sender);
                    }
                })
                .add();

        // server → client: 再生制御。
        CHANNEL.messageBuilder(PlayDiscPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PlayDiscPayload::write)
                .decoder(PlayDiscPayload::read)
                .consumerMainThread((msg, ctx) -> ModNetwork.handlePlay(msg))
                .add();

        CHANNEL.messageBuilder(StopDiscPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StopDiscPayload::write)
                .decoder(StopDiscPayload::read)
                .consumerMainThread((msg, ctx) -> ModNetwork.handleStop(msg))
                .add();

        // Sophisticated Backpacks の Jukebox Upgrade 互換。ペイロードは SC 非依存なので無条件登録 (id 列を両側一致させる)。
        // 受信ハンドラ内の SC 参照は invoke 時のみ class-load (SC 未導入なら送信されないので到達しない)。
        CHANNEL.messageBuilder(com.kuronami.musicdiscmaker.compat.sophisticatedcore.BackpackPlayDiscPayload.class,
                        id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(com.kuronami.musicdiscmaker.compat.sophisticatedcore.BackpackPlayDiscPayload::write)
                .decoder(com.kuronami.musicdiscmaker.compat.sophisticatedcore.BackpackPlayDiscPayload::read)
                .consumerMainThread((msg, ctx) ->
                        com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompatClient.play(msg))
                .add();
    }
}
