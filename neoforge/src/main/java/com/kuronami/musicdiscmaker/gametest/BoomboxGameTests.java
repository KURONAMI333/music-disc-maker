package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlock;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStopPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * ブームボックスと指向性トグルの server 側ロジックの headless テスト。
 * 装填・設置/回収の往復・指向性の永続化・手持ち再生のフラグ遷移を機械判定する。
 * 実音と聴取挙動は client 側なのでここでは扱わない (実機確認帯)。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class BoomboxGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    /** 再生可能な custom disc (silent song つき) を 1 枚作る。 */
    private static ItemStack customDisc(String url, long durationMs) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData(url, "test", "tester", durationMs, "", false));
        // バニラの「再生中」状態に乗せる (DiscFabrication と同じ形)。
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(durationMs)), false));
        return disc;
    }

    /** ディスク入りブームボックスのブロックアイテム。 */
    private static ItemStack loadedBoombox(int range, int volume, boolean directional) {
        final ItemStack stack = new ItemStack(ModItems.BOOMBOX.get());
        stack.set(ModDataComponents.BOOMBOX_CONTENTS.get(),
                new BoomboxContents(customDisc("https://example.invalid/a", 60_000L), range, volume, directional));
        return stack;
    }

    /** {@code BlockItem#place} と同じ順序 (ブロック配置 → component 適用 → setPlacedBy) を再現する。 */
    private static BoomboxBlockEntity place(GameTestHelper helper, BlockPos rel, ItemStack stack) {
        helper.setBlock(rel, ModBlocks.BOOMBOX.get());
        final ServerLevel level = helper.getLevel();
        final BlockPos abs = helper.absolutePos(rel);
        final BoomboxBlockEntity be = helper.getBlockEntity(rel);
        if (be == null) {
            helper.fail("BoomboxBlockEntity が生成されていない", rel);
            return null;
        }
        be.applyComponentsFromItemStack(stack);
        final BlockState state = level.getBlockState(abs);
        state.getBlock().setPlacedBy(level, abs, state, null, stack);
        return be;
    }

    /** 設置でアイテムの中身 (ディスク + 設定) が BE に入り、再生が始まる。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxPlaceLoadsContents(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(2, 1, 2);
        final BoomboxBlockEntity be = place(helper, rel, loadedBoombox(96, 70, false));
        if (be == null) {
            return;
        }
        helper.assertTrue(be.hasDisc(), "設置でディスクが入っていない");
        helper.assertTrue(be.getRangeBlocks() == 96, "可聴範囲が引き継がれていない: " + be.getRangeBlocks());
        helper.assertTrue(be.getVolumePercent() == 70, "音量が引き継がれていない: " + be.getVolumePercent());
        helper.assertFalse(be.isDirectional(), "指向性が引き継がれていない");
        // ディスクが入ると HAS_RECORD が立つ = ticker が付く (立たないと BE が一度も tick されない)。
        helper.assertTrue(
                helper.getLevel().getBlockState(helper.absolutePos(rel)).getValue(GoldenJukeboxBlock.HAS_RECORD),
                "HAS_RECORD が立っていない (ticker が付かない)");
        helper.assertTrue(be.currentTrack() != null, "custom track が読めていない");
        helper.succeed();
    }

    /** 指向性の既定はブロックごとに違う (ブームボックス = OFF / 強化版ジュークボックス = ON)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxDefaultsToFlatAudio(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.BOOMBOX.get());
        helper.setBlock(new BlockPos(4, 1, 4), ModBlocks.GOLDEN_JUKEBOX.get());
        final BoomboxBlockEntity boombox = helper.getBlockEntity(new BlockPos(1, 1, 1));
        final GoldenJukeboxBlockEntity jukebox = helper.getBlockEntity(new BlockPos(4, 1, 4));
        if (boombox == null || jukebox == null) {
            helper.fail("BlockEntity が生成されていない");
            return;
        }
        helper.assertFalse(boombox.isDirectional(), "ブームボックスの指向性が既定 OFF でない");
        helper.assertTrue(jukebox.isDirectional(), "強化版ジュークボックスの指向性が既定 ON でない");
        helper.succeed();
    }

    /** 指向性は NBT に残る (save → load の往復で保たれる)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void directionalSurvivesSaveLoad(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(3, 1, 3);
        helper.setBlock(rel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity be = helper.getBlockEntity(rel);
        if (be == null) {
            helper.fail("BlockEntity が生成されていない", rel);
            return;
        }
        be.setDirectional(false);
        final var registries = helper.getLevel().registryAccess();
        final var tag = be.saveWithFullMetadata(registries);

        // 同じ状態への setBlock は BE を作り直さないので、一度 AIR を挟んで確実に新しい BE にする。
        helper.setBlock(rel, net.minecraft.world.level.block.Blocks.AIR);
        helper.setBlock(rel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity reloaded = helper.getBlockEntity(rel);
        if (reloaded == null) {
            helper.fail("再生成した BlockEntity が取れない", rel);
            return;
        }
        helper.assertTrue(reloaded.isDirectional(), "テストの前提が崩れている (既定が ON でない)");
        reloaded.loadWithComponents(tag, registries);
        helper.assertFalse(reloaded.isDirectional(), "指向性が NBT から復元されていない");
        helper.succeed();
    }

    /** 破壊ドロップが中身 (ディスク + 設定) を保持し、ディスクが二重に落ちない。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxDropCarriesContentsOnce(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(2, 1, 5);
        final BoomboxBlockEntity be = place(helper, rel, loadedBoombox(80, 55, false));
        if (be == null) {
            return;
        }
        final BlockPos abs = helper.absolutePos(rel);
        final List<ItemStack> drops = Block.getDrops(
                helper.getLevel().getBlockState(abs), helper.getLevel(), abs, be);
        helper.assertTrue(drops.size() == 1, "ドロップが 1 個でない (ディスクが二重に落ちている?): " + drops.size());
        final BoomboxContents contents = drops.get(0).get(ModDataComponents.BOOMBOX_CONTENTS.get());
        helper.assertTrue(contents != null, "ドロップが中身を保持していない");
        helper.assertTrue(contents != null && contents.hasDisc(), "ドロップにディスクが入っていない");
        helper.assertTrue(contents != null && contents.rangeBlocks() == 80, "ドロップに可聴範囲が乗っていない");
        helper.assertTrue(contents != null && contents.volumePercent() == 55, "ドロップに音量が乗っていない");

        // 破壊経路でもディスクが単体アイテムとして撒かれないこと (onRemove の dropContents 抑止)。
        helper.getLevel().destroyBlock(abs, false);
        helper.assertTrue(helper.getLevel().getEntities(null,
                        net.minecraft.world.phys.AABB.ofSize(net.minecraft.world.phys.Vec3.atCenterOf(abs), 4, 4, 4))
                .stream().noneMatch(e -> e instanceof net.minecraft.world.entity.item.ItemEntity item
                        && item.getItem().is(ModItems.CUSTOM_MUSIC_DISC.get())),
                "破壊でディスクが単体ドロップしている (component と二重)");
        helper.succeed();
    }

    /** 手持ちトグル: ディスクなしでは鳴らない / 入っていればフラグが立ち、もう一度で降りる。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxHeldToggle(GameTestHelper helper) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            final ItemStack empty = new ItemStack(ModItems.BOOMBOX.get());
            helper.assertFalse(BoomboxPlayback.toggle(player, empty), "ディスクなしで再生を受け付けている");
            helper.assertFalse(BoomboxPlayback.isPlaying(empty), "ディスクなしで再生フラグが立っている");

            final ItemStack loaded = loadedBoombox(64, 100, false);
            helper.assertTrue(BoomboxPlayback.toggle(player, loaded), "ディスク入りで再生を受け付けない");
            helper.assertTrue(BoomboxPlayback.isPlaying(loaded), "再生フラグが立っていない");
            helper.assertTrue(BoomboxPlayback.toggle(player, loaded), "停止を受け付けない");
            helper.assertFalse(BoomboxPlayback.isPlaying(loaded), "停止で再生フラグが降りていない");

            // 停止は即時に撃つ。宛先は「追跡中の player + 本人」でなければ持ち主に届かない。
            final List<CapturingNetwork.Sent> stops = net.of(BoomboxStopPayload.class);
            helper.assertTrue(stops.size() == 1, "停止 packet が 1 回でない: " + stops.size());
            helper.assertTrue("entityAndSelf".equals(stops.get(0).kind()),
                    "停止 packet の宛先が entityAndSelf でない: " + stops.get(0).kind());
        } finally {
            Services.swapNetwork(previous);
        }
        helper.succeed();
    }

    /**
     * 手持ち再生は手に持っている間だけ。しまうと心拍がフラグを落とす。
     * あわせて再生 packet が「追跡中の player + 本人」へ行くことを固定する
     * (素の「追跡中の player」は本人を含まず、持ち主にだけ聞こえない状態になる)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxStopsWhenNotHeld(GameTestHelper helper) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            final ItemStack loaded = loadedBoombox(64, 100, false);
            player.setItemInHand(InteractionHand.MAIN_HAND, loaded);
            helper.assertTrue(BoomboxPlayback.toggle(player, loaded), "再生を受け付けない");
            BoomboxPlayback.heartbeat(player, loaded);
            helper.assertTrue(BoomboxPlayback.isPlaying(loaded), "手に持っているのに止まった");

            final List<CapturingNetwork.Sent> plays = net.of(BoomboxPlayPayload.class);
            helper.assertTrue(plays.size() == 1, "再生開始 packet が 1 回でない: " + plays.size());
            helper.assertTrue("entityAndSelf".equals(plays.get(0).kind()),
                    "再生 packet の宛先が entityAndSelf でない: " + plays.get(0).kind());
            helper.assertTrue(plays.get(0).payload() instanceof BoomboxPlayPayload p
                    && p.entityId() == player.getId(), "再生 packet の entityId が持ち主でない");
            helper.assertFalse(((BoomboxPlayPayload) plays.get(0).payload()).directional(),
                    "手持ち再生の指向性が OFF で送られていない");

            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            BoomboxPlayback.heartbeat(player, loaded);
            helper.assertFalse(BoomboxPlayback.isPlaying(loaded), "手から離しても再生フラグが残っている");
            helper.assertTrue(net.of(BoomboxStopPayload.class).size() == 1,
                    "手から離した時に停止 packet が出ていない");
        } finally {
            Services.swapNetwork(previous);
        }
        helper.succeed();
    }

    /**
     * <b>相互作用ブロックの面にシフト右クリックで設置できる</b>。以前はシフト右クリックが常に
     * 再生トグルへ食われていて、チェスト・かまど等の面にブームボックスを置けなかった。
     *
     * <p>シフト + アイテム所持の右クリックは vanilla がブロック相互作用をスキップして
     * {@code stack.useOn} に落とす = ここが唯一の設置経路なので、トグルに使うと設置手段が消える。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxPlacesOnInteractiveBlockFace(GameTestHelper helper) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        final INetworkHelper previous = Services.swapNetwork(new CapturingNetwork());
        try {
            final BlockPos chestRel = new BlockPos(2, 1, 2);
            helper.setBlock(chestRel, Blocks.CHEST);
            final BlockPos chestAbs = helper.absolutePos(chestRel);

            // 中身なしのブームボックスを持ってシフト中 (= 再生トグルが成立しうる状態)。
            final ItemStack stack = new ItemStack(ModItems.BOOMBOX.get());
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);

            final BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(chestAbs).add(0.0, 0.5, 0.0), Direction.UP, chestAbs, false);
            stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));

            helper.assertBlockPresent(ModBlocks.BOOMBOX.get(), chestRel.above());
            helper.assertFalse(BoomboxPlayback.isPlaying(stack),
                    "設置のはずが再生トグルに食われている");
        } finally {
            Services.swapNetwork(previous);
        }
        helper.succeed();
    }

    /** 空中 (ブロック非対象) のシフト右クリックは従来どおり再生トグル。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxTogglesOnAirClick(GameTestHelper helper) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        final INetworkHelper previous = Services.swapNetwork(new CapturingNetwork());
        try {
            final ItemStack loaded = loadedBoombox(64, 100, false);
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, loaded);

            loaded.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(BoomboxPlayback.isPlaying(loaded), "空中クリックで再生が始まらない");

            loaded.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertFalse(BoomboxPlayback.isPlaying(loaded), "空中クリックで停止できない");
        } finally {
            Services.swapNetwork(previous);
        }
        helper.succeed();
    }
}
