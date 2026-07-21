package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

public final class ModItems {

    public static final RegistrationProvider<Item> ITEMS =
            RegistrationProvider.get(Registries.ITEM, MusicDiscMaker.MODID);

    /** 空ディスク。Music Disc Maker に投入して custom disc にする。 */
    public static final RegistryHolder<Item> BLANK_DISC =
            ITEMS.register("blank_disc", () -> new Item(new Item.Properties().stacksTo(16)));

    /** custom music disc。CUSTOM_TRACK component を持ち jukebox で再生される。 */
    public static final RegistryHolder<CustomMusicDiscItem> CUSTOM_MUSIC_DISC =
            ITEMS.register("custom_music_disc",
                    () -> new CustomMusicDiscItem(new Item.Properties().stacksTo(1)));

    /** Music Disc Maker ブロックの BlockItem。 */
    public static final RegistryHolder<BlockItem> MUSIC_DISC_MAKER =
            ITEMS.register("music_disc_maker",
                    () -> new BlockItem(ModBlocks.MUSIC_DISC_MAKER.get(), new Item.Properties()));

    /** 強化版ジュークボックスの BlockItem。 */
    public static final RegistryHolder<BlockItem> GOLDEN_JUKEBOX =
            ITEMS.register("golden_jukebox",
                    () -> new BlockItem(ModBlocks.GOLDEN_JUKEBOX.get(), new Item.Properties()));

    private ModItems() {
    }

    public static void init() {
    }
}
