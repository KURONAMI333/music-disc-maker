package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.network.NeoForgePayloads;
import com.kuronami.musicdiscmaker.platform.NeoForgeConfigHelper;
import com.kuronami.musicdiscmaker.platform.registry.NeoForgeRegistrationProvider;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModCreativeTab;
import com.kuronami.musicdiscmaker.register.ModRegistries;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;

/**
 * NeoForge entry。共通ホルダ ({@code Mod*}) の static 初期化で生成された {@link NeoForgeRegistrationProvider}
 * 群を mod event bus へ bind し、payload・capability・config・creative タブ内容を登録する。
 */
@Mod(MusicDiscMaker.MODID)
public class MusicDiscMakerNeoForge {

    public MusicDiscMakerNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        // common ホルダの static 初期化を強制 → 各 DeferredRegister (NeoForgeRegistrationProvider) を生成。
        ModRegistries.init();
        // 生成された全 DeferredRegister を mod event bus へ bind する。
        NeoForgeRegistrationProvider.registerAll(modEventBus);

        modEventBus.addListener(NeoForgePayloads::register);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::buildCreativeTabContents);
        // Sophisticated Core 互換: ロードされていれば custom disc を SB の Jukebox Upgrade で使える IDiscHandler を登録。
        modEventBus.addListener(com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat::onCommonSetup);

        modContainer.registerConfig(ModConfig.Type.CLIENT, NeoForgeConfigHelper.SPEC);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // hopper 等から空ディスク投入 / 出力取り出しを可能にする。
        // BE は vanilla Container を実装し、VanillaContainerWrapper で ResourceHandler<ItemResource> に橋渡しする。
        event.registerBlockEntity(Capabilities.Item.BLOCK, ModBlockEntities.MUSIC_DISC_MAKER.get(),
                (be, side) -> VanillaContainerWrapper.of(be));
    }

    private void buildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        // common は tab の identity のみ作る。中身はここで表示順に流し込む。
        if (event.getTab() == ModCreativeTab.MAIN.get()) {
            ModCreativeTab.TAB_ITEMS.forEach(holder -> event.accept(holder.get()));
        }
    }
}
