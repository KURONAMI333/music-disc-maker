package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.network.NeoForgePayloads;
import com.kuronami.musicdiscmaker.platform.NeoForgeConfigHelper;
import com.kuronami.musicdiscmaker.platform.registry.NeoForgeRegistrationProvider;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModRegistries;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * NeoForge entry。共通ホルダ ({@code Mod*}) の static 初期化で生成された {@link NeoForgeRegistrationProvider}
 * 群を mod event bus へ bind し、payload・capability・config を登録する。
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
        // Sophisticated Core 互換: ロードされていれば custom disc を SB の Jukebox Upgrade で使える IDiscHandler を登録。
        modEventBus.addListener(com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat::onCommonSetup);
        // Create 互換: ロードされていれば強化版ジュークボックスに MovementBehaviour を登録し、捕獲式 contraption 上での再生継続を有効にする。
        modEventBus.addListener(com.kuronami.musicdiscmaker.compat.create.CreateCompat::onCommonSetup);
        // Create Aeronautics (Sable) 互換: ロードされていれば物理 sub-level に載った強化版ジュークボックスの再生追従を有効にする。
        modEventBus.addListener(com.kuronami.musicdiscmaker.compat.aeronautics.SableCompat::onCommonSetup);
        // Additional Additions 互換: ロードされていればアルバムを強化版ジュークボックスで再生できるようにする。
        modEventBus.addListener(com.kuronami.musicdiscmaker.compat.additionaladditions.AlbumCompat::onCommonSetup);

        modContainer.registerConfig(ModConfig.Type.CLIENT, NeoForgeConfigHelper.SPEC);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // hopper 等から空ディスク投入 / 出力取り出しを可能にする。
        // BlockEntity は vanilla Container なので InvWrapper で IItemHandler に変換する。
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.MUSIC_DISC_MAKER.get(),
                (be, side) -> new InvWrapper(be));
        // 強化版ジュークボックス: hopper でディスクの投入/取り出しを可能にする。
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.GOLDEN_JUKEBOX.get(),
                (be, side) -> new InvWrapper(be));
    }
}
