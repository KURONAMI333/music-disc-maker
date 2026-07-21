package com.kuronami.musicdiscmaker.register;

import java.util.function.Supplier;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MusicDiscMaker.MODID);

    public static final Supplier<BlockEntityType<MusicDiscMakerBlockEntity>> MUSIC_DISC_MAKER =
            BLOCK_ENTITIES.register("music_disc_maker",
                    () -> new BlockEntityType<>(MusicDiscMakerBlockEntity::new, ModBlocks.MUSIC_DISC_MAKER.get()));

    public static final Supplier<BlockEntityType<GoldenJukeboxBlockEntity>> GOLDEN_JUKEBOX =
            BLOCK_ENTITIES.register("golden_jukebox",
                    () -> new BlockEntityType<>(GoldenJukeboxBlockEntity::new, ModBlocks.GOLDEN_JUKEBOX.get()));

    private ModBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}
