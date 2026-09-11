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

    // 版は common 側の 1 箇所が正本 (ModNetwork.PROTOCOL_VERSION)。
    // acceptMissingOr: 相手側にこのチャンネルが無い場合 (ABSENT) でも接続を許す (MDM 入り client が
    // バニラ/MDM 無し server に繋げるようにするため)。ただし相手がチャンネルを持っているのに版が
    // 食い違えば弾く (fail-fast) — ここを緩めて全部 accept する方式にはしない。緩めると版が食い違った
    // まま接続できてしまい、「接続はできるのに何も鳴らない/取り違える」に化ける。
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MusicDiscMaker.MODID, "main"),
            () -> ModNetwork.PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(ModNetwork.PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(ModNetwork.PROTOCOL_VERSION));

    private ForgeNetwork() {
    }

    /**
     * entity を追跡中の player へ送る。common の {@code INetworkHelper} には置かない
     * (この経路を要るのは Forge の Create 互換だけで、他ローダーに実装義務を負わせる理由が無い)。
     */
    public static void sendToPlayersTrackingEntity(net.minecraft.world.entity.Entity entity, ModPayload payload) {
        CHANNEL.send(net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY.with(() -> entity), payload);
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

        // client → server: 強化版ジュークボックスの設定変更 (末尾に追加して既存 id を動かさない)。
        CHANNEL.messageBuilder(ConfigureJukeboxPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ConfigureJukeboxPayload::write)
                .decoder(ConfigureJukeboxPayload::read)
                .consumerMainThread((msg, ctx) -> {
                    final ServerPlayer sender = ctx.get().getSender();
                    if (sender != null) {
                        ModNetwork.handleConfigureJukebox(msg, sender);
                    }
                })
                .add();

        // client → server: 強化版ジュークボックスのシークバー頭出し (末尾に追加して既存 id を動かさない)。
        CHANNEL.messageBuilder(SeekJukeboxPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SeekJukeboxPayload::write)
                .decoder(SeekJukeboxPayload::read)
                .consumerMainThread((msg, ctx) -> {
                    final ServerPlayer sender = ctx.get().getSender();
                    if (sender != null) {
                        ModNetwork.handleSeekJukebox(msg, sender);
                    }
                })
                .add();

        // server → client: Create contraption に載った音源の再生 (末尾に追加)。ペイロードは Create 非依存
        // なので無条件登録 (id 列を両側一致させる)。受信ハンドラ内の Create 参照は invoke 時のみ class-load
        // (Create 未導入なら server が送信しないので到達しない)。
        CHANNEL.messageBuilder(ContraptionPlayDiscPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ContraptionPlayDiscPayload::write)
                .decoder(ContraptionPlayDiscPayload::read)
                .consumerMainThread((msg, ctx) ->
                        com.kuronami.musicdiscmaker.compat.create.CreateAudioClient.play(msg))
                .add();

        // server → client: ブームボックスの再生 / keep-alive (末尾に追加して既存 id を動かさない)。
        // 1 秒ごとに撃たれるので、client 側が受け取れないと打刻切れで自己停止する。
        CHANNEL.messageBuilder(BoomboxPlayPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BoomboxPlayPayload::write)
                .decoder(BoomboxPlayPayload::read)
                .consumerMainThread((msg, ctx) -> ModNetwork.handleBoomboxPlay(msg))
                .add();

        // server → client: ブームボックスの即時停止 (末尾に追加)。
        CHANNEL.messageBuilder(BoomboxStopPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BoomboxStopPayload::write)
                .decoder(BoomboxStopPayload::read)
                .consumerMainThread((msg, ctx) -> ModNetwork.handleBoomboxStop(msg))
                .add();

        // 既存channel IDを動かさず末尾へ追加する。
        CHANNEL.messageBuilder(NavigateJukeboxPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(NavigateJukeboxPayload::write)
                .decoder(NavigateJukeboxPayload::read)
                .consumerMainThread((msg, ctx) -> {
                    final ServerPlayer sender = ctx.get().getSender();
                    if (sender != null) {
                        ModNetwork.handleNavigateJukebox(msg, sender);
                    }
                })
                .add();

        CHANNEL.messageBuilder(ShuffleJukeboxPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ShuffleJukeboxPayload::write)
                .decoder(ShuffleJukeboxPayload::read)
                .consumerMainThread((msg, ctx) -> {
                    final ServerPlayer sender = ctx.get().getSender();
                    if (sender != null) {
                        ModNetwork.handleShuffleJukebox(msg, sender);
                    }
                })
                .add();

        CHANNEL.messageBuilder(ControlBoomboxPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ControlBoomboxPayload::write)
                .decoder(ControlBoomboxPayload::read)
                .consumerMainThread((msg, ctx) -> {
                    final ServerPlayer sender = ctx.get().getSender();
                    if (sender != null) {
                        ModNetwork.handleControlBoombox(msg, sender);
                    }
                })
                .add();

        CHANNEL.messageBuilder(BoomboxStatePayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BoomboxStatePayload::write)
                .decoder(BoomboxStatePayload::read)
                .consumerMainThread((msg, ctx) -> ModNetwork.handleBoomboxState(msg))
                .add();
        CHANNEL.messageBuilder(SpeakerSetPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SpeakerSetPayload::write)
                .decoder(SpeakerSetPayload::read)
                .consumerMainThread((msg, ctx) -> ModNetwork.handleSpeakerSet(msg))
                .add();
        CHANNEL.messageBuilder(PlayVanillaDiscPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PlayVanillaDiscPayload::write)
                .decoder(PlayVanillaDiscPayload::read)
                .consumerMainThread((msg, ctx) -> ModNetwork.handleVanillaPlay(msg))
                .add();
    }
}
