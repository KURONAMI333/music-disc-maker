package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlock;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MusicDiscMaker.MODID);

    // 硬さ・素材・適性ツール・音はジュークボックスと完全に同じ (素手で壊せる / 斧が効率ツール / hardness 2.0 / WOOD)。
    public static final DeferredBlock<MusicDiscMakerBlock> MUSIC_DISC_MAKER =
            BLOCKS.registerBlock("music_disc_maker", MusicDiscMakerBlock::new,
                    BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX));

    private ModBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
