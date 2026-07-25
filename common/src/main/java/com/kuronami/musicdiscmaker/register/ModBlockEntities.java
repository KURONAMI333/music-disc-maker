package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
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

    // 26.2: BlockEntityType の public ctor は (BlockEntitySupplier, Set<Block>)。26.1.2 の
    // Block... varargs overload は撤去されたため Set.of(block) で渡す。ctor と BlockEntitySupplier は
    // どちらも public なので loader 抽象は不要 (common から直接生成できる)。
    public static final RegistryHolder<BlockEntityType<MusicDiscMakerBlockEntity>> MUSIC_DISC_MAKER =
            BLOCK_ENTITIES.register("music_disc_maker",
                    () -> Services.PLATFORM.createBlockEntityType(MusicDiscMakerBlockEntity::new, ModBlocks.MUSIC_DISC_MAKER.get()));

    public static final RegistryHolder<BlockEntityType<GoldenJukeboxBlockEntity>> GOLDEN_JUKEBOX =
            BLOCK_ENTITIES.register("golden_jukebox",
                    () -> Services.PLATFORM.createBlockEntityType(GoldenJukeboxBlockEntity::new, ModBlocks.GOLDEN_JUKEBOX.get()));

    private ModBlockEntities() {
    }

    public static void init() {
    }
}
