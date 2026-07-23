package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.network.ForgeNetwork;
import com.kuronami.musicdiscmaker.platform.ForgeConfigHelper;
import com.kuronami.musicdiscmaker.platform.registry.ForgeRegistrationProvider;
import com.kuronami.musicdiscmaker.register.ModRegistries;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge entry。共通ホルダ ({@code Mod*}) の static 初期化で生成された {@link ForgeRegistrationProvider}
 * 群を mod event bus へ bind し、network channel・config を登録する。
 *
 * <p>hopper 連携は common BE が {@code WorldlyContainer} を実装するため capability 登録は不要
 * (vanilla hopper が face 規則を尊重する)。
 */
@Mod(MusicDiscMaker.MODID)
public class MusicDiscMakerForge {

    public MusicDiscMakerForge() {
        final IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // common ホルダの static 初期化を強制 → 各 DeferredRegister (ForgeRegistrationProvider) を生成。
        ModRegistries.init();
        // 生成された全 DeferredRegister を mod event bus へ bind する。
        ForgeRegistrationProvider.registerAll(modEventBus);

        // SimpleChannel に payload を登録する。
        ForgeNetwork.register();

        // Sophisticated Core 互換: ロードされていれば custom disc を SB の Jukebox Upgrade で使える IDiscHandler を登録。
        modEventBus.addListener(this::onCommonSetup);

        // client 設定 (再生音量・同時再生数)。
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ForgeConfigHelper.SPEC);

        MusicDiscMaker.LOGGER.info("Music Disc Maker (Forge) initialized");
    }

    private void onCommonSetup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
        // isLoaded() は SC 非参照で安全。registerHandler() は SC 参照なのでロード時のみ呼ぶ (call site ガード)。
        if (com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat.isLoaded()) {
            event.enqueueWork(com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat::registerHandler);
        }
        // Create 互換: ロードされていれば強化版ジュークボックスへ MovementBehaviour を登録し、捕獲式
        // contraption に載った時の音源追従を有効にする。isLoaded() は Create 非参照で安全、
        // registerMovementBehaviour() は Create 参照なのでロード時のみ呼ぶ (call site ガード)。
        if (com.kuronami.musicdiscmaker.compat.create.CreateCompat.isLoaded()) {
            event.enqueueWork(com.kuronami.musicdiscmaker.compat.create.CreateCompat::registerMovementBehaviour);
        }
    }
}
