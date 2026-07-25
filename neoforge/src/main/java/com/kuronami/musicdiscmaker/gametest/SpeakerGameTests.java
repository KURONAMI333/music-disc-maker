package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.SpeakerBlock;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.event.SpeakerNetwork;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * スピーカーの server 側ロジックの headless テスト。リンクの確立・拒否・レッドストーン ミュート・
 * 破壊時の解除を機械判定する。実音・聴取挙動は client 側なのでここでは扱わない (kura 実機帯)。
 *
 * <p>設置は {@code BlockItem#place} と同じ順序 (ブロック配置 → component 適用 → setPlacedBy) を
 * 直接再現する。GameTest には実プレイヤーの右クリックが無いため。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class SpeakerGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    /** 相対座標にスピーカーを置き、{@code sourceRel} を音源として記憶したアイテムから設置する。 */
    private static SpeakerBlockEntity placeLinkedSpeaker(GameTestHelper helper, BlockPos speakerRel,
            BlockPos absoluteSource) {
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get());
        final ServerLevel level = helper.getLevel();
        final BlockPos speakerAbs = helper.absolutePos(speakerRel);
        final SpeakerBlockEntity be = helper.getBlockEntity(speakerRel);
        if (be == null) {
            helper.fail("SpeakerBlockEntity が生成されていない", speakerRel);
            return null;
        }
        final ItemStack stack = new ItemStack(ModItems.SPEAKER.get());
        stack.set(ModDataComponents.SPEAKER_SOURCE.get(), GlobalPos.of(level.dimension(), absoluteSource));
        // BlockItem#place と同じ順序: component 適用 → setPlacedBy (検証と index 登録)。
        be.applyComponentsFromItemStack(stack);
        final BlockState state = level.getBlockState(speakerAbs);
        state.getBlock().setPlacedBy(level, speakerAbs, state, null, stack);
        return be;
    }

    /** 有効なリンク: BE に音源が入り、逆引き index と配送用の集合に載る。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void speakerLinksToJukebox(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(1, 1, 1);
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final BlockPos jukeboxAbs = helper.absolutePos(jukeboxRel);

        final SpeakerBlockEntity be = placeLinkedSpeaker(helper, new BlockPos(4, 1, 4), jukeboxAbs);
        if (be == null) {
            return;
        }
        helper.assertTrue(jukeboxAbs.equals(be.getSourcePos()), "リンクが確立していない");
        helper.assertTrue(
                SpeakerNetwork.speakersOf(helper.getLevel(), jukeboxAbs).contains(helper.absolutePos(new BlockPos(4, 1, 4))),
                "逆引き index にスピーカーが登録されていない");
        helper.assertTrue(SpeakerNetwork.activeEntries(helper.getLevel(), jukeboxAbs).size() == 1,
                "配送用のスピーカー集合が 1 台になっていない");
        helper.succeed();
    }

    /** 記憶した位置に金ジュークが無ければリンクしない。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void speakerRejectsMissingSource(GameTestHelper helper) {
        final BlockPos emptyAbs = helper.absolutePos(new BlockPos(1, 1, 1));
        final SpeakerBlockEntity be = placeLinkedSpeaker(helper, new BlockPos(4, 1, 4), emptyAbs);
        if (be == null) {
            return;
        }
        helper.assertTrue(be.getSourcePos() == null, "音源不在なのにリンクが残っている");
        helper.assertTrue(SpeakerNetwork.speakersOf(helper.getLevel(), emptyAbs).isEmpty(),
                "音源不在なのに逆引き index に登録された");
        helper.succeed();
    }

    /** リンク上限距離を超えた音源にはリンクしない。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void speakerRejectsTooFarSource(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        // 構造物の外に本物の金ジュークを置き、距離だけで弾かれることを確かめる
        // (音源不在の分岐と区別するため、実在させた上で上限距離の外に置く)。
        final BlockPos farAbs = helper.absolutePos(new BlockPos(1, 1, 1))
                .offset(Config.speakerLinkRange() + 8, 0, 0);
        level.setBlockAndUpdate(farAbs, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        helper.assertTrue(level.getBlockState(farAbs).is(ModBlocks.GOLDEN_JUKEBOX.get()),
                "遠方の音源を設置できなかった (テストの前提が崩れている)");

        final SpeakerBlockEntity be = placeLinkedSpeaker(helper, new BlockPos(4, 1, 4), farAbs);
        level.setBlockAndUpdate(farAbs, Blocks.AIR.defaultBlockState());
        if (be == null) {
            return;
        }
        helper.assertTrue(be.getSourcePos() == null, "上限距離を超えているのにリンクが残っている");
        helper.succeed();
    }

    /** 1 音源あたりの台数上限を超えるリンクは拒否する。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void speakerRejectsOverCap(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(0, 1, 0);
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final BlockPos jukeboxAbs = helper.absolutePos(jukeboxRel);
        final int cap = Config.maxSpeakersPerSource();

        int placed = 0;
        for (int z = 0; z < 8 && placed < cap; z++) {
            for (int x = 0; x < 8 && placed < cap; x++) {
                if (x == 0 && z == 0) {
                    continue; // 音源の位置
                }
                placeLinkedSpeaker(helper, new BlockPos(x, 1, z), jukeboxAbs);
                placed++;
            }
        }
        helper.assertTrue(SpeakerNetwork.countFor(helper.getLevel(), jukeboxAbs) == cap,
                "上限まで登録できていない: " + SpeakerNetwork.countFor(helper.getLevel(), jukeboxAbs) + "/" + cap);

        // 上限 +1 台目 (1 段上に置く) は拒否される。
        final SpeakerBlockEntity overflow = placeLinkedSpeaker(helper, new BlockPos(1, 2, 1), jukeboxAbs);
        if (overflow == null) {
            return;
        }
        helper.assertTrue(overflow.getSourcePos() == null, "台数上限を超えたのにリンクが成立した");
        helper.assertTrue(SpeakerNetwork.countFor(helper.getLevel(), jukeboxAbs) == cap,
                "台数上限を超えて index に登録された");
        helper.succeed();
    }

    /** レッドストーン信号が入ったスピーカーは配送用の集合から外れる (ミュート)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void redstoneMutesSpeaker(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(1, 1, 1);
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final BlockPos jukeboxAbs = helper.absolutePos(jukeboxRel);

        final BlockPos speakerRel = new BlockPos(4, 1, 4);
        final SpeakerBlockEntity be = placeLinkedSpeaker(helper, speakerRel, jukeboxAbs);
        if (be == null) {
            return;
        }
        helper.assertTrue(SpeakerNetwork.activeEntries(helper.getLevel(), jukeboxAbs).size() == 1,
                "ミュート前に集合へ載っていない");

        helper.setBlock(speakerRel.east(), Blocks.REDSTONE_BLOCK);
        helper.assertBlockProperty(speakerRel, SpeakerBlock.POWERED, Boolean.TRUE);
        helper.assertTrue(be.isMuted(), "レッドストーン信号でミュートになっていない");
        helper.assertTrue(SpeakerNetwork.activeEntries(helper.getLevel(), jukeboxAbs).isEmpty(),
                "ミュート中のスピーカーが配送用の集合に残っている");
        helper.succeed();
    }

    /** スピーカーを壊すと逆引き index から外れる。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void speakerUnregistersOnRemoval(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(1, 1, 1);
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final BlockPos jukeboxAbs = helper.absolutePos(jukeboxRel);

        final BlockPos speakerRel = new BlockPos(4, 1, 4);
        if (placeLinkedSpeaker(helper, speakerRel, jukeboxAbs) == null) {
            return;
        }
        helper.assertTrue(SpeakerNetwork.countFor(helper.getLevel(), jukeboxAbs) == 1,
                "破壊前に登録されていない");

        helper.setBlock(speakerRel, Blocks.AIR);
        helper.assertTrue(SpeakerNetwork.countFor(helper.getLevel(), jukeboxAbs) == 0,
                "破壊後も逆引き index に残っている");
        helper.succeed();
    }
}
