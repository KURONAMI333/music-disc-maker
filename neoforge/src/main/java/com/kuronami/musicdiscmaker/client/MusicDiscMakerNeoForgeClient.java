package com.kuronami.musicdiscmaker.client;

//? if >=1.21.2 {
//?} else {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.BoomboxClientPlayback;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackManager;
import com.kuronami.musicdiscmaker.client.jacket.JacketClientTooltip;
import com.kuronami.musicdiscmaker.client.jacket.JacketTooltip;
import com.kuronami.musicdiscmaker.client.render.BoomboxBlockRenderer;
import com.kuronami.musicdiscmaker.client.render.DiscPedestalRenderer;
import com.kuronami.musicdiscmaker.client.render.SpeakerBlockRenderer;
import com.kuronami.musicdiscmaker.client.render.SpeakerLinkOutlineRenderer;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.TrackKey;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/^*
 * NeoForge client setup。dedicated server ではロードされないので client 専用コードを安全に参照できる。
 * 音声ストリーミングは common の Mixin が担うので、ここでは getStream / sound-engine 系を一切扱わない。
 ^/
@Mod(value = MusicDiscMaker.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MusicDiscMaker.MODID, value = Dist.CLIENT)
public class MusicDiscMakerNeoForgeClient {

    public MusicDiscMakerNeoForgeClient(ModContainer container) {
        // Mods 画面 > このmod > config から開ける config 画面を登録する。
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MUSIC_DISC_MAKER.get(), MusicDiscMakerScreen::new);
        event.register(ModMenus.GOLDEN_JUKEBOX.get(), GoldenJukeboxScreen::new);
        event.register(ModMenus.DISC_DYEING_TABLE.get(), DiscDyeingTableScreen::new);
        event.register(ModMenus.BOOMBOX.get(), BoomboxScreen::new);
        event.register(ModMenus.ALBUM.get(), AlbumScreen::new);
        event.register(ModMenus.SPEAKER.get(), SpeakerScreen::new);
    }

    @SubscribeEvent
    static void onRegisterTooltipFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(JacketTooltip.class, JacketClientTooltip::new);
    }

    /^*
     * 設置されたブームボックスの取っ手を描く BER。<b>ここを登録し忘れると取っ手が消える</b>
     * (ブロックの模型は取っ手を持たない) が、例外もログも出ない。
     ^/
    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.BOOMBOX.get(), BoomboxBlockRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.DISC_PEDESTAL.get(), DiscPedestalRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.SPEAKER.get(), SpeakerBlockRenderer::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // サーバ離脱時に全再生を止める game-bus listener を登録する。
        NeoForge.EVENT_BUS.addListener(MusicDiscMakerNeoForgeClient::onLoggingOut);
        // 1.21.1 は ClientGameEvents の対象外なので、この帯自身で選択中の金ジュークを描く。
        NeoForge.EVENT_BUS.addListener(MusicDiscMakerNeoForgeClient::onRenderLevel);

        event.enqueueWork(() -> {
            if (com.kuronami.musicdiscmaker.compat.aeronautics.SableCompat.isLoaded()) {
                ClientPlaybackManager.setShipAnchorResolver(
                        (level, pos) -> new com.kuronami.musicdiscmaker.compat.aeronautics.SableSourceAnchor(pos));
            }
            // custom disc の texture variant を曲名+アーティストから決定的に選ぶ。
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

            // 染色されたディスクを tint モデルへ振り分ける。model の overrides は末尾の枝が
            // 最優先なので、dyed >= 1.0 の枝が 12 色の焼き込みより先に当たる。
            ItemProperties.register(
                    ModItems.CUSTOM_MUSIC_DISC.get(),
                    ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "dyed"),
                    (stack, level, entity, seed) -> CustomMusicDiscItem.isDyed(stack) ? 1.0F : 0.0F);
        });
    }

    /^*
     * 染色ディスクの tint。<b>登録し忘れると色が出ないだけで、例外もログも出ない</b>
     * (未登録の tintindex は白として扱われ、未染色と同じ見た目になる)。
     ^/
    @SubscribeEvent
    static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        event.register(DiscDyeTint::color, ModItems.CUSTOM_MUSIC_DISC.get());
        event.register(AlbumDyeTint::color, ModItems.ALBUM.get());
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientPlaybackManager.get().stopAll();
        if (com.kuronami.musicdiscmaker.platform.Services.PLATFORM.isModLoaded("create")) {
            com.kuronami.musicdiscmaker.compat.create.CreateAudioClient.stopAll();
        }
        if (com.kuronami.musicdiscmaker.compat.aeronautics.SableCompat.isLoaded()) {
            com.kuronami.musicdiscmaker.compat.aeronautics.SableAudioClient.stopAll();
        }
    }

    /^*
     * リンクを設定済みのスピーカーを手に持つ間、リンク先の金ジュークを太い全辺で囲む。
     * AFTER_TRANSLUCENT_BLOCKS は通常の depth buffer を使うため、壁越しには見えない。
     ^/
    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            SpeakerLinkOutlineRenderer.renderLegacy(event.getPoseStack());
        }
    }

    /^*
     * B5 (混在アルバムの先読み): MDM 再生が無い区間 (vanilla / 他 MOD ディスクの再生中) でも
     * 先読み判定を回すための client tick 源。先読みを持たない帯では口が no-op になる。
     ^/
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        ClientPlaybackManager.mixedAlbumTick();
    }
}

*///?}
