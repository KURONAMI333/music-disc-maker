package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.client.MusicDiscMakerScreen;
import com.kuronami.musicdiscmaker.client.VariantItemModelProperty;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = MusicDiscMaker.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = MusicDiscMaker.MODID, value = Dist.CLIENT)
public class MusicDiscMakerClient {
    public MusicDiscMakerClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MUSIC_DISC_MAKER.get(), MusicDiscMakerScreen::new);
    }

    /**
     * custom disc の texture variant (12 色) を曲名+アーティストから決定的に選ぶ range_dispatch プロパティを登録する。
     * 26.1: 旧 {@code ItemProperties} は廃止され、items モデル JSON の range_dispatch +
     * カスタムプロパティ ({@link VariantItemModelProperty}) で表現する。
     */
    @SubscribeEvent
    static void onRegisterRangeProperties(RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "variant"),
                VariantItemModelProperty.MAP_CODEC);
    }
}
