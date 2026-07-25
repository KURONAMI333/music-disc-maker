package com.kuronami.musicdiscmaker.gametest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlock;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.client.audio.SpeakerSelection;
import com.kuronami.musicdiscmaker.event.SpeakerNetwork;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * スピーカーの server 側ロジックの headless テスト。リンクの確立・拒否・レッドストーン ミュート・
 * 破壊時の解除を機械判定する。実音・聴取挙動は client 側なのでここでは扱わない (実機確認帯)。
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

    /** 破壊ドロップがリンクを保持する (loot table の copy_components + collectImplicitComponents)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void speakerDropCarriesLink(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(1, 1, 1);
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final BlockPos jukeboxAbs = helper.absolutePos(jukeboxRel);

        final BlockPos speakerRel = new BlockPos(4, 1, 4);
        final SpeakerBlockEntity be = placeLinkedSpeaker(helper, speakerRel, jukeboxAbs);
        if (be == null) {
            return;
        }
        final BlockPos speakerAbs = helper.absolutePos(speakerRel);
        final List<ItemStack> drops = Block.getDrops(
                helper.getLevel().getBlockState(speakerAbs), helper.getLevel(), speakerAbs, be);
        helper.assertTrue(drops.size() == 1, "ドロップが 1 個でない: " + drops.size());
        final GlobalPos link = drops.get(0).get(ModDataComponents.SPEAKER_SOURCE.get());
        helper.assertTrue(link != null, "ドロップがリンクを保持していない");
        helper.assertTrue(link != null && jukeboxAbs.equals(link.pos()),
                "ドロップのリンク先が音源と一致しない");
        helper.assertTrue(link != null && helper.getLevel().dimension().equals(link.dimension()),
                "ドロップのリンクの dimension が一致しない");
        helper.succeed();
    }

    /** 別 dimension を指すリンクは取り込まない (component は GlobalPos で dimension を持つ)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void speakerRejectsOtherDimension(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(1, 1, 1);
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final BlockPos jukeboxAbs = helper.absolutePos(jukeboxRel);
        final ServerLevel level = helper.getLevel();
        helper.assertFalse(level.dimension().equals(Level.NETHER),
                "テストが NETHER で走っている (前提が崩れている)");

        final BlockPos speakerRel = new BlockPos(4, 1, 4);
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get());
        final SpeakerBlockEntity be = helper.getBlockEntity(speakerRel);
        if (be == null) {
            helper.fail("SpeakerBlockEntity が生成されていない", speakerRel);
            return;
        }
        final ItemStack stack = new ItemStack(ModItems.SPEAKER.get());
        // 座標は同じで dimension だけ別 (座標一致の偶然でリンクしないことの確認)。
        stack.set(ModDataComponents.SPEAKER_SOURCE.get(), GlobalPos.of(Level.NETHER, jukeboxAbs));
        be.applyComponentsFromItemStack(stack);
        final BlockPos speakerAbs = helper.absolutePos(speakerRel);
        final BlockState state = level.getBlockState(speakerAbs);
        state.getBlock().setPlacedBy(level, speakerAbs, state, null, stack);

        helper.assertTrue(be.getSourcePos() == null, "別 dimension のリンクを取り込んでいる");
        helper.assertTrue(SpeakerNetwork.countFor(level, jukeboxAbs) == 0,
                "別 dimension のリンクが逆引き index に登録された");
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

    // ── 配送先 (payload の宛先集合) ───────────────────────────────────────
    // 「誰に届くか」は build でも通常の GameTest でも一切見えない面で、実際に穴が出た面でもある。
    // Services.NETWORK を捕獲実装に差し替えて宛先だけを固定する。

    /**
     * 配送先が「音源チャンク ∪ 全スピーカーチャンク」を覆い、かつ<b>同じ player には 1 通だけ</b>
     * であること。
     *
     * <p>音源チャンクだけに撃つと遠方スピーカーの傍にいる player に構造的に届かない。逆に
     * チャンクごとに撃つと、音源とスピーカーの両方を追跡している player (＝通常の配置) が同じ
     * payload を複数通受け取り、client は「停止を挟まない 2 連続の再生要求」を見る = 再生
     * インスタンスが二重に立つ。<b>観測量を最終受信者である player の粒度に置く</b>のがこの
     * テストの要点で、チャンク集合で数えている限りこの重複は構造的に見えない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void broadcastReachesSpeakerChunks(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(1, 1, 1);
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final BlockPos jukeboxAbs = helper.absolutePos(jukeboxRel);
        final GoldenJukeboxBlockEntity jukebox = helper.getBlockEntity(jukeboxRel);
        if (jukebox == null) {
            helper.fail("金ジュークの BlockEntity が生成されていない", jukeboxRel);
            return;
        }
        // 音源とは別チャンクになる位置へリンクさせる (構造物の外に直接置く)。
        final BlockPos farAbs = jukeboxAbs.offset(48, 0, 48);
        helper.getLevel().setBlockAndUpdate(farAbs, ModBlocks.SPEAKER.get().defaultBlockState());
        final SpeakerBlockEntity far = (SpeakerBlockEntity) helper.getLevel().getBlockEntity(farAbs);
        if (far == null) {
            helper.fail("遠方スピーカーの BlockEntity が生成されていない");
            return;
        }
        far.setSourcePos(jukeboxAbs);

        final CapturingNetwork net = new CapturingNetwork();
        // 「音源とスピーカーの両方を追跡している player」を作る。both は 2 チャンクを追跡している
        // ので、チャンク単位で撃つ実装だと 2 通受け取る。onlyFar は遠方スピーカーのチャンクだけ。
        final ServerPlayer both = helper.makeMockServerPlayerInLevel();
        final ServerPlayer onlyFar = helper.makeMockServerPlayerInLevel();
        net.watch(new ChunkPos(jukeboxAbs), both);
        net.watch(new ChunkPos(farAbs), both, onlyFar);

        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            jukebox.broadcastSpeakerSet();
            final Set<ChunkPos> targets = net.chunksOf(SpeakerSetPayload.class);
            helper.assertTrue(targets.contains(new ChunkPos(jukeboxAbs)),
                    "音源チャンクが宛先に入っていない: " + targets);
            helper.assertTrue(targets.contains(new ChunkPos(farAbs)),
                    "遠方スピーカーのチャンクが宛先に入っていない: " + targets);
            helper.assertTrue(targets.size() == 2, "宛先チャンクが 2 個でない: " + targets);

            final Map<ServerPlayer, Integer> counts = net.countPerPlayer(SpeakerSetPayload.class);
            helper.assertTrue(counts.size() == 2, "宛先 player が 2 人でない: " + counts.size());
            helper.assertTrue(counts.getOrDefault(both, 0) == 1,
                    "両方のチャンクを追跡している player への通数が 1 でない: " + counts.get(both));
            helper.assertTrue(counts.getOrDefault(onlyFar, 0) == 1,
                    "スピーカーチャンクだけの player への通数が 1 でない: " + counts.get(onlyFar));
        } finally {
            Services.swapNetwork(previous);
            helper.getLevel().setBlockAndUpdate(farAbs, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    /**
     * ミュート切り替え (集合の更新) では再生 packet を撒かないこと。
     * 撒くと、レッドストーンを叩くたびに周囲の client が再生要求を受ける。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void muteToggleDoesNotResendPlayback(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(1, 1, 1);
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final BlockPos jukeboxAbs = helper.absolutePos(jukeboxRel);

        final BlockPos speakerRel = new BlockPos(4, 1, 4);
        final SpeakerBlockEntity be = placeLinkedSpeaker(helper, speakerRel, jukeboxAbs);
        if (be == null) {
            return;
        }
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            helper.setBlock(speakerRel.east(), Blocks.REDSTONE_BLOCK);
            helper.assertTrue(be.isMuted(), "レッドストーン信号でミュートになっていない");
            helper.assertTrue(net.of(SpeakerSetPayload.class).size() > 0,
                    "ミュート切り替えで集合が再配布されていない");
            helper.assertTrue(net.of(PlayDiscPayload.class).isEmpty(),
                    "ミュート切り替えで再生 packet が撒かれている");
        } finally {
            Services.swapNetwork(previous);
        }
        helper.succeed();
    }

    /**
     * 指向性の切り替えが「音源チャンク ∪ 全スピーカーチャンク」へ届くこと。
     * BE 同期だけではスピーカー圏の listener（音源の chunk を持たない）に届かない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void directionalToggleReachesSpeakerChunks(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(1, 1, 1);
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final BlockPos jukeboxAbs = helper.absolutePos(jukeboxRel);
        final GoldenJukeboxBlockEntity jukebox = helper.getBlockEntity(jukeboxRel);
        if (jukebox == null) {
            helper.fail("金ジュークの BlockEntity が生成されていない", jukeboxRel);
            return;
        }
        final BlockPos farAbs = jukeboxAbs.offset(48, 0, 48);
        helper.getLevel().setBlockAndUpdate(farAbs, ModBlocks.SPEAKER.get().defaultBlockState());
        final SpeakerBlockEntity far = (SpeakerBlockEntity) helper.getLevel().getBlockEntity(farAbs);
        if (far == null) {
            helper.fail("遠方スピーカーの BlockEntity が生成されていない");
            return;
        }
        far.setSourcePos(jukeboxAbs);

        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            helper.assertTrue(jukebox.isDirectional(), "既定が ON でない (テストの前提が崩れている)");
            jukebox.setDirectional(false);
            final List<CapturingNetwork.Sent> sent = net.of(SpeakerSetPayload.class);
            helper.assertTrue(!sent.isEmpty(), "指向性の切り替えで聴取モデルが配送されていない");
            helper.assertFalse(((SpeakerSetPayload) sent.get(0).payload()).directional(),
                    "配送された指向性の値が反映されていない");
            final Set<ChunkPos> targets = net.chunksOf(SpeakerSetPayload.class);
            helper.assertTrue(targets.contains(new ChunkPos(jukeboxAbs)),
                    "音源チャンクへ届いていない: " + targets);
            helper.assertTrue(targets.contains(new ChunkPos(farAbs)),
                    "遠方スピーカーのチャンクへ届いていない: " + targets);
            // 同じ値への再設定は撃たない (毎フレームの GUI エコーで撒かないための早期 return)。
            net.clear();
            jukebox.setDirectional(false);
            helper.assertTrue(net.of(SpeakerSetPayload.class).isEmpty(),
                    "同じ値の再設定で無駄に配送している");
        } finally {
            Services.swapNetwork(previous);
            helper.getLevel().setBlockAndUpdate(farAbs, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    // ── 聴取点の選択則 (client の純関数) ─────────────────────────────────

    private static SpeakerSelection.Candidate at(double x, double z, int volume, int range) {
        return new SpeakerSelection.Candidate(BlockPos.containing(x, 0, z), new Vec3(x, 0, z), volume, range);
    }

    /**
     * 音源も自分の可聴範囲でゲートすること。
     *
     * <p>音源 range 64 / スピーカー range 128 で、音源の方が近いが音源の範囲外・スピーカーの範囲内、
     * という座標を作る。音源を無条件の候補にすると距離だけで音源が勝ち、その range が減衰半径に
     * 書かれて完全な無音になる (出荷 GUI のスライダーを 1 本上げるだけで作れる配置)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void selectionGatesSourceByItsOwnRange(GameTestHelper helper) {
        final SpeakerSelection.Candidate source =
                new SpeakerSelection.Candidate(null, new Vec3(0, 0, 0), 100, 64);
        final SpeakerSelection.Candidate speaker = at(100, 0, 100, 128);
        final Vec3 ear = new Vec3(0, 0, 70); // 音源まで 70 (範囲外) / スピーカーまで ≈122 (範囲内)

        final SpeakerSelection.Choice choice =
                SpeakerSelection.pick(ear, source, List.of(speaker), null);
        helper.assertTrue(choice.pos() != null, "音源の範囲外なのに音源が選ばれている (無音になる)");
        helper.assertTrue(choice.rangeBlocks() == 128, "選ばれた点の range がスピーカーのものでない");

        // 音源の範囲内に入れば、より近い音源が選ばれる (最近傍の規則は変わらない)。
        final SpeakerSelection.Choice near =
                SpeakerSelection.pick(new Vec3(0, 0, 20), source, List.of(speaker), null);
        helper.assertTrue(near.pos() == null, "音源の範囲内なのに音源が選ばれていない");
        helper.succeed();
    }

    /** 範囲内の候補が 1 つも無ければ音源へ縮退する (減衰で無音になるのが正しい)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void selectionFallsBackToSource(GameTestHelper helper) {
        final SpeakerSelection.Candidate source =
                new SpeakerSelection.Candidate(null, new Vec3(0, 0, 0), 100, 64);
        final SpeakerSelection.Candidate speaker = at(100, 0, 100, 32);
        final SpeakerSelection.Choice choice =
                SpeakerSelection.pick(new Vec3(0, 0, 500), source, List.of(speaker), null);
        helper.assertTrue(choice.pos() == null, "全候補が範囲外なのに音源へ縮退していない");
        helper.assertTrue(choice.rangeBlocks() == 64, "縮退先の range が音源のものでない");

        // バニラジューク経路 (range=0 の sentinel) も常に音源へ縮退する。
        final SpeakerSelection.Candidate vanilla =
                new SpeakerSelection.Candidate(null, new Vec3(0, 0, 0), 100, 0);
        final SpeakerSelection.Choice v = SpeakerSelection.pick(new Vec3(0, 0, 1), vanilla, List.of(), null);
        helper.assertTrue(v.pos() == null && v.rangeBlocks() == 0,
                "range=0 の sentinel が音源へ縮退していない");
        helper.succeed();
    }

    /**
     * <b>音源の可聴範囲の外</b>で全スピーカーをミュートしたら無音になること。
     *
     * <p>可聴の判定は「音源 ∪ 非ミュートスピーカー、それぞれの範囲の合併」であって、ミュートされた
     * スピーカーの範囲パッチは合併から除かれる。ミュート済みは server の {@code activeEntries} と
     * client の BE 直読の両方で候補から落ちるので、ここへは非ミュートだけが渡る = 候補ゼロになり
     * 音源へ縮退する。縮退先の range が listener を覆っていなければフラットモードの範囲ゲートが
     * 閉じて無音になる（指向性 ON なら距離減衰で 0）。
     *
     * <p>「音源の範囲の内側では、ミュートしても音源本体から同じ曲が聴こえ続ける」のは仕様
     * （ジュークボックス自体が鳴っている）。ミュートが効かないように見えるのはその範囲内の話。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void mutingAllSpeakersSilencesOutsideSourceRange(GameTestHelper helper) {
        final SpeakerSelection.Candidate source =
                new SpeakerSelection.Candidate(null, new Vec3(0, 0, 0), 100, 64);
        final SpeakerSelection.Candidate speaker = at(0, 200, 100, 64);
        final Vec3 ear = new Vec3(0, 0, 200); // 音源まで 200 (範囲外) / スピーカー直上 (範囲内)

        // ミュート前: スピーカーが選ばれ、その範囲が listener を覆う = 聴こえる。
        final SpeakerSelection.Choice live = SpeakerSelection.pick(ear, source, List.of(speaker), null);
        helper.assertTrue(speaker.pos().equals(live.pos()),
                "非ミュートのスピーカー圏内なのにスピーカーが選ばれていない");
        helper.assertTrue(ear.distanceTo(speaker.center()) <= live.rangeBlocks(),
                "テストの前提が崩れている (ミュート前から範囲外)");

        // ミュート後: 候補ゼロ → 音源へ縮退。縮退先の範囲は listener を覆わない = 無音。
        final SpeakerSelection.Choice muted =
                SpeakerSelection.pick(ear, source, List.of(), speaker.pos());
        helper.assertTrue(muted.pos() == null, "全ミュートで音源へ縮退していない");
        helper.assertTrue(muted.rangeBlocks() == 64, "縮退先の range が音源のものでない");
        helper.assertFalse(ear.distanceTo(new Vec3(0, 0, 0)) <= muted.rangeBlocks(),
                "ミュートされたスピーカーの範囲が可聴の合併に残っている (無音にならない)");

        // 直前の選択がミュートされたスピーカーでも、ヒステリシスで据え置かれないこと。
        helper.assertFalse(speaker.pos().equals(muted.pos()),
                "ミュートされたスピーカーが前回の選択として据え置かれている");
        helper.succeed();
    }

    /** 等距離付近で毎 tick 反転しないよう、前回の選択に切り替え余裕を与える。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void selectionKeepsCurrentWithinMargin(GameTestHelper helper) {
        final SpeakerSelection.Candidate source =
                new SpeakerSelection.Candidate(null, new Vec3(0, 0, 0), 100, 64);
        final SpeakerSelection.Candidate speaker = at(0, 40, 100, 64);
        // 音源まで 19.9 / スピーカーまで 20.1 = 差 0.2 < SWITCH_MARGIN(0.5) なので据え置き。
        final Vec3 ear = new Vec3(0, 0, 19.9);
        final SpeakerSelection.Choice keep =
                SpeakerSelection.pick(ear, source, List.of(speaker), speaker.pos());
        helper.assertTrue(speaker.pos().equals(keep.pos()), "余裕の内側で選択が反転している");

        // 余裕を超えて音源へ寄れば切り替わる (音源まで 5 / スピーカーまで 35)。
        final SpeakerSelection.Choice flip =
                SpeakerSelection.pick(new Vec3(0, 0, 5), source, List.of(speaker), speaker.pos());
        helper.assertTrue(flip.pos() == null, "余裕を超えても選択が切り替わらない");
        helper.succeed();
    }
}
