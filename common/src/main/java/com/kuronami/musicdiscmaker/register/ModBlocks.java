package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlock;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlock;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {

    public static final RegistrationProvider<Block> BLOCKS =
            RegistrationProvider.get(Registries.BLOCK, MusicDiscMaker.MODID);

    // 硬さ・素材・適性ツール・音はジュークボックスと完全に同じ (素手で壊せる / 斧が効率ツール / hardness 2.0 / WOOD)。
    public static final RegistryHolder<MusicDiscMakerBlock> MUSIC_DISC_MAKER =
            BLOCKS.register("music_disc_maker",
                    () -> new MusicDiscMakerBlock(props("music_disc_maker")));

    // 強化版ジュークボックス。硬さ・素材・音はバニラ jukebox 準拠。
    public static final RegistryHolder<GoldenJukeboxBlock> GOLDEN_JUKEBOX =
            BLOCKS.register("golden_jukebox",
                    () -> new GoldenJukeboxBlock(props("golden_jukebox")));

    // 26.x: Block も生成時に登録 id を Properties へ set する必要がある。
    private static BlockBehaviour.Properties props(String name) {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)
                .setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, name)));
    }

    private ModBlocks() {
    }

    public static void init() {
    }
}
