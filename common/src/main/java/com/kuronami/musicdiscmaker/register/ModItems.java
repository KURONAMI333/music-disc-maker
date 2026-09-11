package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.item.BoomboxItem;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.item.SpeakerItem;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
//? if >=1.21.2 {
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
//?} else {
//?}
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

public final class ModItems {

    public static final RegistrationProvider<Item> ITEMS =
            RegistrationProvider.get(Registries.ITEM, MusicDiscMaker.MODID);

    /** 空ディスク。Music Disc Maker に投入して custom disc にする。 */
    public static final RegistryHolder<Item> BLANK_DISC =
            //? if >=1.21.2 {
            ITEMS.register("blank_disc", () -> new Item(props("blank_disc").stacksTo(16)));
            //?} else {
            /*ITEMS.register("blank_disc", () -> new Item(new Item.Properties().stacksTo(16)));
            *///?}

    /** custom music disc。CUSTOM_TRACK component を持ち jukebox で再生される。 */
    public static final RegistryHolder<CustomMusicDiscItem> CUSTOM_MUSIC_DISC =
            ITEMS.register("custom_music_disc",
                    //? if >=1.21.2 {
                    () -> new CustomMusicDiscItem(props("custom_music_disc").stacksTo(1)));
                    //?} else {
                    /*() -> new CustomMusicDiscItem(new Item.Properties().stacksTo(1)));
                    *///?}

    /** 完成ディスクを束ねる、革表紙と紙スリーブの Album。 */
    public static final RegistryHolder<AlbumItem> ALBUM =
            //? if >=1.21.2 {
            ITEMS.register("album", () -> new AlbumItem(props("album").stacksTo(1)));
            //?} else {
            /*ITEMS.register("album", () -> new AlbumItem(new Item.Properties().stacksTo(1)));
            *///?}

    /** Music Disc Maker ブロックの BlockItem。 */
    public static final RegistryHolder<BlockItem> MUSIC_DISC_MAKER =
            ITEMS.register("music_disc_maker",
                    //? if >=1.21.2 {
                    () -> new BlockItem(ModBlocks.MUSIC_DISC_MAKER.get(),
                            props("music_disc_maker").useBlockDescriptionPrefix()));
                    //?} else {
                    /*() -> new BlockItem(ModBlocks.MUSIC_DISC_MAKER.get(), new Item.Properties()));
                    *///?}

    /** 強化版ジュークボックスの BlockItem。 */
    public static final RegistryHolder<BlockItem> GOLDEN_JUKEBOX =
            ITEMS.register("golden_jukebox",
                    //? if >=1.21.2 {
                    () -> new BlockItem(ModBlocks.GOLDEN_JUKEBOX.get(),
                            props("golden_jukebox").useBlockDescriptionPrefix()));

    // 26.x: Item は生成時に登録 id を Properties へ set する必要がある (NeoForge の
    // DeferredRegister.Items が内部でやっていることを loader 非依存に共通化する)。
    private static Item.Properties props(String name) {
        return new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, name)));
    }
                    //?} else {
                    /*() -> new BlockItem(ModBlocks.GOLDEN_JUKEBOX.get(), new Item.Properties()));
                    *///?}

    /** Disc Dyeing Table の BlockItem。 */
    public static final RegistryHolder<BlockItem> DISC_DYEING_TABLE =
            ITEMS.register("disc_dyeing_table",
                    //? if >=1.21.2 {
                    () -> new BlockItem(ModBlocks.DISC_DYEING_TABLE.get(),
                            props("disc_dyeing_table").useBlockDescriptionPrefix()));
                    //?} else {
                    /*() -> new BlockItem(ModBlocks.DISC_DYEING_TABLE.get(), new Item.Properties()));
                    *///?}

    /** 携帯ブームボックス。BlockItem ではない (置くのは BoomboxItem#useOn が自前で行う)。 */
    //? if >=1.21.2 {
    public static final RegistryHolder<BoomboxItem> BOOMBOX =
            ITEMS.register("boombox", () -> new BoomboxItem(props("boombox").stacksTo(1)));
    //?} elif >=1.21 {
    /*public static final RegistryHolder<BoomboxItem> BOOMBOX =
            ITEMS.register("boombox", () -> new BoomboxItem(new Item.Properties().stacksTo(1)));
    *///?} else {
    /*public static final RegistryHolder<BoomboxItem> BOOMBOX =
            ITEMS.register("boombox", () -> new BoomboxItem(new Item.Properties().stacksTo(1)));
    *///?}

    /** ディスクを飾る台座の BlockItem。 */
    //? if >=1.21.2 {
    public static final RegistryHolder<BlockItem> DISC_PEDESTAL =
            ITEMS.register("disc_pedestal", () -> new BlockItem(ModBlocks.DISC_PEDESTAL.get(),
                    props("disc_pedestal").useBlockDescriptionPrefix()));
    //?} elif >=1.21 {
    /*public static final RegistryHolder<BlockItem> DISC_PEDESTAL =
            ITEMS.register("disc_pedestal", () -> new BlockItem(ModBlocks.DISC_PEDESTAL.get(), new Item.Properties()));
    *///?} else {
    /*public static final RegistryHolder<BlockItem> DISC_PEDESTAL =
            ITEMS.register("disc_pedestal", () -> new BlockItem(ModBlocks.DISC_PEDESTAL.get(), new Item.Properties()));
    *///?}

    //? if >=1.21.2 {
    public static final RegistryHolder<SpeakerItem> SPEAKER =
            ITEMS.register("speaker", () -> new SpeakerItem(ModBlocks.SPEAKER.get(),
                    props("speaker").useBlockDescriptionPrefix()));
    //?} elif >=1.21 {
    /*public static final RegistryHolder<SpeakerItem> SPEAKER =
            ITEMS.register("speaker", () -> new SpeakerItem(ModBlocks.SPEAKER.get(), new Item.Properties()));
    *///?} else {
    /*public static final RegistryHolder<SpeakerItem> SPEAKER =
            ITEMS.register("speaker", () -> new SpeakerItem(ModBlocks.SPEAKER.get(), new Item.Properties()));
    *///?}

    private ModItems() {
    }

    public static void init() {
    }
}
