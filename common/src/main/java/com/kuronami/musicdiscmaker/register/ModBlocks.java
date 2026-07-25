package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.BoomboxBlock;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlock;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlock;
import com.kuronami.musicdiscmaker.block.SpeakerBlock;
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

    // 強化版ジュークボックス。硬さ・素材・音はバニラ jukebox 準拠。
    public static final RegistryHolder<GoldenJukeboxBlock> GOLDEN_JUKEBOX =
            BLOCKS.register("golden_jukebox",
                    () -> new GoldenJukeboxBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));

    // スピーカー。木材系の note block 準拠 (斧で掘れる・音は木)。
    public static final RegistryHolder<SpeakerBlock> SPEAKER =
            BLOCKS.register("speaker",
                    () -> new SpeakerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK)));

    // ブームボックス。硬さ・素材・音は強化版ジュークボックスと同じ (バニラ jukebox 準拠)。
    public static final RegistryHolder<BoomboxBlock> BOOMBOX =
            BLOCKS.register("boombox",
                    () -> new BoomboxBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));

    private ModBlocks() {
    }

    public static void init() {
    }
}
