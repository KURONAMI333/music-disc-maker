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
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
//? if >=26.1 {
//?} else {
/*import net.minecraft.world.item.EitherHolder;
*///?}
//? if <1.21.2 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class GoldenRedstoneGameTests {
    private static final String TEMPLATE = "empty8x3x8";

    private GoldenRedstoneGameTests() { }

    //? if <1.21.2 {
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

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = "goldenRedstonePower")
    *///?}
    public static void powerOwnsOnlyItsPauseAndDoesNotLockTheHopper(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        final GoldenJukeboxBlockEntity golden = circuit.golden();
        golden.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc(helper, "power", 120_000L, false));

        golden.seekTo(30_000L);
        golden.setPaused(true);
        helper.setBlock(circuit.goldenPos().above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        tick(helper, golden);
        helper.setBlock(circuit.goldenPos().above(), Blocks.AIR.defaultBlockState());
        tick(helper, golden);
        helper.assertTrue(golden.isPaused(), "手動pauseを給電解除が勝手にresumeした");

        helper.setBlock(circuit.goldenPos().above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        final long heldOffset = golden.currentElapsedMs();
        golden.setPaused(false);
        helper.assertTrue(golden.isPaused() && Math.abs(golden.currentElapsedMs() - heldOffset) <= 50L,
                "給電中の手動resume要求がpause位置を失った");
        helper.setBlock(circuit.goldenPos().above(), Blocks.AIR.defaultBlockState());
        tick(helper, golden);
        helper.assertTrue(!golden.isPaused() && golden.currentElapsedMs() >= heldOffset,
                "給電解除で保持位置からresumeしない");

        helper.setBlock(circuit.goldenPos().above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        tick(helper, golden);
        helper.assertTrue(golden.isPaused(), "給電highが再生をpauseしない");
        helper.assertTrue(helper.getBlockState(circuit.outputPos()).getValue(HopperBlock.ENABLED),
                "強入力がAutomation Goldenを介して下hopperをロックした");
        helper.setBlock(circuit.goldenPos().above(), Blocks.AIR.defaultBlockState());
        tick(helper, golden);
        helper.assertTrue(!golden.isPaused() && golden.playbackCursor().state()
                        == com.kuronami.musicdiscmaker.component.PlaybackCursor.State.PLAYING,
                "給電が所有したpauseをlowでresumeしない");

        golden.clearContent();
        helper.setBlock(circuit.goldenPos().above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        golden.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc(helper, "powered-insert", 120_000L, false));
        tick(helper, golden);
        helper.assertTrue(golden.isPaused() && golden.currentElapsedMs() == 0L,
                "給電中に投入した盤を0秒で保持していない");

        helper.setBlock(circuit.goldenPos().above(), Blocks.AIR.defaultBlockState());
        tick(helper, golden);
        golden.clearContent();
        helper.setBlock(circuit.outputPos(), Blocks.AIR.defaultBlockState());
        golden.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc(helper, "terminal", 1L, false));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(golden.isStopped(), "有限曲がSTOPPEDへ到達しない"))
                .thenExecute(() -> {
                    helper.setBlock(circuit.goldenPos().above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    tick(helper, golden);
                    helper.assertTrue(golden.isStopped() && !golden.isAutomationExtractionReady(),
                            "STOPPED後の給電で停止を変えたか給電中の搬出を許した");
                })
                .thenExecute(() -> {
                    helper.setBlock(circuit.goldenPos().above(), Blocks.AIR.defaultBlockState());
                    tick(helper, golden);
                    helper.assertTrue(golden.isStopped() && golden.isAutomationExtractionReady(),
                            "STOPPED後の給電解除で勝手に再生したか搬出readyへ戻らない");
                })
                .thenSucceed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 600, batch = "goldenRedstoneTop")
    *///?}
    public static void topInputMovesAToChestWhileBPlays(GameTestHelper helper) {
        twoDiscAutomation(helper, true);
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 600, batch = "goldenRedstoneSide")
    *///?}
    public static void sideInputMovesAToChestWhileBPlays(GameTestHelper helper) {
        twoDiscAutomation(helper, false);
    }

    //? if <1.21.2 {
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

    //? if <1.21.2 {
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

    //? if <1.21.2 {
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

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 140, batch = "goldenRedstoneReload")
    *///?}
    public static void redstoneAndManualPauseOwnershipSurviveReload(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        helper.setBlock(circuit.outputPos(), Blocks.AIR.defaultBlockState());
        helper.setBlock(circuit.goldenPos().above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        circuit.golden().setItem(0, disc(helper, "owned-reload", 120_000L, false));
        tick(helper, circuit.golden());
        GoldenJukeboxBlockEntity restored = reload(helper, circuit.golden());
        tick(helper, restored);
        helper.setBlock(circuit.goldenPos().above(), Blocks.AIR.defaultBlockState());
        tick(helper, restored);
        helper.assertTrue(!restored.isPaused() && restored.hasDisc(),
                "保存したredstone-owned pauseをlowでresumeしない");

        helper.setBlock(circuit.goldenPos().above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        tick(helper, restored);
        helper.assertTrue(restored.isPaused(), "reload後のhighでpauseしない");
        restored.setPaused(true);
        restored = reload(helper, restored);
        tick(helper, restored);
        helper.setBlock(circuit.goldenPos().above(), Blocks.AIR.defaultBlockState());
        tick(helper, restored);
        helper.assertTrue(restored.isPaused() && restored.hasDisc(),
                "手動へ移管し保存したpauseをlowが勝手にresumeした");
        helper.succeed();
    }

    //? if <1.21.2 {
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

    //? if <1.21.2 {
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

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = "goldenRedstoneFinishedReload")
    *///?}
    public static void naturalCompletionSurvivesReloadAndExtracts(GameTestHelper helper) {
        final Circuit circuit = circuit(helper);
        helper.setBlock(circuit.outputPos(), Blocks.AIR.defaultBlockState());
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

    //? if <1.21.2 {
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
        helper.assertTrue(helper.getBlockState(wirePos).getValue(RedStoneWireBlock.POWER) == expected,
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
        final CompoundTag saved = golden.saveWithFullMetadata(helper.getLevel().registryAccess());
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
        //? if <1.21.2 {
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
        //?} elif >=1.21.2 {
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
