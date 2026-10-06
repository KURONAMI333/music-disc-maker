package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.BoomboxClientPlayback;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackManager;
import com.kuronami.musicdiscmaker.client.jacket.JacketClientTooltip;
import com.kuronami.musicdiscmaker.client.jacket.JacketTooltip;
import com.kuronami.musicdiscmaker.compat.valkyrienskies.VS2Compat;
import com.kuronami.musicdiscmaker.component.TrackKey;
import com.kuronami.musicdiscmaker.client.render.BoomboxBlockRenderer;
import com.kuronami.musicdiscmaker.client.render.DeviceModelPoses;
import com.kuronami.musicdiscmaker.client.render.DevicePartModels;
import com.kuronami.musicdiscmaker.client.render.DiscPedestalRenderer;
import com.kuronami.musicdiscmaker.client.render.GoldenJukeboxMinecartRenderer;
import com.kuronami.musicdiscmaker.client.render.SpeakerBlockRenderer;
import com.kuronami.musicdiscmaker.client.render.SpeakerLinkOutlineRenderer;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModEntities;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Forge client setup。dedicated server ではロードされないので client 専用コードを安全に参照できる。
 * 音声ストリーミングは {@code MixinSoundEngine} が担うので、ここでは getStream / sound-engine 系を扱わない。
 */
@Mod.EventBusSubscriber(modid = MusicDiscMaker.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MusicDiscMakerForgeClient {

    private MusicDiscMakerForgeClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.MUSIC_DISC_MAKER.get(), MusicDiscMakerScreen::new);
            MenuScreens.register(ModMenus.GOLDEN_JUKEBOX.get(), GoldenJukeboxScreen::new);
            MenuScreens.register(ModMenus.GOLDEN_JUKEBOX_CART.get(), GoldenJukeboxCartScreen::new);
            MenuScreens.register(ModMenus.DISC_DYEING_TABLE.get(), DiscDyeingTableScreen::new);
            MenuScreens.register(ModMenus.BOOMBOX.get(), BoomboxScreen::new);
            MenuScreens.register(ModMenus.ALBUM.get(), AlbumScreen::new);
            MenuScreens.register(ModMenus.SPEAKER.get(), SpeakerScreen::new);

            // custom disc の texture variant を曲名+アーティストから決定的に選ぶ。
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

            // 染色されたディスクを tint モデルへ振り分ける。model の overrides は末尾の枝が
            // 最優先なので、dyed >= 1.0 の枝が 12 色の焼き込みより先に当たる。
            ItemProperties.register(
                    ModItems.CUSTOM_MUSIC_DISC.get(),
                    new ResourceLocation(MusicDiscMaker.MODID, "dyed"),
                    (stack, level, entity, seed) -> CustomMusicDiscItem.isDyed(stack) ? 1.0F : 0.0F);
        });

        // VS2 (物理船) に載った jukebox の音源座標を compat に決めさせる。実装を持つのはこの帯だけで、
        // 差し込まない帯は StaticAnchor 再生のまま動く。ラムダなので実装側の名前をコンパイラが見る。
        ClientPlaybackManager.setShipAnchorResolver((level, pos) -> VS2Compat.resolveShipAnchor(level, pos));

        // サーバ離脱時に全再生を止める game-bus listener を登録する。
        MinecraftForge.EVENT_BUS.addListener(MusicDiscMakerForgeClient::onLoggingOut);
        MinecraftForge.EVENT_BUS.addListener(MusicDiscMakerForgeClient::onRenderLevel);
        // Curios 専用 slot のブームボックスを開く key の押下処理。Curios 未導入では tick() が即 return する。
        MinecraftForge.EVENT_BUS.addListener(MusicDiscMakerForgeClient::onClientTick);
    }

    /**
     * Curios 専用 slot に装備したブームボックスを開く key。Curios 未導入では登録しない
     * (押しても何も起きない dead keybind を操作設定へ並べないため)。
     */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        if (CuriosBoomboxKey.isAvailable()) {
            event.register(CuriosBoomboxKey.mapping());
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ClientPlaybackManager.mixedAlbumTick();
            CuriosBoomboxKey.tick();
        }
    }

    /**
     * 染色ディスクの tint。<b>登録し忘れると色が出ないだけで、例外もログも出ない</b>
     * (未登録の tintindex は白として扱われ、未染色と同じ見た目になる)。
     */
    @SubscribeEvent
    public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        event.getItemColors().register(DiscDyeTint::color, ModItems.CUSTOM_MUSIC_DISC.get());
        event.getItemColors().register(AlbumDyeTint::color, ModItems.ALBUM.get());
    }

    /**
     * 斜めスピーカーのホーンとブームボックスの取っ手を、リソースパックで差替えられる
     * 追加モデルとして登録する。provider は毎フレーム現在の ModelManager に問い合わせるので
     * F3+T (resources reload) で差し替わる — 焼き済みモデルは保持しない。
     */
    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        for (final DevicePartModels.Part part : DevicePartModels.Part.values()) {
            event.register(new ResourceLocation(MusicDiscMaker.MODID, part.path()));
        }
        DevicePartModels.installProvider(part -> {
            final ModelManager manager = Minecraft.getInstance().getModelManager();
            final BakedModel model = manager.getModel(
                    new ResourceLocation(MusicDiscMaker.MODID, part.path()));
            return model == manager.getMissingModel() ? null : model;
        });
    }

    /** device_model_pose.json の client リロード口。パックを外したらデフォルトへ戻る。 */
    @SubscribeEvent
    public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(DeviceModelPoses.INSTANCE);
    }

    /**
     * 設置されたブームボックスの取っ手を描く BER。<b>ここを登録し忘れると取っ手が消える</b>
     * (ブロックの模型は取っ手を持たない) が、例外もログも出ない。
     */
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.BOOMBOX.get(), BoomboxBlockRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.DISC_PEDESTAL.get(), DiscPedestalRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.SPEAKER.get(), SpeakerBlockRenderer::new);
        event.registerEntityRenderer(ModEntities.GOLDEN_JUKEBOX_MINECART.get(), GoldenJukeboxMinecartRenderer::new);
    }

    /** ツールチップのジャケット画像: JacketTooltip → JacketClientTooltip に変換する factory を登録。 */
    @SubscribeEvent
    public static void onRegisterTooltipFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(JacketTooltip.class, JacketClientTooltip::new);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompatClient.stopAll();
        ClientPlaybackManager.get().stopAll();
        if (com.kuronami.musicdiscmaker.platform.Services.PLATFORM.isModLoaded("create")) {
            com.kuronami.musicdiscmaker.compat.create.CreateAudioClient.stopAll();
        }
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            SpeakerLinkOutlineRenderer.renderLegacy(event.getPoseStack());
        }
    }

}
