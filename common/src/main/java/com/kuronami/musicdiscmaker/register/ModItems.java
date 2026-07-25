package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

public final class ModItems {

    public static final RegistrationProvider<Item> ITEMS =
            RegistrationProvider.get(Registries.ITEM, MusicDiscMaker.MODID);

    /** 空ディスク。Music Disc Maker に投入して custom disc にする。 */
    public static final RegistryHolder<Item> BLANK_DISC =
            ITEMS.register("blank_disc", () -> new Item(props("blank_disc").stacksTo(16)));

    /** custom music disc。CUSTOM_TRACK component を持ち jukebox で再生される。 */
    public static final RegistryHolder<CustomMusicDiscItem> CUSTOM_MUSIC_DISC =
            ITEMS.register("custom_music_disc",
                    () -> new CustomMusicDiscItem(props("custom_music_disc").stacksTo(1)));

    /** Music Disc Maker ブロックの BlockItem。 */
    public static final RegistryHolder<BlockItem> MUSIC_DISC_MAKER =
            ITEMS.register("music_disc_maker",
                    () -> new BlockItem(ModBlocks.MUSIC_DISC_MAKER.get(),
                            props("music_disc_maker").useBlockDescriptionPrefix()));

    /** 強化版ジュークボックスの BlockItem。 */
    public static final RegistryHolder<BlockItem> GOLDEN_JUKEBOX =
            ITEMS.register("golden_jukebox",
                    () -> new BlockItem(ModBlocks.GOLDEN_JUKEBOX.get(),
                            props("golden_jukebox").useBlockDescriptionPrefix()));

    // Item は生成時に登録 id を Properties へ set する必要がある (NeoForge の
    // DeferredRegister.Items が内部でやっていることを loader 非依存に共通化する)。
    private static Item.Properties props(String name) {
        return new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, name)));
    }

    private ModItems() {
    }

    public static void init() {
    }
}
