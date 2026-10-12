package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
//? if >=26.3 {
/*import net.minecraft.world.level.block.RedstoneWireBlock;
*///?} else {
import net.minecraft.world.level.block.RedStoneWireBlock;
//?}
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
//? if >=26.1 {
//?} else {
/*import net.minecraft.world.item.EitherHolder;
*///?}
//? if <1.21.11 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class GoldenRedstoneGameTests {
    private static final String TEMPLATE = "empty8x3x8";

    private GoldenRedstoneGameTests() { }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 100, batch = "goldenRedstoneSignals")
    *///?}
    public static void comparatorAndSignalsFollowUnifiedContract(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        final GoldenJukeboxBlockEntity golden = circuit.golden();
        final BlockPos comparatorPos = circuit.goldenPos().east();
        final BlockPos wirePos = comparatorPos.east();
        helper.setBlock(comparatorPos.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(wirePos.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(comparatorPos, Blocks.COMPARATOR.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
        helper.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState());
        golden.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc(helper, "signal", 120_000L, false));
        helper.startSequence()
                .thenWaitUntil(() -> assertComparatorState(helper, golden, wirePos, 15,
                        "再生中"))
                .thenExecute(() -> {
                    assertSignals(helper, golden, 0, "Golden");
                    golden.setPaused(true);
                })
                .thenWaitUntil(() -> assertComparatorState(helper, golden, wirePos, 1,
                        "pause中"))
                .thenExecute(golden::clearContent)
                .thenWaitUntil(() -> assertComparatorState(helper, golden, wirePos, 0,
                        "空"))
                .thenSucceed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = "goldenRedstonePower")
    *///?}
    public static void risingEdgeTogglesPlaybackAndReleaseDoesNotStop(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        final GoldenJukeboxBlockEntity golden = circuit.golden();
        final BlockPos input = circuit.goldenPos().above();
        golden.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc(helper, "pulse", 120_000L, false));
        tick(helper, golden);
        helper.assertTrue(!golden.isPaused(), "無給電の新盤が自動再生しない");

        golden.seekTo(30_000L);
        golden.setPaused(true);
        helper.setBlock(input, Blocks.REDSTONE_BLOCK.defaultBlockState());
        tick(helper, golden);
        helper.assertTrue(!golden.isPaused() && golden.currentElapsedMs() >= 30_000L,
                "PAUSEDへの立ち上がりで保持位置から再生しない");
        tick(helper, golden);
        helper.assertTrue(!golden.isPaused(), "HIGH保持で再びpauseした");
        helper.setBlock(input, Blocks.AIR.defaultBlockState());
        tick(helper, golden);
        helper.assertTrue(!golden.isPaused(), "ボタンreleaseで停止した");

        helper.setBlock(input, Blocks.REDSTONE_BLOCK.defaultBlockState());
        tick(helper, golden);
        helper.assertTrue(golden.isPaused(), "PLAYINGへの次の立ち上がりでpauseしない");
        helper.setBlock(input, Blocks.AIR.defaultBlockState());
        tick(helper, golden);
        helper.assertTrue(golden.isPaused(), "LOWへ戻しただけでresumeした");
        helper.setBlock(input, Blocks.REDSTONE_BLOCK.defaultBlockState());
        tick(helper, golden);
        helper.assertTrue(!golden.isPaused(), "PAUSEDへの次の立ち上がりでresumeしない");
        helper.assertTrue(helper.getBlockState(circuit.outputPos()).getValue(HopperBlock.ENABLED),
                "強入力がGoldenを介して下hopperをロックした");

        golden.setPaused(true);
        tick(helper, golden);
        helper.assertTrue(golden.isPaused(), "HIGH保持中の手動pauseが解除された");
        golden.setPaused(false);
        tick(helper, golden);
        helper.assertTrue(!golden.isPaused(), "HIGH保持中の手動resumeが阻止された");
        golden.clearContent();
        golden.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc(helper, "powered-insert", 120_000L, false));
        tick(helper, golden);
        helper.assertTrue(!golden.isPaused(), "HIGH保持中の新盤が自動再生しない");

        helper.setBlock(input, Blocks.AIR.defaultBlockState());
        tick(helper, golden);
        golden.clearContent();
        // The empty block has no ticker: power can change without a sample.
        helper.setBlock(input, Blocks.REDSTONE_BLOCK.defaultBlockState());
        golden.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc(helper, "empty-gap-insert", 120_000L, false));
        tick(helper, golden);
        helper.assertTrue(!golden.isPaused(), "空期間の給電変化を新盤への立ち上がりと誤認した");

        golden.clearContent();
        helper.setBlock(circuit.outputPos(), Blocks.AIR.defaultBlockState());
        helper.setBlock(input, Blocks.AIR.defaultBlockState());
        tick(helper, golden);
        golden.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc(helper, "terminal", 1_000L, false));
        // GameTest ticks can run faster than wall time; seek to the real track end explicitly.
        golden.seekTo(1_000L);
        tick(helper, golden);
        helper.assertTrue(golden.isStopped() && golden.isAutomationExtractionReady(),
                "有限曲が自然終端して搬出readyにならない");
        helper.setBlock(input, Blocks.REDSTONE_BLOCK.defaultBlockState());
        tick(helper, golden);
        helper.assertTrue(!golden.isStopped() && !golden.isAutomationExtractionReady()
                        && golden.currentElapsedMs() < 1_000L,
                "自然終端後の立ち上がりで先頭から再生しない");
        golden.seekTo(1_000L);
        tick(helper, golden);
        helper.assertTrue(golden.isStopped() && golden.isAutomationExtractionReady(),
                "HIGH保持中の自然終端が搬出readyにならない");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 12000, batch = "goldenRedstoneTop")
    *///?}
    public static void topInputMovesAToChestWhileBPlays(GameTestHelper helper) {
        twoDiscAutomation(helper, true);
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 12000, batch = "goldenRedstoneSide")
    *///?}
    public static void sideInputMovesAToChestWhileBPlays(GameTestHelper helper) {
        twoDiscAutomation(helper, false);
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = "goldenRedstonePauseHopper")
    *///?}
    public static void pauseStaysInRealOutputHopper(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        circuit.golden().setItem(0, disc(helper, "pause-held", 120_000L, false));
        circuit.golden().setPaused(true);
        final Container output = requireContainer(helper, circuit.outputPos(), "output hopper");
        final Container chest = requireContainer(helper, circuit.chestPos(), "chest");
        helper.startSequence()
                .thenExecuteAfter(20, () -> helper.assertTrue(circuit.golden().hasDisc()
                                && output.isEmpty() && chest.isEmpty()
                                && !circuit.golden().isAutomationExtractionReady(),
                        "manual pause盤を実hopperが20tick以内に搬出した"))
                .thenSucceed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 240, batch = "goldenRedstoneContinuous")
    *///?}
    public static void repeatRadioAndUnknownStayInserted(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        final Container output = requireContainer(helper, circuit.outputPos(), "output hopper");
        final Container chest = requireContainer(helper, circuit.chestPos(), "chest");
        final GoldenJukeboxBlockEntity golden = circuit.golden();
        golden.setRepeat(true);
        golden.setItem(0, disc(helper, "repeat", 1L, false));
        helper.startSequence()
                .thenExecuteAfter(20, () -> assertHeld(helper, golden, output, chest, "repeat有限曲"))
                .thenExecute(() -> {
                    golden.setRepeat(false);
                    golden.clearContent();
                    golden.setItem(0, disc(helper, "radio", 0L, true));
                })
                .thenExecuteAfter(20, () -> assertHeld(helper, golden, output, chest, "radio"))
                .thenExecute(() -> {
                    golden.clearContent();
                    golden.setItem(0, disc(helper, "unknown", 0L, false));
                })
                .thenExecuteAfter(20, () -> {
                    assertHeld(helper, golden, output, chest, "未知尺");
                    golden.setPaused(true);
                })
                .thenSucceed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 400, batch = "goldenRedstoneSequence")
    *///?}
    public static void mdmAlbumAdvancesWithoutExtraction(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        final Container output = requireContainer(helper, circuit.outputPos(), "output hopper");
        final Container chest = requireContainer(helper, circuit.chestPos(), "chest");
        final GoldenJukeboxBlockEntity golden = circuit.golden();
        golden.setItem(0, album(helper,
                disc(helper, "album-a", 1L, false),
                disc(helper, "album-b", 120_000L, false)));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(golden.currentTrack() != null
                                && "album-b".equals(golden.currentTrack().title()),
                        "MDM Albumの2盤目へ進まない"))
                .thenExecute(() -> {
                    assertHeld(helper, golden, output, chest, "MDM Album途中");
                    golden.setPaused(true);
                })
                .thenSucceed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 140, batch = "goldenRedstoneReload")
    *///?}
    public static void inputAndLegacyPauseSurviveReloadWithoutPhantomToggle(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        helper.setBlock(circuit.outputPos(), Blocks.AIR.defaultBlockState());
        final BlockPos input = circuit.goldenPos().above();
        helper.setBlock(input, Blocks.REDSTONE_BLOCK.defaultBlockState());
        circuit.golden().setItem(0, disc(helper, "powered-reload", 120_000L, false));
        tick(helper, circuit.golden());
        helper.assertTrue(!circuit.golden().isPaused(), "給電済み位置への新盤投入で再生しない");

        GoldenJukeboxBlockEntity restored = reload(helper, circuit.golden());
        tick(helper, restored);
        helper.assertTrue(!restored.isPaused(), "HIGH状態のreloadでphantom pauseした");
        helper.setBlock(input, Blocks.AIR.defaultBlockState());
        tick(helper, restored);
        helper.assertTrue(!restored.isPaused(), "reload後のLOWで再生が止まった");
        helper.setBlock(input, Blocks.REDSTONE_BLOCK.defaultBlockState());
        tick(helper, restored);
        helper.assertTrue(restored.isPaused(), "reload後の真の立ち上がりでpauseしない");
        final long heldOffset = restored.currentElapsedMs();

        restored = reload(helper, restored);
        tick(helper, restored);
        helper.assertTrue(restored.isPaused(), "PAUSEDのHIGH reloadでphantom resumeした");
        helper.setBlock(input, Blocks.AIR.defaultBlockState());
        tick(helper, restored);
        helper.assertTrue(restored.isPaused(), "PAUSEDのLOWで勝手にresumeした");
        helper.setBlock(input, Blocks.REDSTONE_BLOCK.defaultBlockState());
        tick(helper, restored);
        helper.assertTrue(!restored.isPaused() && restored.currentElapsedMs() >= heldOffset,
                "reload後の新しい立ち上がりで保持位置からresumeしない");

        restored.setPaused(true);
        final long legacyOffset = restored.currentElapsedMs();
        final CompoundTag legacySaved = restored.saveWithFullMetadata(helper.getLevel().registryAccess());
        legacySaved.putBoolean("redstonePaused", true);
        restored = reload(helper, restored, legacySaved);
        tick(helper, restored);
        helper.assertTrue(restored.isPaused() && restored.currentElapsedMs() >= legacyOffset,
                "旧redstonePausedデータのPAUSED位置を失った");
        helper.assertTrue(!restored.saveWithFullMetadata(helper.getLevel().registryAccess())
                        .contains("redstonePaused"),
                "廃止したredstonePausedを再保存した");
        helper.setBlock(input, Blocks.AIR.defaultBlockState());
        tick(helper, restored);
        helper.assertTrue(restored.isPaused(), "旧保存のLOWで勝手にresumeした");
        helper.setBlock(input, Blocks.REDSTONE_BLOCK.defaultBlockState());
        tick(helper, restored);
        helper.assertTrue(!restored.isPaused() && restored.currentElapsedMs() >= legacyOffset,
                "旧保存のpauseを次の立ち上がりでresumeできない");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 180, batch = "goldenRedstoneBackpressure")
    *///?}
    public static void fullOutputHopperReleasesFinishedDiscExactlyOnce(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        final Container output = requireContainer(helper, circuit.outputPos(), "output hopper");
        helper.setBlock(circuit.chestPos(), Blocks.STONE.defaultBlockState());
        for (int slot = 0; slot < output.getContainerSize(); slot++) {
            output.setItem(slot, new ItemStack(Blocks.COBBLESTONE, 64));
        }
        circuit.golden().setItem(0, disc(helper, "backpressure", 1L, false));

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(circuit.golden().isStopped(),
                        "有限曲が搬出可能なSTOPPEDへ到達しない"))
                .thenExecuteAfter(20, () -> {
                    helper.assertTrue(circuit.golden().hasDisc(),
                            "満杯の下hopperへ終了盤を消失させた");
                    helper.assertTrue(countTitle(output, "backpressure") == 0,
                            "満杯の下hopperへ終了盤を重複挿入した");
                    output.setItem(0, ItemStack.EMPTY);
                })
                .thenWaitUntil(() -> helper.assertTrue(!circuit.golden().hasDisc()
                                && countTitle(output, "backpressure") == 1,
                        "空きを解放しても終了盤を1枚だけ搬出できない"))
                .thenExecuteAfter(20, () -> helper.assertTrue(!circuit.golden().hasDisc()
                                && countTitle(output, "backpressure") == 1,
                        "終了盤の搬出がexactly-onceでない"))
                .thenSucceed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 100, batch = "goldenRedstoneLegacyStopped")
    *///?}
    public static void restoredStoppedStateIsNotNaturalCompletion(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        final Container output = requireContainer(helper, circuit.outputPos(), "output hopper");
        final Container chest = requireContainer(helper, circuit.chestPos(), "chest");
        circuit.golden().setItem(0, disc(helper, "restored-stopped", 120_000L, false));
        final CompoundTag saved = circuit.golden().saveWithFullMetadata(helper.getLevel().registryAccess());
        saved.putString("playbackState", "STOPPED");
        saved.putLong("playbackStartGameTime", -1L);
        saved.remove("automationFinished");
        circuit.golden().onBlockRemoved();
        final GoldenJukeboxBlockEntity restored = (GoldenJukeboxBlockEntity) BlockEntity.loadStatic(
                circuit.golden().getBlockPos(), circuit.golden().getBlockState(), saved,
                helper.getLevel().registryAccess());
        helper.assertTrue(restored != null, "旧STOPPED fixtureを復元できない");
        restored.setLevel(helper.getLevel());
        helper.getLevel().setBlockEntity(restored);
        helper.startSequence()
                .thenExecuteAfter(20, () -> helper.assertTrue(restored.hasDisc()
                                && output.isEmpty() && chest.isEmpty()
                                && !restored.isAutomationExtractionReady(),
                        "自然終端の証拠がないSTOPPED媒体を搬出した"))
                .thenSucceed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = "goldenRedstoneFinishedReload")
    *///?}
    public static void naturalCompletionSurvivesReloadAndExtracts(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        helper.setBlock(circuit.outputPos(), Blocks.AIR.defaultBlockState());
        helper.setBlock(circuit.goldenPos().above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        circuit.golden().setItem(0, disc(helper, "finished-reload", 1L, false));
        final GoldenJukeboxBlockEntity[] restored = {null};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(circuit.golden().isAutomationExtractionReady(),
                        "自然終端が搬出readyへ到達しない"))
                .thenExecute(() -> {
                    restored[0] = reload(helper, circuit.golden());
                    helper.assertTrue(restored[0].isAutomationExtractionReady(),
                            "自然終端の搬出readyがreloadで失われた");
                    helper.setBlock(circuit.outputPos(), Blocks.HOPPER.defaultBlockState()
                            .setValue(HopperBlock.FACING, Direction.EAST));
                })
                .thenWaitUntil(() -> helper.assertTrue(!restored[0].hasDisc(),
                        "reload後の自然終端盤を下hopperへ搬出できない"))
                .thenSucceed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 500, batch = "goldenRedstoneVanillaEnd")
    *///?}
    public static void vanillaJukeboxSongNaturallyEndsAndExtracts(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        final Container output = requireContainer(helper, circuit.outputPos(), "output hopper");
        final Container chest = requireContainer(helper, circuit.chestPos(), "chest");
        circuit.golden().setRepeat(true);
        circuit.golden().setItem(0, vanillaDisc(helper));
        helper.assertTrue(circuit.golden().currentTrack() == null,
                "vanilla fixtureがCustomTrackData経路へ入った");
        helper.assertTrue(circuit.golden().isVanillaPlaying(),
                "正式JukeboxSong fixtureが再生を開始しない");

        helper.startSequence()
                .thenExecuteAfter(220, () -> {
                    helper.assertTrue(circuit.golden().hasDisc()
                                    && circuit.golden().playbackCursor().state()
                                    == com.kuronami.musicdiscmaker.component.PlaybackCursor.State.PLAYING
                                    && !circuit.golden().isAutomationExtractionReady()
                                    && output.isEmpty() && chest.isEmpty(),
                            "repeat中のvanilla盤を最初のJukeboxSong終端で停止・搬出した");
                    circuit.golden().setRepeat(false);
                })
                .thenWaitUntil(() -> helper.assertTrue(circuit.golden().isStopped(),
                        "有限vanilla JukeboxSongが自然終端でSTOPPEDへ到達しない"))
                .thenWaitUntil(() -> helper.assertTrue(!circuit.golden().hasDisc()
                                && countItem(output, Items.MUSIC_DISC_13) + countItem(chest, Items.MUSIC_DISC_13) == 1,
                        "自然終端したvanilla盤を下hopperへ1枚だけ搬出できない"))
                .thenExecuteAfter(20, () -> helper.assertTrue(!circuit.golden().hasDisc()
                                && countItem(output, Items.MUSIC_DISC_13) + countItem(chest, Items.MUSIC_DISC_13) == 1,
                        "vanilla盤の自然終端搬出がexactly-onceでない"))
                .thenSucceed();
    }

    private static void twoDiscAutomation(GameTestHelper helper, boolean topInput) {
        final Circuit circuit = circuit(helper);
        final BlockPos inputPos = topInput ? circuit.goldenPos().above() : circuit.goldenPos().west();
        helper.setBlock(inputPos, topInput ? Blocks.HOPPER.defaultBlockState()
                : Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        final HopperBlockEntity input = requireHopper(helper, inputPos);
        final Container chest = requireContainer(helper, circuit.chestPos(), "chest");
        input.setItem(0, disc(helper, "A-" + (topInput ? "top" : "side"), 25L, false));
        input.setItem(1, disc(helper, "B-" + (topInput ? "top" : "side"), 120_000L, false));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(circuit.golden().currentTrack() != null
                                && circuit.golden().currentTrack().title().startsWith("B-")
                                && circuit.golden().isRedstonePlaying()
                                && countTitle(chest, "A-") == 1,
                        "A搬出後にBを再生しつつAをchestへ送れない"))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getBlockState(inputPos).getValue(HopperBlock.ENABLED),
                            "Automationの入力hopperがGoldenにロックされた");
                    helper.assertTrue(helper.getBlockState(circuit.outputPos()).getValue(HopperBlock.ENABLED),
                            "Automationの出力hopperがGoldenにロックされた");
                    circuit.golden().setPaused(true);
                })
                .thenSucceed();
    }

    private static Circuit circuit(GameTestHelper helper) {
        final BlockPos goldenPos = new BlockPos(3, 1, 3);
        final BlockPos outputPos = goldenPos.below();
        final BlockPos chestPos = outputPos.east();
        helper.setBlock(goldenPos, ModBlocks.GOLDEN_JUKEBOX.get());
        helper.setBlock(outputPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        helper.setBlock(chestPos, Blocks.CHEST.defaultBlockState());
        final GoldenJukeboxBlockEntity golden = requireGolden(helper, goldenPos);
        return new Circuit(goldenPos, outputPos, chestPos, golden);
    }

    private static void assertHeld(GameTestHelper helper, GoldenJukeboxBlockEntity golden,
            Container output, Container chest, String label) {
        helper.assertTrue(golden.hasDisc() && golden.isRedstonePlaying()
                        && output.isEmpty() && chest.isEmpty(),
                label + "で媒体が停止・搬出された");
    }

    private static void assertComparatorState(GameTestHelper helper, GoldenJukeboxBlockEntity golden,
            BlockPos wirePos, int expected, String label) {
        helper.assertTrue(golden.getComparatorOutput() == expected,
                label + "のcomparator値が" + expected + "でない");
        //? if >=26.3 {
        /*helper.assertTrue(helper.getBlockState(wirePos).getValue(RedstoneWireBlock.POWER) == expected,
        *///?} else {
        helper.assertTrue(helper.getBlockState(wirePos).getValue(RedStoneWireBlock.POWER) == expected,
        //?}
                "物理comparatorが" + label + "の値" + expected + "を出さない");
    }

    private static void assertSignals(GameTestHelper helper, GoldenJukeboxBlockEntity golden,
            int expectedWeak, String label) {
        for (Direction direction : Direction.values()) {
            helper.assertTrue(helper.getLevel().getSignal(golden.getBlockPos(), direction) == expectedWeak,
                    label + "の通常信号が" + expectedWeak + "でない: " + direction);
            helper.assertTrue(helper.getLevel().getDirectSignal(golden.getBlockPos(), direction) == 0,
                    label + "がdirect signalを出した: " + direction);
        }
    }

    private static GoldenJukeboxBlockEntity reload(GameTestHelper helper, GoldenJukeboxBlockEntity golden) {
        return reload(helper, golden, golden.saveWithFullMetadata(helper.getLevel().registryAccess()));
    }

    private static GoldenJukeboxBlockEntity reload(GameTestHelper helper, GoldenJukeboxBlockEntity golden,
            CompoundTag saved) {
        golden.onBlockRemoved();
        final GoldenJukeboxBlockEntity restored = (GoldenJukeboxBlockEntity) BlockEntity.loadStatic(
                golden.getBlockPos(), golden.getBlockState(), saved, helper.getLevel().registryAccess());
        helper.assertTrue(restored != null, "Golden automation stateをreloadできない");
        restored.setLevel(helper.getLevel());
        helper.getLevel().setBlockEntity(restored);
        return restored;
    }

    private static GoldenJukeboxBlockEntity requireGolden(GameTestHelper helper, BlockPos pos) {
        final var be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (!(be instanceof GoldenJukeboxBlockEntity golden)) {
            throw new AssertionError("Golden BEがない: " + pos);
        }
        return golden;
    }

    private static HopperBlockEntity requireHopper(GameTestHelper helper, BlockPos pos) {
        final var be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (!(be instanceof HopperBlockEntity hopper)) {
            throw new AssertionError("hopper BEがない: " + pos);
        }
        return hopper;
    }

    private static Container requireContainer(GameTestHelper helper, BlockPos pos, String name) {
        final var be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (!(be instanceof Container container)) throw new AssertionError(name + " containerがない");
        return container;
    }

    private static void tick(GameTestHelper helper, GoldenJukeboxBlockEntity golden) {
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), golden.getBlockPos(), golden.getBlockState(), golden);
    }

    private static int countTitle(Container container, String prefix) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            final CustomTrackData track = container.getItem(i).get(ModDataComponents.CUSTOM_TRACK.get());
            if (track != null && track.title().startsWith(prefix)) count += container.getItem(i).getCount();
        }
        return count;
    }

    private static int countItem(Container container, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) count += container.getItem(i).getCount();
        }
        return count;
    }

    private static CustomTrackData track(String title, long durationMs, boolean radio) {
        return new CustomTrackData("https://example.invalid/redstone/" + title, title,
                "tester", durationMs, "", radio);
    }

    private static ItemStack disc(GameTestHelper helper, String title, long durationMs, boolean radio) {
        final CustomTrackData track = track(title, durationMs, radio);
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), track);
        //? if <1.21.11 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(durationMs)), false));
        *///?} else {
        final var song = helper.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                .getOrThrow(SilentSongs.pick(durationMs));
        //? if >=26.1 {
        disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(song));
        //?} else {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song)));
        *///?}
        //?}
        return disc;
    }

    private static ItemStack vanillaDisc(GameTestHelper helper) {
        final ItemStack disc = new ItemStack(Items.MUSIC_DISC_13);
        final var song = helper.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                .getOrThrow(SilentSongs.pick(1L));
        //? if >=26.1 {
        disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(song));
        //?} elif >=1.21.11 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song)));
        *///?} else {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song), false));
        *///?}
        return disc;
    }

    private static ItemStack album(GameTestHelper helper, ItemStack... discs) {
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(discs)));
        return album;
    }

    private record Circuit(BlockPos goldenPos, BlockPos outputPos, BlockPos chestPos,
            GoldenJukeboxBlockEntity golden) { }
}
