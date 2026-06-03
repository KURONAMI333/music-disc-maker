package com.kuronami.musicdiscmaker;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.kuronami.musicdiscmaker.depend.DependencyManager;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModCreativeTab;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;
import com.kuronami.musicdiscmaker.register.ModSounds;
import com.kuronami.musicdiscmaker.network.ModNetwork;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@Mod(MusicDiscMaker.MODID)
public class MusicDiscMaker {

    public static final String MODID = "music_disc_maker";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MusicDiscMaker(IEventBus modEventBus, ModContainer modContainer) {
        ModDataComponents.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModMenus.register(modEventBus);
        ModSounds.register(modEventBus);
        ModCreativeTab.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(ModNetwork::register);

        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Gate 検証: LavaPlayer 隔離ロードが NeoForge runtime で動くかを確認する。
        // -Dmusicdiscmaker.selftest=true の時だけ走る (gameTestServer run config で有効)。
        if (Boolean.getBoolean("musicdiscmaker.selftest")) {
            runLavaPlayerSelfTest();
        }
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // hopper 等から空ディスク投入 / 出力取り出しを可能にする
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.MUSIC_DISC_MAKER.get(),
                (be, side) -> be.getInventory());
    }

    // ── Gate 1/2 検証用の一時 self-test。本実装の URL 解決が固まったら削除する。 ──
    private void runLavaPlayerSelfTest() {
        LOGGER.info("[GATE] LavaPlayer 隔離ロード self-test 開始");
        try {
            DependencyManager.load();
            final Class<?> clazz = Class.forName(
                    "com.kuronami.musicdiscmaker.lavaplayer.MusicLoaderImpl", true, DependencyManager.CLASSLOADER);
            if (!IMusicLoader.class.isAssignableFrom(clazz)) {
                LOGGER.error("[GATE] FAIL: impl が IMusicLoader を実装していない: {}", clazz);
                return;
            }
            final IMusicLoader loader = (IMusicLoader) clazz.getDeclaredConstructor().newInstance();
            LOGGER.info("[GATE] OK: 隔離 classloader から impl 生成成功: {}", clazz.getName());

            final String testUrl = System.getProperty(
                    "musicdiscmaker.testUrl", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
            final TrackInfo info = loader.resolve(testUrl);
            if (info != null) {
                LOGGER.info("[GATE] OK: 解決成功 title='{}' author='{}' dur={}ms", info.title(), info.author(), info.durationMs());
            } else {
                LOGGER.warn("[GATE] 解決は null (service/ネット要因の可能性)");
            }
        } catch (final Throwable t) {
            LOGGER.error("[GATE] FAIL: self-test で例外", t);
        }
        LOGGER.info("[GATE] self-test 終了");
    }
}
