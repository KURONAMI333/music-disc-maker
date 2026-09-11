package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.color.DiscDye;
import com.kuronami.musicdiscmaker.component.DiscDyeData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class ModCreativeTab {

    public static final RegistrationProvider<CreativeModeTab> TABS =
            RegistrationProvider.get(Registries.CREATIVE_MODE_TAB, MusicDiscMaker.MODID);

    // 26.2 は CreativeModeTab.Output が protected になり、displayItems generator を common
    // (vanilla classpath) で書けない。tab の identity (title/icon) のみ common で作り、中身は各ローダー
    // のタブ内容イベント (NeoForge=BuildCreativeModeTabContentsEvent / Fabric=ItemGroupEvents) で
    // {@link #TAB_ITEMS} を流し込む。
    public static final RegistryHolder<CreativeModeTab> MAIN =
            TABS.register("main", () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.music_disc_maker"))
                    .icon(() -> {
                        final ItemStack icon = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
                        CustomMusicDiscItem.setDye(icon, new DiscDyeData(null, DiscDye.RED));
                        return icon;
                    })
                    //? if >=1.21.2 {
                    //?} elif >=1.21 {
                    /*.displayItems((params, output) -> {
                        output.accept(ModItems.MUSIC_DISC_MAKER.get());
                        output.accept(ModItems.BLANK_DISC.get());
                        output.accept(ModItems.GOLDEN_JUKEBOX.get());
                        output.accept(ModItems.DISC_DYEING_TABLE.get());
                        output.accept(ModItems.DISC_PEDESTAL.get());
                        output.accept(ModItems.SPEAKER.get());
                        output.accept(ModItems.ALBUM.get());
                        output.accept(ModItems.BOOMBOX.get());
                    })
                    *///?} else {
                    //?}
                    .build());
    //? if >=1.21.2 {

    /** タブに載せるアイテム (各ローダーのタブ内容イベントが表示順で流し込む)。 */
    public static final java.util.List<RegistryHolder<? extends net.minecraft.world.item.Item>> TAB_ITEMS =
            java.util.List.of(ModItems.MUSIC_DISC_MAKER, ModItems.BLANK_DISC, ModItems.GOLDEN_JUKEBOX,
                    ModItems.DISC_DYEING_TABLE, ModItems.DISC_PEDESTAL, ModItems.SPEAKER, ModItems.ALBUM, ModItems.BOOMBOX);
    //?} elif >=1.21 {
    //?} else {
    /*public static final java.util.List<RegistryHolder<? extends net.minecraft.world.item.Item>> TAB_ITEMS =
            java.util.List.of(ModItems.MUSIC_DISC_MAKER, ModItems.BLANK_DISC, ModItems.GOLDEN_JUKEBOX,
                    ModItems.DISC_DYEING_TABLE, ModItems.DISC_PEDESTAL, ModItems.SPEAKER, ModItems.ALBUM, ModItems.BOOMBOX);
    *///?}

    private ModCreativeTab() {
    }

    public static void init() {
    }
}
