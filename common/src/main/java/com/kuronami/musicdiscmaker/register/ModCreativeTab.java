package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class ModCreativeTab {

    public static final RegistrationProvider<CreativeModeTab> TABS =
            RegistrationProvider.get(Registries.CREATIVE_MODE_TAB, MusicDiscMaker.MODID);

    // tab の identity (title/icon) のみ common で作り、中身は各ローダー
    // のタブ内容イベント (NeoForge=BuildCreativeModeTabContentsEvent / Fabric=ItemGroupEvents) で
    // {@link #TAB_ITEMS} を流し込む。
    public static final RegistryHolder<CreativeModeTab> MAIN =
            TABS.register("main", () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.music_disc_maker"))
                    .icon(() -> new ItemStack(ModItems.MUSIC_DISC_MAKER.get()))
                    .build());

    /** タブに載せるアイテム (各ローダーのタブ内容イベントが表示順で流し込む)。 */
    public static final java.util.List<RegistryHolder<? extends net.minecraft.world.item.Item>> TAB_ITEMS =
            java.util.List.of(ModItems.MUSIC_DISC_MAKER, ModItems.GOLDEN_JUKEBOX, ModItems.BLANK_DISC);

    private ModCreativeTab() {
    }

    public static void init() {
    }
}
