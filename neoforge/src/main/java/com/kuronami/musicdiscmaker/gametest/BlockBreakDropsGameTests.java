package com.kuronami.musicdiscmaker.gametest;

import java.util.function.Predicate;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.block.DiscDyeingTableBlockEntity;
import com.kuronami.musicdiscmaker.block.DiscPedestalBlockEntity;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
//? if <1.21.11 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/**
 * 中身を持つブロックを<b>中身を出さずに</b>壊した時に、中身がワールドへ落ちること。
 *
 * <p>1.21.5+ は {@code BlockEntity#preRemoveSideEffects} → BE 除去 →
 * {@code BlockState#affectNeighborsAfterRemoval} の順で走る。このため最後のフックで
 * {@code level.getBlockEntity(pos)} を引いても既に {@code null} で、そこだけに中身のドロップを
 * 置いたブロックは中身ごと消える。{@code Container} を実装していない台座がこの型だった。
 * 各ブロックの実体は個別に確認できるよう 1 本ずつ分けてある。
 */
public final class BlockBreakDropsGameTests {
    private BlockBreakDropsGameTests() { }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "blockBreakDrops")
    *///?}
    public static void breakingTheMakerDropsItsContents(GameTestHelper helper) {
        final BlockPos relativePos = new BlockPos(1, 1, 1);
        helper.setBlock(relativePos, ModBlocks.MUSIC_DISC_MAKER.get().defaultBlockState());
        final MusicDiscMakerBlockEntity maker =
                GameTestBlockEntities.get(helper, relativePos, MusicDiscMakerBlockEntity.class);
        try {
            maker.setItem(MusicDiscMakerBlockEntity.SLOT_INPUT, new ItemStack(ModItems.BLANK_DISC.get()));
            maker.setItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT, customDisc("maker-drop"));

            breakBlock(helper, relativePos, "製作機");

            helper.assertTrue(dropped(helper, relativePos, stack -> stack.is(ModItems.BLANK_DISC.get())) == 1,
                    "製作機を中身を出さずに壊した時に空ディスクがdropしない");
            helper.assertTrue(dropped(helper, relativePos,
                            stack -> "maker-drop".equals(DiscPedestalBlockEntity.titleOf(stack))) == 1,
                    "製作機を中身を出さずに壊した時に完成ディスクがdropしない");
        } finally {
            clear(helper, relativePos);
        }
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "blockBreakDrops")
    *///?}
    public static void breakingTheDyeingTableDropsItsContents(GameTestHelper helper) {
        final BlockPos relativePos = new BlockPos(2, 1, 1);
        helper.setBlock(relativePos, ModBlocks.DISC_DYEING_TABLE.get().defaultBlockState());
        final DiscDyeingTableBlockEntity table =
                GameTestBlockEntities.get(helper, relativePos, DiscDyeingTableBlockEntity.class);
        try {
            // 入力へ置くと染料台が結果の見本を出力slotへ組むので、同じディスクが2枚になる。
            table.setItem(DiscDyeingTableBlockEntity.SLOT_INPUT, customDisc("dye-drop"));

            breakBlock(helper, relativePos, "染料台");

            helper.assertTrue(dropped(helper, relativePos,
                            stack -> "dye-drop".equals(DiscPedestalBlockEntity.titleOf(stack))) == 2,
                    "染料台を中身を出さずに壊した時に入力と結果のディスクがdropしない");
        } finally {
            clear(helper, relativePos);
        }
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "blockBreakDrops")
    *///?}
    public static void breakingTheGoldenJukeboxDropsItsContents(GameTestHelper helper) {
        final BlockPos relativePos = new BlockPos(3, 1, 1);
        helper.setBlock(relativePos, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity jukebox =
                GameTestBlockEntities.get(helper, relativePos, GoldenJukeboxBlockEntity.class);
        try {
            jukebox.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, new ItemStack(Items.MUSIC_DISC_13));

            breakBlock(helper, relativePos, "金のジュークボックス");

            helper.assertTrue(dropped(helper, relativePos, stack -> stack.is(Items.MUSIC_DISC_13)) == 1,
                    "金のジュークボックスを中身を出さずに壊した時にディスクがdropしない");
        } finally {
            clear(helper, relativePos);
        }
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "blockBreakDrops")
    *///?}
    public static void breakingTheBoomboxDropsItsContents(GameTestHelper helper) {
        final BlockPos relativePos = new BlockPos(4, 1, 1);
        helper.setBlock(relativePos, ModBlocks.BOOMBOX.get().defaultBlockState());
        final BoomboxBlockEntity boombox =
                GameTestBlockEntities.get(helper, relativePos, BoomboxBlockEntity.class);
        try {
            final ItemStack stored = new ItemStack(ModItems.BOOMBOX.get());
            stored.set(ModDataComponents.BOOMBOX_CONTENTS.get(),
                    new BoomboxContents(customDisc("boombox-drop"), BoomboxContents.mintId()));
            boombox.setStored(stored);

            breakBlock(helper, relativePos, "ブームボックス");

            helper.assertTrue(dropped(helper, relativePos, stack -> stack.is(ModItems.BOOMBOX.get())) == 1,
                    "ブームボックスを中身を出さずに壊した時に本体がdropしない");
            helper.assertTrue(dropped(helper, relativePos, stack -> stack.is(ModItems.BOOMBOX.get())
                            && "boombox-drop".equals(DiscPedestalBlockEntity.titleOf(
                                    stack.get(ModDataComponents.BOOMBOX_CONTENTS.get()).disc()))) == 1,
                    "ブームボックスを壊した時に咥えていたディスクが本体と一緒にdropしない");
        } finally {
            clear(helper, relativePos);
        }
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "blockBreakDrops")
    *///?}
    public static void breakingThePedestalDropsItsContents(GameTestHelper helper) {
        final BlockPos relativePos = new BlockPos(5, 1, 1);
        helper.setBlock(relativePos, ModBlocks.DISC_PEDESTAL.get().defaultBlockState());
        final DiscPedestalBlockEntity pedestal =
                GameTestBlockEntities.get(helper, relativePos, DiscPedestalBlockEntity.class);
        try {
            pedestal.setStored(customDisc("pedestal-drop"));

            breakBlock(helper, relativePos, "ディスク台座");

            helper.assertTrue(dropped(helper, relativePos,
                            stack -> "pedestal-drop".equals(DiscPedestalBlockEntity.titleOf(stack))) == 1,
                    "ディスク台座を中身を出さずに壊した時に飾ってあるディスクがdropしない");
        } finally {
            clear(helper, relativePos);
        }
        helper.succeed();
    }

    private static ItemStack customDisc(String title) {
        final ItemStack stack = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        stack.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData(
                "https://example.invalid/" + title, title, "tester", 1_000L, "", false));
        return stack;
    }

    private static void breakBlock(GameTestHelper helper, BlockPos relativePos, String label) {
        helper.assertTrue(helper.getLevel().destroyBlock(helper.absolutePos(relativePos), true),
                label + "を通常破壊できない");
    }

    /** 壊した位置の周りに落ちているアイテムの合計枚数。 */
    private static int dropped(GameTestHelper helper, BlockPos relativePos, Predicate<ItemStack> match) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(helper.absolutePos(relativePos)).inflate(2.0),
                        entity -> match.test(entity.getItem()))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static void clear(GameTestHelper helper, BlockPos relativePos) {
        helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(helper.absolutePos(relativePos)).inflate(3.0))
                .forEach(ItemEntity::discard);
        helper.setBlock(relativePos, Blocks.AIR.defaultBlockState());
    }
}
