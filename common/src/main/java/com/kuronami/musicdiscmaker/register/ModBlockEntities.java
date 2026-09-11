package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.block.DiscDyeingTableBlockEntity;
import com.kuronami.musicdiscmaker.block.DiscPedestalBlockEntity;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {

    public static final RegistrationProvider<BlockEntityType<?>> BLOCK_ENTITIES =
            RegistrationProvider.get(Registries.BLOCK_ENTITY_TYPE, MusicDiscMaker.MODID);

    // BlockEntityType$BlockEntitySupplier は vanilla の neoForm classpath では package-private なので
    // common から直接生成できない。生成は loader 層の SPI (Services.PLATFORM.createBlockEntityType) に委ねる。
    public static final RegistryHolder<BlockEntityType<MusicDiscMakerBlockEntity>> MUSIC_DISC_MAKER =
            BLOCK_ENTITIES.register("music_disc_maker",
                    () -> Services.PLATFORM.createBlockEntityType(MusicDiscMakerBlockEntity::new, ModBlocks.MUSIC_DISC_MAKER.get()));

    public static final RegistryHolder<BlockEntityType<GoldenJukeboxBlockEntity>> GOLDEN_JUKEBOX =
            BLOCK_ENTITIES.register("golden_jukebox",
                    () -> Services.PLATFORM.createBlockEntityType(GoldenJukeboxBlockEntity::new, ModBlocks.GOLDEN_JUKEBOX.get()));

    public static final RegistryHolder<BlockEntityType<DiscDyeingTableBlockEntity>> DISC_DYEING_TABLE =
            BLOCK_ENTITIES.register("disc_dyeing_table",
                    () -> Services.PLATFORM.createBlockEntityType(DiscDyeingTableBlockEntity::new, ModBlocks.DISC_DYEING_TABLE.get()));

    public static final RegistryHolder<BlockEntityType<BoomboxBlockEntity>> BOOMBOX =
            BLOCK_ENTITIES.register("boombox",
                    () -> Services.PLATFORM.createBlockEntityType(BoomboxBlockEntity::new, ModBlocks.BOOMBOX.get()));

    public static final RegistryHolder<BlockEntityType<DiscPedestalBlockEntity>> DISC_PEDESTAL =
            BLOCK_ENTITIES.register("disc_pedestal",
                    () -> Services.PLATFORM.createBlockEntityType(DiscPedestalBlockEntity::new, ModBlocks.DISC_PEDESTAL.get()));

    public static final RegistryHolder<BlockEntityType<SpeakerBlockEntity>> SPEAKER =
            BLOCK_ENTITIES.register("speaker",
                    () -> Services.PLATFORM.createBlockEntityType(SpeakerBlockEntity::new, ModBlocks.SPEAKER.get()));

    private ModBlockEntities() {
    }

    public static void init() {
    }
}
