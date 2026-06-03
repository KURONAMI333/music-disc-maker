package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MusicDiscMaker.MODID);

    /** 空ディスク。Music Disc Maker に投入して custom disc にする。簡単にクラフトできるのでコモン (白)。 */
    public static final DeferredItem<Item> BLANK_DISC =
            ITEMS.registerSimpleItem("blank_disc", new Item.Properties().stacksTo(16));

    /** custom music disc。CUSTOM_TRACK component を持ち、jukebox で再生される。コモン (白) — 色は曲名側で表現。 */
    public static final DeferredItem<CustomMusicDiscItem> CUSTOM_MUSIC_DISC =
            ITEMS.registerItem("custom_music_disc", CustomMusicDiscItem::new,
                    new Item.Properties().stacksTo(1));

    /** Music Disc Maker ブロックの BlockItem。 */
    public static final DeferredItem<BlockItem> MUSIC_DISC_MAKER =
            ITEMS.registerSimpleBlockItem("music_disc_maker", ModBlocks.MUSIC_DISC_MAKER);

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
