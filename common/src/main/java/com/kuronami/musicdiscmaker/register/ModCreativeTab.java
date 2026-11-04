package com.kuronami.musicdiscmaker.register;

import java.util.ArrayList;
import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.color.DiscDye;
import com.kuronami.musicdiscmaker.component.DiscDyeData;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ModCreativeTab {

    public static final RegistrationProvider<CreativeModeTab> TABS =
            RegistrationProvider.get(Registries.CREATIVE_MODE_TAB, MusicDiscMaker.MODID);

    // 1.21.2+ は CreativeModeTab.Output が protected になり、displayItems generator を common
    // (vanilla classpath) で書けない。tab の identity (title/icon) のみ common で作り、中身は各ローダー
    // のタブ内容イベント (NeoForge=BuildCreativeModeTabContentsEvent / Fabric=ItemGroupEvents) で
    // {@link #displayStacks()} を流し込む。1.21.1 / 1.20.1 は下の displayItems generator がそのまま効く
    // (loader イベントで二重に流さない)。
    public static final RegistryHolder<CreativeModeTab> MAIN =
            TABS.register("main", () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.music_disc_maker"))
                    .icon(() -> {
                        final ItemStack icon = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
                        CustomMusicDiscItem.setDye(icon, new DiscDyeData(null, DiscDye.RED));
                        return icon;
                    })
                    //? if >=1.21.2 {
                    //?} else {
                    /*.displayItems((params, output) -> displayStacks().forEach(stack -> output.accept(stack)))
                    *///?}
                    .build());

    /**
     * タブに載せるアイテムを表示順で返す。アルバム群 (無色の直後に 16 色を DyeColor の定義順) は末尾にまとめる。
     * 各ローダーのタブ内容イベントと、1.21.1 / 1.20.1 の displayItems generator が共有する。
     */
    public static List<ItemStack> displayStacks() {
        final List<ItemStack> stacks = new ArrayList<>();
        stacks.add(new ItemStack(ModItems.MUSIC_DISC_MAKER.get()));
        stacks.add(new ItemStack(ModItems.BLANK_DISC.get()));
        stacks.add(new ItemStack(ModItems.GOLDEN_JUKEBOX.get()));
        stacks.add(new ItemStack(ModItems.DISC_DYEING_TABLE.get()));
        stacks.add(new ItemStack(ModItems.DISC_PEDESTAL.get()));
        stacks.add(new ItemStack(ModItems.SPEAKER.get()));
        stacks.add(new ItemStack(ModItems.BOOMBOX.get()));
        stacks.add(new ItemStack(ModItems.GOLDEN_JUKEBOX_MINECART.get()));
        addAlbumAndColors(stacks, ModItems.ALBUM.get());
        addAlbumAndColors(stacks, ModItems.GOLDEN_ALBUM.get());
        return stacks;
    }

    /** 無色の Album と、その直後に 16 色を DyeColor の定義順で並べる。名前は変えない。 */
    private static void addAlbumAndColors(List<ItemStack> stacks, Item album) {
        stacks.add(new ItemStack(album));
        for (final DyeColor color : DyeColor.values()) {
            final ItemStack dyed = new ItemStack(album);
            AlbumItem.setColor(dyed, color);
            stacks.add(dyed);
        }
    }

    private ModCreativeTab() {
    }

    public static void init() {
    }
}
