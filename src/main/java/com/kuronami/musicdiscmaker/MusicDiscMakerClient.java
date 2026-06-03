package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.client.MusicDiscMakerScreen;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.TrackKey;
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
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = MusicDiscMaker.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = MusicDiscMaker.MODID, value = Dist.CLIENT)
public class MusicDiscMakerClient {
    public MusicDiscMakerClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MUSIC_DISC_MAKER.get(), MusicDiscMakerScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
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
}
