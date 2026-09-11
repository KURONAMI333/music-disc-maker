package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.network.NeoForgePayloads;
import com.kuronami.musicdiscmaker.platform.NeoForgeConfigHelper;
import com.kuronami.musicdiscmaker.platform.registry.NeoForgeRegistrationProvider;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.register.ModCreativeTab;
//?} else {
//?}
import com.kuronami.musicdiscmaker.register.ModRegistries;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
//? if >=1.21.2 {
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
//?} else {
/*import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
*///?}

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
        //? if >=1.21.2 {
        modEventBus.addListener(this::buildCreativeTabContents);
        modEventBus.addListener(com.kuronami.musicdiscmaker.gametest.MdmGameTestRegistration::register);
        //?} else {
        //?}
        // Sophisticated Core 互換: ロードされていれば custom disc を SB の Jukebox Upgrade で使える IDiscHandler を登録。
        modEventBus.addListener(com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat::onCommonSetup);
        //? if >=1.21.2 {
        //?} else {
        /*// Create 互換: ロードされていれば強化版ジュークボックスに MovementBehaviour を登録し、捕獲式 contraption 上での再生継続を有効にする。
        modEventBus.addListener(com.kuronami.musicdiscmaker.compat.create.CreateCompat::onCommonSetup);
        // Create Aeronautics (Sable) 互換: ロードされていれば物理 sub-level に載った強化版ジュークボックスの再生追従を有効にする。
        modEventBus.addListener(com.kuronami.musicdiscmaker.compat.aeronautics.SableCompat::onCommonSetup);
        *///?}
        // Additional Additions 互換: ロードされていればアルバムを強化版ジュークボックスで再生できるようにする。
        modEventBus.addListener(com.kuronami.musicdiscmaker.compat.additionaladditions.AlbumCompat::onCommonSetup);

        modContainer.registerConfig(ModConfig.Type.CLIENT, NeoForgeConfigHelper.SPEC);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // hopper 等から空ディスク投入 / 出力取り出しを可能にする。
        //? if >=1.21.2 {
        // BE は vanilla WorldlyContainer を実装している。side を渡す WorldlyContainerWrapper で橋渡しし、
        // 面ごとのスロット規則 (取り出しは出力スロットのみ) を capability 経由でも通す。
        // side を捨てる VanillaContainerWrapper だとその規則が消え、hopper が強化スロットの
        // レッドストーンまで吸い出す。side が無い問い合わせ (null) のときだけ全スロット版に落とす。
        event.registerBlockEntity(Capabilities.Item.BLOCK, ModBlockEntities.MUSIC_DISC_MAKER.get(),
                (be, side) -> side == null
                        ? VanillaContainerWrapper.of(be)
                        : new WorldlyContainerWrapper(be, side));
        event.registerBlockEntity(Capabilities.Item.BLOCK, ModBlockEntities.GOLDEN_JUKEBOX.get(),
                (be, side) -> new WorldlyContainerWrapper(be, side) {
                    @Override
                    public int extract(int slot, net.neoforged.neoforge.transfer.item.ItemResource resource,
                            int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
                        final net.minecraft.core.Direction extractionSide =
                                side == null ? net.minecraft.core.Direction.DOWN : side;
                        if (!be.canTakeItemThroughFace(slot, be.getItem(slot), extractionSide)) return 0;
                        return super.extract(slot, resource, amount, transaction);
                    }
                });
    }

    private void buildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        // common は tab の identity のみ作る (26.2 の Output が protected)。中身はここで表示順に流し込む。
        if (event.getTab() == ModCreativeTab.MAIN.get()) {
            ModCreativeTab.TAB_ITEMS.forEach(holder -> event.accept(holder.get()));
        }
        //?} else {
        /*// BlockEntity は vanilla WorldlyContainer なので、side を渡す SidedInvWrapper で
        // IItemHandler に変換し、面ごとのスロット規則 (取り出しは出力スロットのみ) を通す。
        // side を捨てる InvWrapper だとその規則が消え、hopper が強化スロットのレッドストーンまで
        // 吸い出す。side が無い問い合わせ (null) のときだけ全スロット版に落とす。
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.MUSIC_DISC_MAKER.get(),
                (be, side) -> side == null ? new InvWrapper(be) : new SidedInvWrapper(be, side));
        // 強化版ジュークボックス: hopper でディスクの投入/取り出しを可能にする。
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.GOLDEN_JUKEBOX.get(),
                (be, side) -> side != null ? new SidedInvWrapper(be, side) : new InvWrapper(be) {
                    @Override
                    public net.minecraft.world.item.ItemStack extractItem(int slot, int amount, boolean simulate) {
                        if (!be.canTakeItemThroughFace(slot, be.getItem(slot), net.minecraft.core.Direction.DOWN)) {
                            return net.minecraft.world.item.ItemStack.EMPTY;
                        }
                        return super.extractItem(slot, amount, simulate);
                    }
                });
        *///?}
    }
}
