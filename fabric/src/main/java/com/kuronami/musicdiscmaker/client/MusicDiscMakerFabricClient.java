package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.BoomboxClientPlayback;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackManager;
import com.kuronami.musicdiscmaker.client.jacket.JacketClientTooltip;
import com.kuronami.musicdiscmaker.client.jacket.JacketTooltip;
import com.kuronami.musicdiscmaker.client.render.BoomboxBlockRenderer;
import com.kuronami.musicdiscmaker.client.render.DiscPedestalRenderer;
import com.kuronami.musicdiscmaker.client.render.SpeakerBlockRenderer;
import com.kuronami.musicdiscmaker.client.render.SpeakerLinkOutlineRenderer;
//? if >=1.21.2 {
//?} elif >=1.21 {
/*import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.TrackKey;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
*/
//?} else {
/*import com.kuronami.musicdiscmaker.component.TrackKey;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
*/
//?}
import com.kuronami.musicdiscmaker.network.ModNetwork;
//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.network.ModPayloadTypes;
//?} elif >=1.21 {
/*import com.kuronami.musicdiscmaker.network.ModPayloadTypes;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
*/
//?} else {
/*import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.register.ModItems;
*/
//?}
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
//? if >=26.2 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?} elif >=1.21.11 {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
*/
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
*///?}
//? if <1.21.2 {
/*import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
*///?}
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
*/
//?}
import net.minecraft.client.gui.screens.MenuScreens;
//? if >=1.21.2 {
import net.minecraft.client.color.item.ItemTintSources;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties;
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
*/
//?}

/**
 * Fabric client setup。dedicated server ではロードされないので client 専用コードを安全に参照できる。
 * 音声ストリーミングは client mixin ({@code MixinDiscSoundInstance}) が担うので、ここでは
 * getStream / sound-engine 系を一切扱わない。
 */
public class MusicDiscMakerFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        //? if >=26.1 {
        // menu screen 登録。26.2 は vanilla MenuScreens.register が private だが fabric-api の
        // transitive access widener で mod から呼べる (旧 fabric-screen-handler-api の HandledScreens 相当)。
        MenuScreens.register(ModMenus.MUSIC_DISC_MAKER.get(), MusicDiscMakerScreen::new);
        //?} elif >=1.21.2 {
        /*        // menu screen 登録。vanilla MenuScreens.register は private だが fabric-api の
        // transitive access widener で mod から呼べる。
        MenuScreens.register(ModMenus.MUSIC_DISC_MAKER.get(), MusicDiscMakerScreen::new);
        */
        //?} else {
        /*        MenuScreens.register(ModMenus.MUSIC_DISC_MAKER.get(), MusicDiscMakerScreen::new);
        */
        //?}
        MenuScreens.register(ModMenus.GOLDEN_JUKEBOX.get(), GoldenJukeboxScreen::new);
        MenuScreens.register(ModMenus.DISC_DYEING_TABLE.get(), DiscDyeingTableScreen::new);
        MenuScreens.register(ModMenus.BOOMBOX.get(), BoomboxScreen::new);
        MenuScreens.register(ModMenus.ALBUM.get(), AlbumScreen::new);
        MenuScreens.register(ModMenus.SPEAKER.get(), SpeakerScreen::new);

        // 設置されたブームボックスの取っ手を描く BER。登録し忘れると取っ手が消えるが、
        // ブロックの模型は取っ手を持たないので例外もログも出ない。
        BlockEntityRendererRegistry.register(ModBlockEntities.BOOMBOX.get(), BoomboxBlockRenderer::new);
        BlockEntityRendererRegistry.register(ModBlockEntities.DISC_PEDESTAL.get(), DiscPedestalRenderer::new);
        BlockEntityRendererRegistry.register(ModBlockEntities.SPEAKER.get(), SpeakerBlockRenderer::new);
        //? if >=26.2 {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> SpeakerLinkOutlineRenderer.submitModern(
                context.poseStack(), context.submitNodeCollector(), context.levelState().cameraRenderState.pos));
        //?} elif >=1.21.11 {
        /*WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> SpeakerLinkOutlineRenderer.renderIntermediate(
                context.matrices(), context.consumers().getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads()),
                context.worldState().cameraRenderState.pos));
        */
        //?} else {
        /*WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> SpeakerLinkOutlineRenderer.renderLegacy(new com.mojang.blaze3d.vertex.PoseStack()));
        *///?}


        //? if >=26.1 {
        // ツールチップのジャケット画像: JacketTooltip → JacketClientTooltip に変換する。
        // 26.2 fabric-rendering: TooltipComponentCallback は ClientTooltipComponentCallback に rename
        // (メソッドは getClientComponent(TooltipComponent)→ClientTooltipComponent)。
        ClientTooltipComponentCallback.EVENT.register(
        //?} elif >=1.21.2 {
        /*        // ツールチップのジャケット画像: JacketTooltip → JacketClientTooltip に変換する。
        // fabric-rendering の TooltipComponentCallback#getComponent で TooltipComponent を
        // ClientTooltipComponent に変換する。
        TooltipComponentCallback.EVENT.register(
        */
        //?} else {
        /*        // ツールチップのジャケット画像: JacketTooltip → JacketClientTooltip に変換する。
        TooltipComponentCallback.EVENT.register(
        */
        //?}
                data -> data instanceof JacketTooltip jt ? new JacketClientTooltip(jt) : null);

        //? if >=1.21.2 {
        // custom disc の texture variant (12 色) を曲名+アーティストから決定的に選ぶ range_dispatch プロパティ。
        // 26.1: 旧 ItemProperties は廃止。items モデル JSON の range_dispatch + カスタムプロパティで表現する。
        // NeoForge は RegisterRangeSelectItemModelPropertyEvent、Fabric は vanilla の LateBoundIdMapper
        // (RangeSelectItemModelProperties.ID_MAPPER・fabric-api の transitive access widener で可視) へ直接 put する。
        // client init は資源リロード (item model パース) より前に走るので put のタイミングは安全。
        RangeSelectItemModelProperties.ID_MAPPER.put(
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "variant"),
                VariantItemModelProperty.MAP_CODEC);
        // ブームボックスの ON (肩に担ぐ・取っ手が倒れる) / OFF (提げる・取っ手が立つ) の出し分け。
        RangeSelectItemModelProperties.ID_MAPPER.put(
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "playing"),
                BoomboxPlayingItemModelProperty.MAP_CODEC);
        // 染色ディスクの tint 源。items モデル JSON の tints がこの id を引く。
        // 登録し忘れると色が出ないだけで、例外もログも出ない (未登録の tintindex は白扱い)。
        ItemTintSources.ID_MAPPER.put(
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "disc_dye"),
                DiscDyeTintSource.MAP_CODEC);
        ItemTintSources.ID_MAPPER.put(
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "album_dye"),
                AlbumDyeTintSource.MAP_CODEC);
        //?} elif >=1.21 {
        /*        // custom disc の texture variant を曲名+アーティストから決定的に選ぶ。
        // 返り値 (index+0.5)/VARIANTS が model override の threshold (i/VARIANTS) に対応。
        ItemProperties.register(
                ModItems.CUSTOM_MUSIC_DISC.get(),
                ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "variant"),
                (stack, level, entity, seed) -> {
                    final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
                    return (TrackKey.variantIndex(track) + 0.5F) / TrackKey.VARIANTS;
                });
        // ブームボックスの ON (肩に担ぐ・取っ手が倒れる) / OFF (提げる・取っ手が立つ) の出し分け。
        ItemProperties.register(
                ModItems.BOOMBOX.get(),
                ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "playing"),
                (stack, level, entity, seed) -> BoomboxClientPlayback.isPlaying(stack) ? 1.0F : 0.0F);
        // 染色されたディスクを tint モデルへ振り分ける。model の overrides は末尾の枝が最優先。
        ItemProperties.register(
                ModItems.CUSTOM_MUSIC_DISC.get(),
                ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "dyed"),
                (stack, level, entity, seed) -> CustomMusicDiscItem.isDyed(stack) ? 1.0F : 0.0F);
        // 染色ディスクの tint。登録し忘れると色が出ないだけで、例外もログも出ない。
        ColorProviderRegistry.ITEM.register(DiscDyeTint::color, ModItems.CUSTOM_MUSIC_DISC.get());
        ColorProviderRegistry.ITEM.register(AlbumDyeTint::color, ModItems.ALBUM.get());
        */
        //?} else {
        /*        // custom disc の texture variant を曲名+アーティストから決定的に選ぶ。
        // 返り値 (index+0.5)/VARIANTS が model override の threshold (i/VARIANTS) に対応。
        ItemProperties.register(
                ModItems.CUSTOM_MUSIC_DISC.get(),
                new ResourceLocation(MusicDiscMaker.MODID, "variant"),
                (stack, level, entity, seed) ->
                        (TrackKey.variantIndex(CustomMusicDiscItem.getTrack(stack)) + 0.5F) / TrackKey.VARIANTS);
        // ブームボックスの ON (肩に担ぐ・取っ手が倒れる) / OFF (提げる・取っ手が立つ) の出し分け。
        ItemProperties.register(
                ModItems.BOOMBOX.get(),
                new ResourceLocation(MusicDiscMaker.MODID, "playing"),
                (stack, level, entity, seed) -> BoomboxClientPlayback.isPlaying(stack) ? 1.0F : 0.0F);
        // 染色されたディスクを tint モデルへ振り分ける。model の overrides は末尾の枝が最優先。
        ItemProperties.register(
                ModItems.CUSTOM_MUSIC_DISC.get(),
                new ResourceLocation(MusicDiscMaker.MODID, "dyed"),
                (stack, level, entity, seed) -> CustomMusicDiscItem.isDyed(stack) ? 1.0F : 0.0F);
        // 染色ディスクの tint。登録し忘れると色が出ないだけで、例外もログも出ない。
        ColorProviderRegistry.ITEM.register(DiscDyeTint::color, ModItems.CUSTOM_MUSIC_DISC.get());
        ColorProviderRegistry.ITEM.register(AlbumDyeTint::color, ModItems.ALBUM.get());
        */
        //?}

        //? if >=1.21.2 {
        // client 受信: 再生制御 (main thread へ enqueue)。
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.Play.TYPE,
                (msg, ctx) -> ctx.client().execute(() -> ModNetwork.handlePlay(msg.inner())));
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.PlayVanilla.TYPE,
                (msg, ctx) -> ctx.client().execute(() -> ModNetwork.handleVanillaPlay(msg.inner())));
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.Stop.TYPE,
                (msg, ctx) -> ctx.client().execute(() -> ModNetwork.handleStop(msg.inner())));
        // B5/C12: play 段ハンドシェイク。サーバーの wire 版を受け取り不一致なら警告+無効化。
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.Version.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleClientVersion(payload.inner())));
        // client 受信: 携帯ブームボックス (1 秒ごとの keep-alive を兼ねる)。
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.BoomboxPlay.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleBoomboxPlay(payload.inner())));
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.BoomboxStop.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleBoomboxStop(payload.inner())));
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.BoomboxState.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleBoomboxState(payload.inner())));
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.SpeakerSet.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleSpeakerSet(payload.inner())));
        //?} elif >=1.21 {
        /*        // client 受信: 再生制御 (main thread へ enqueue)。
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.Play.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handlePlay(payload.inner())));
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.PlayVanilla.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleVanillaPlay(payload.inner())));
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.Stop.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleStop(payload.inner())));
        // B5/C12: play 段ハンドシェイク。サーバーの wire 版を受け取り不一致なら警告+無効化。
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.Version.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleClientVersion(payload.inner())));
        // client 受信: 携帯ブームボックス (1 秒ごとの keep-alive を兼ねる)。
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.BoomboxPlay.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleBoomboxPlay(payload.inner())));
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.BoomboxStop.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleBoomboxStop(payload.inner())));
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.BoomboxState.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleBoomboxState(payload.inner())));
        ClientPlayNetworking.registerGlobalReceiver(ModPayloadTypes.SpeakerSet.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleSpeakerSet(payload.inner())));
        // SB Fabric port の backpack jukebox 再生受信 (port 非依存)。
        com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat.registerClientReceiver();
        */
        //?} else {
        /*        // client 受信: 再生制御 (netty thread で buf を読み、main thread へ enqueue)。
        ClientPlayNetworking.registerGlobalReceiver(com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(PlayDiscPayload.PATH),
                (client, handler, buf, responseSender) -> {
                    final PlayDiscPayload payload = PlayDiscPayload.read(buf);
                    client.execute(() -> ModNetwork.handlePlay(payload));
                });
        ClientPlayNetworking.registerGlobalReceiver(com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload.PATH),
                (client, handler, buf, responseSender) -> {
                    final com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload payload = com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload.read(buf);
                    client.execute(() -> ModNetwork.handleVanillaPlay(payload));
                });
        ClientPlayNetworking.registerGlobalReceiver(com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(StopDiscPayload.PATH),
                (client, handler, buf, responseSender) -> {
                    final StopDiscPayload payload = StopDiscPayload.read(buf);
                    client.execute(() -> ModNetwork.handleStop(payload));
                });

        // B5/C12: play 段ハンドシェイク。サーバーの wire 版を受け取り不一致なら警告+無効化。
        ClientPlayNetworking.registerGlobalReceiver(
                com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(
                        com.kuronami.musicdiscmaker.network.VersionPayload.PATH),
                (client, handler, buf, responseSender) -> {
                    final com.kuronami.musicdiscmaker.network.VersionPayload payload =
                            com.kuronami.musicdiscmaker.network.VersionPayload.read(buf);
                    client.execute(() -> ModNetwork.handleClientVersion(payload));
                });

        // client 受信: Sophisticated Backpacks (非公式 Fabric port) の backpack 内 jukebox 再生。
        // server 側は JukeboxUpgradeWrapperMixin が送信する (port 非導入なら never-load)。
        ClientPlayNetworking.registerGlobalReceiver(
                com.kuronami.musicdiscmaker.compat.sophisticatedcore.BackpackPlayDiscPayload.ID,
                (client, handler, buf, responseSender) -> {
                    final var payload =
                            com.kuronami.musicdiscmaker.compat.sophisticatedcore.BackpackPlayDiscPayload.read(buf);
                    client.execute(() ->
                            com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompatClient.play(payload));
                });

        // client 受信: 携帯ブームボックス (1 秒ごとの keep-alive を兼ねる)。
        ClientPlayNetworking.registerGlobalReceiver(
                com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(
                        com.kuronami.musicdiscmaker.network.BoomboxPlayPayload.PATH),
                (client, handler, buf, responseSender) -> {
                    final com.kuronami.musicdiscmaker.network.BoomboxPlayPayload payload =
                            com.kuronami.musicdiscmaker.network.BoomboxPlayPayload.read(buf);
                    client.execute(() -> ModNetwork.handleBoomboxPlay(payload));
                });
        ClientPlayNetworking.registerGlobalReceiver(
                com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(
                        com.kuronami.musicdiscmaker.network.BoomboxStopPayload.PATH),
                (client, handler, buf, responseSender) -> {
                    final com.kuronami.musicdiscmaker.network.BoomboxStopPayload payload =
                            com.kuronami.musicdiscmaker.network.BoomboxStopPayload.read(buf);
                    client.execute(() -> ModNetwork.handleBoomboxStop(payload));
                });
        ClientPlayNetworking.registerGlobalReceiver(
                com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(
                        com.kuronami.musicdiscmaker.network.BoomboxStatePayload.PATH),
                (client, handler, buf, responseSender) -> {
                    final com.kuronami.musicdiscmaker.network.BoomboxStatePayload payload =
                            com.kuronami.musicdiscmaker.network.BoomboxStatePayload.read(buf);
                    client.execute(() -> ModNetwork.handleBoomboxState(payload));
                });
        ClientPlayNetworking.registerGlobalReceiver(
                com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(
                        com.kuronami.musicdiscmaker.network.SpeakerSetPayload.PATH),
                (client, handler, buf, responseSender) -> {
                    final com.kuronami.musicdiscmaker.network.SpeakerSetPayload payload =
                            com.kuronami.musicdiscmaker.network.SpeakerSetPayload.read(buf);
                    client.execute(() -> ModNetwork.handleSpeakerSet(payload));
                });
        */
        //?}

        //? if <1.21 {
        /*        // VS2 (物理船) に載った jukebox の音源座標を compat に決めさせる。VS2Compat を持つのは
        // 1.20.1 の common オーバーレイだけなので、差し込みもその帯だけ。ラムダなので実装側の
        // クラス名・メソッド名・引数の型をコンパイラが見る (名前を変えればここが落ちる)。
        ClientPlaybackManager.setShipAnchorResolver(
                (level, pos) -> com.kuronami.musicdiscmaker.compat.valkyrienskies.VS2Compat
                        .resolveShipAnchor(level, pos));
        */
        //?}

        // サーバ離脱時に全再生を止める。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientPlaybackManager.get().stopAll());

        // B5 (混在アルバムの先読み): MDM 再生が無い区間 (vanilla / 他 MOD ディスクの再生中) でも
        // 先読み判定を回すための client tick 源。先読みを持たない帯では口が no-op になる。
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientPlaybackManager.mixedAlbumTick());
    }
}


