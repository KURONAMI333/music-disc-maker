package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlock;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {

    public static final RegistrationProvider<Block> BLOCKS =
            RegistrationProvider.get(Registries.BLOCK, MusicDiscMaker.MODID);

    // 硬さ・素材・適性ツール・音はジュークボックスと完全に同じ。
    public static final RegistryHolder<MusicDiscMakerBlock> MUSIC_DISC_MAKER =
            BLOCKS.register("music_disc_maker",
                    () -> new MusicDiscMakerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));

    private ModBlocks() {
    }

    public static void init() {
    }
}
