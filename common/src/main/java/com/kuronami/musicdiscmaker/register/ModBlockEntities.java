package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.EnhancedJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {

    public static final RegistrationProvider<BlockEntityType<?>> BLOCK_ENTITIES =
            RegistrationProvider.get(Registries.BLOCK_ENTITY_TYPE, MusicDiscMaker.MODID);

    // BlockEntityType の生成は loader 固有 (vanilla builder の supplier が package-private)。
    public static final RegistryHolder<BlockEntityType<MusicDiscMakerBlockEntity>> MUSIC_DISC_MAKER =
            BLOCK_ENTITIES.register("music_disc_maker",
                    () -> Services.PLATFORM.createBlockEntityType(
                            MusicDiscMakerBlockEntity::new, ModBlocks.MUSIC_DISC_MAKER.get()));

    public static final RegistryHolder<BlockEntityType<EnhancedJukeboxBlockEntity>> ENHANCED_JUKEBOX =
            BLOCK_ENTITIES.register("enhanced_jukebox",
                    () -> Services.PLATFORM.createBlockEntityType(
                            EnhancedJukeboxBlockEntity::new, ModBlocks.ENHANCED_JUKEBOX.get()));

    private ModBlockEntities() {
    }

    public static void init() {
    }
}
