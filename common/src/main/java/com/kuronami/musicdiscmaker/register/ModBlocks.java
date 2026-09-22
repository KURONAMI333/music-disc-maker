package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.BoomboxBlock;
import com.kuronami.musicdiscmaker.block.SpeakerBlock;
import com.kuronami.musicdiscmaker.block.DiscDyeingTableBlock;
import com.kuronami.musicdiscmaker.block.DiscPedestalBlock;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlock;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlock;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
//? if >=1.21.2 {
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
//?} else {
//?}
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {

    public static final RegistrationProvider<Block> BLOCKS =
            RegistrationProvider.get(Registries.BLOCK, MusicDiscMaker.MODID);

    // 硬さ・素材・適性ツール・音はジュークボックスと完全に同じ (素手で壊せる / 斧が効率ツール / hardness 2.0 / WOOD)。
    public static final RegistryHolder<MusicDiscMakerBlock> MUSIC_DISC_MAKER =
            BLOCKS.register("music_disc_maker",
                    //? if >=1.21.2 {
                    () -> new MusicDiscMakerBlock(props("music_disc_maker")));
                    //?} elif >=1.21 {
                    /*() -> new MusicDiscMakerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
                    *///?} else {
                    /*() -> new MusicDiscMakerBlock(BlockBehaviour.Properties.copy(Blocks.JUKEBOX)));
                    *///?}

    // 強化版ジュークボックス。硬さ・素材・音はバニラ jukebox 準拠。
    public static final RegistryHolder<GoldenJukeboxBlock> GOLDEN_JUKEBOX =
            BLOCKS.register("golden_jukebox",
                    //? if >=1.21.2 {
                    () -> new GoldenJukeboxBlock(props("golden_jukebox")));

    // 26.x: Block も生成時に登録 id を Properties へ set する必要がある。
    private static BlockBehaviour.Properties props(String name) {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)
                .setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, name)));
    }
                    //?} elif >=1.21 {
                    /*() -> new GoldenJukeboxBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
                    *///?} else {
                    /*() -> new GoldenJukeboxBlock(BlockBehaviour.Properties.copy(Blocks.JUKEBOX)));
                    *///?}

    // custom disc の色を塗り替える作業台。硬さ・素材・音は他の 2 ブロックと揃えて jukebox 準拠。
    public static final RegistryHolder<DiscDyeingTableBlock> DISC_DYEING_TABLE =
            BLOCKS.register("disc_dyeing_table",
                    //? if >=1.21.2 {
                    () -> new DiscDyeingTableBlock(props("disc_dyeing_table")));
                    //?} elif >=1.21 {
                    /*() -> new DiscDyeingTableBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
                    *///?} else {
                    /*() -> new DiscDyeingTableBlock(BlockBehaviour.Properties.copy(Blocks.JUKEBOX)));
                    *///?}

    // 置いたブームボックス。適正ツールを持たせず、素手を含む全ての道具で同じ速さにする。
    // バニラの作業台を木の斧で壊す速度 (hardness 2.5 / tool speed 2 / correct-tool divisor 30)
    // と揃えるため、適正ツール不要 (hasCorrectToolForDrops=true) の divisor 30 に合わせて
    // hardness を 1.25 にする。mineable タグを持たないため、道具速度はすべて 1 になる。
    // noOcclusion は必須。3D モデルは 16x14x7 で立方体ではないので、jukebox からコピーした
    // canOcclude=true のままだと箱の下に立方体ぶんの影が落ち、頭が中に入ると画面が暗転する。
    //? if >=1.21.2 {
    public static final RegistryHolder<BoomboxBlock> BOOMBOX =
            BLOCKS.register("boombox", () -> new BoomboxBlock(props("boombox")
                    .strength(1.25F).noOcclusion()));
    //?} elif >=1.21 {
    /*public static final RegistryHolder<BoomboxBlock> BOOMBOX =
            BLOCKS.register("boombox",
                    () -> new BoomboxBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)
                            .strength(1.25F).noOcclusion()));
    *///?} else {
    /*public static final RegistryHolder<BoomboxBlock> BOOMBOX =
            BLOCKS.register("boombox",
                    () -> new BoomboxBlock(BlockBehaviour.Properties.copy(Blocks.JUKEBOX)
                            .strength(1.25F).noOcclusion()));
    *///?}

    // ディスクを飾る台座。noOcclusion は必須 (土台 + 柱 + 天板で立方体ではないので、
    // jukebox からコピーした canOcclude=true のままだと立方体ぶんの影が落ちる。BOOMBOX と同じ理由)。
    //? if >=1.21.2 {
    public static final RegistryHolder<DiscPedestalBlock> DISC_PEDESTAL =
            BLOCKS.register("disc_pedestal", () -> new DiscPedestalBlock(props("disc_pedestal").noOcclusion()));
    //?} elif >=1.21 {
    /*public static final RegistryHolder<DiscPedestalBlock> DISC_PEDESTAL =
            BLOCKS.register("disc_pedestal",
                    () -> new DiscPedestalBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX).noOcclusion()));
    *///?} else {
    /*public static final RegistryHolder<DiscPedestalBlock> DISC_PEDESTAL =
            BLOCKS.register("disc_pedestal",
                    () -> new DiscPedestalBlock(BlockBehaviour.Properties.copy(Blocks.JUKEBOX).noOcclusion()));
    *///?}

    //? if >=1.21.2 {
    public static final RegistryHolder<SpeakerBlock> SPEAKER =
            BLOCKS.register("speaker", () -> new SpeakerBlock(props("speaker").noOcclusion()));
    //?} elif >=1.21 {
    /*public static final RegistryHolder<SpeakerBlock> SPEAKER =
            BLOCKS.register("speaker",
                    () -> new SpeakerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX).noOcclusion()));
    *///?} else {
    /*public static final RegistryHolder<SpeakerBlock> SPEAKER =
            BLOCKS.register("speaker",
                    () -> new SpeakerBlock(BlockBehaviour.Properties.copy(Blocks.JUKEBOX).noOcclusion()));
    *///?}

    private ModBlocks() {
    }

    public static void init() {
    }
}

