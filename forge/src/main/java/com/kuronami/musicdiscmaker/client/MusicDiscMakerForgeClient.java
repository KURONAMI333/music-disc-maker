package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackManager;
import com.kuronami.musicdiscmaker.client.jacket.JacketClientTooltip;
import com.kuronami.musicdiscmaker.client.jacket.JacketTooltip;
import com.kuronami.musicdiscmaker.component.TrackKey;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.minecraftforge.common.MinecraftForge;
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
            MenuScreens.register(ModMenus.ENHANCED_JUKEBOX.get(), EnhancedJukeboxScreen::new);

            // custom disc の texture variant を曲名+アーティストから決定的に選ぶ。
            // 返り値 (index+0.5)/VARIANTS が model override の threshold (i/VARIANTS) に対応。
            ItemProperties.register(
                    ModItems.CUSTOM_MUSIC_DISC.get(),
                    new ResourceLocation(MusicDiscMaker.MODID, "variant"),
                    (stack, level, entity, seed) ->
                            (TrackKey.variantIndex(CustomMusicDiscItem.getTrack(stack)) + 0.5F) / TrackKey.VARIANTS);
        });

        // サーバ離脱時に全再生を止める game-bus listener を登録する。
        MinecraftForge.EVENT_BUS.addListener(MusicDiscMakerForgeClient::onLoggingOut);
    }

    /** ツールチップのジャケット画像: JacketTooltip → JacketClientTooltip に変換する factory を登録。 */
    @SubscribeEvent
    public static void onRegisterTooltipFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(JacketTooltip.class, JacketClientTooltip::new);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientPlaybackManager.get().stopAll();
    }
}
