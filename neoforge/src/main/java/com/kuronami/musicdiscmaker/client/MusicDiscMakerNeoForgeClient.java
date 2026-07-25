package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackManager;
import com.kuronami.musicdiscmaker.client.jacket.JacketClientTooltip;
import com.kuronami.musicdiscmaker.client.jacket.JacketTooltip;
import com.kuronami.musicdiscmaker.client.render.SpeakerLinkOverlay;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.TrackKey;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge client setup。dedicated server ではロードされないので client 専用コードを安全に参照できる。
 * 音声ストリーミングは common の Mixin が担うので、ここでは getStream / sound-engine 系を一切扱わない。
 */
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
        event.register(ModMenus.SPEAKER.get(), SpeakerScreen::new);
    }

    @SubscribeEvent
    static void onRegisterTooltipFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(JacketTooltip.class, JacketClientTooltip::new);
    }

    /**
     * スピーカーのリンク先を輪郭で示す (スピーカーのブロックアイテムを持っている間だけ)。
     *
     * <p>段階は Fabric 側と揃えて {@code AFTER_ENTITIES}（あちらは行 buffer が使える段階が
     * ここまでで、{@code AFTER_TRANSLUCENT} 以降は「フレームバッファへ直接描く」段階になる）。
     * 行 buffer はここで流す (level renderer 側の flush を当てにしない)。
     */
    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        final MultiBufferSource.BufferSource buffers =
                net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource();
        SpeakerLinkOverlay.render(event.getPoseStack(), buffers, event.getCamera().getPosition());
        buffers.endBatch(RenderType.lines());
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // サーバ離脱時に全再生を止める game-bus listener を登録する。
        NeoForge.EVENT_BUS.addListener(MusicDiscMakerNeoForgeClient::onLoggingOut);

        event.enqueueWork(() -> {
            // custom disc の texture variant を曲名+アーティストから決定的に選ぶ。
            // 返り値 (index+0.5)/VARIANTS が model override の threshold (i/VARIANTS) に対応。
            ItemProperties.register(
                    ModItems.CUSTOM_MUSIC_DISC.get(),
                    ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "variant"),
                    (stack, level, entity, seed) -> {
                        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
                        return (TrackKey.variantIndex(track) + 0.5F) / TrackKey.VARIANTS;
                    });
        });
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientPlaybackManager.get().stopAll();
    }
}
