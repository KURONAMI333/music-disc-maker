package com.kuronami.musicdiscmaker.gametest;

//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.compat.create.CreateAudioMovementBehaviour;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mdm_create_test")
public final class CreateIntegrationGameTests {
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void registeredActorPersistsOffsetBeforeStopping(GameTestHelper helper) {
        helper.assertTrue(net.neoforged.fml.ModList.get().isLoaded("create"), "Create integration run did not load Create");
        LoadedCreate.run(helper, false);
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void vanillaActorPersistsOffsetBeforeStopping(GameTestHelper helper) {
        helper.assertTrue(net.neoforged.fml.ModList.get().isLoaded("create"), "Create integration run did not load Create");
        LoadedCreate.run(helper, true);
    }

    // Keep Create type verification out of automatic test discovery without the optional mod.
    private static final class LoadedCreate {
    static void run(GameTestHelper helper, boolean vanilla) {
        final var level = helper.getLevel();
        final var block = ModBlocks.GOLDEN_JUKEBOX.get();
        final var behavior = MovementBehaviour.REGISTRY.get(block.defaultBlockState());
        helper.assertTrue(behavior instanceof CreateAudioMovementBehaviour, "Golden actor was not registered with Create");
        helper.setBlock(new BlockPos(1, 1, 1), block);
        final var source = (GoldenJukeboxBlockEntity) level.getBlockEntity(helper.absolutePos(new BlockPos(1, 1, 1)));
        final var disc = new ItemStack(vanilla ? net.minecraft.world.item.Items.MUSIC_DISC_CAT : ModItems.CUSTOM_MUSIC_DISC.get());
        if (!vanilla) disc.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData(
                "https://example.invalid/create", "Create", "test", 120000L, "", false));
        source.setItem(0, disc);
        final var data = source.saveWithFullMetadata(level.registryAccess());
        data.putString("playbackState", "PLAYING");
        data.putLong("playbackStartGameTime", level.getGameTime() - 100L);
        final var captured = CapturedGoldenPlayback.read(level, BlockPos.ZERO, data).orElseThrow();
        final var contraption = new BearingContraption(false, net.minecraft.core.Direction.UP);
        final var context = new MovementContext(level,
                new StructureBlockInfo(BlockPos.ZERO, block.defaultBlockState(), data), contraption);
        behavior.startMoving(context);
        helper.assertTrue(com.kuronami.musicdiscmaker.event.GoldenSourceRegistry.lookup(level, captured.sourceId()).status()
                        == com.kuronami.musicdiscmaker.event.GoldenSourceRegistry.Status.CONFLICT,
                "Captured actor did not reserve the identity alongside the still-present world fixture");
        behavior.tick(context); // Before entity spawn: must defer without losing data.
        helper.assertTrue(context.temporaryData == null, "Pre-spawn actor started playback");
        helper.setBlock(new BlockPos(3, 1, 1), ModBlocks.SPEAKER.get());
        final var speaker = (com.kuronami.musicdiscmaker.block.SpeakerBlockEntity)
                level.getBlockEntity(helper.absolutePos(new BlockPos(3, 1, 1)));
        speaker.linkTo(level, source.getBlockPos());
        source.setRemoved(); // Simulate removal of the captured world BE without destroying the fixture data.
        final var receivers = com.kuronami.musicdiscmaker.event.SpeakerPlayback.movingSpeakers(level, captured.sourceId(), context);
        helper.assertTrue(receivers.size() == 1 && receivers.get(0).pos().equals(speaker.getBlockPos()),
                "Captured actor did not retain its linked fixed speaker");
        final var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "stationary_contraption"))
                .orElseThrow(() -> new IllegalStateException("Create stationary contraption type was not registered"));
        final var entity = new ControlledContraptionEntity(type, level);
        contraption.entity = entity;
        final long clockBeforeStart = audioTime(level);
        behavior.tick(context);
        helper.assertTrue(context.temporaryData != null, "Live actor did not initialize playback state");
        helper.runAfterDelay(5, () -> {
            final long expectedOffset = captured.offsetMs() + audioTime(level) - clockBeforeStart;
            source.clearRemoved();
            behavior.stopMoving(context);
            helper.assertTrue(com.kuronami.musicdiscmaker.event.GoldenSourceRegistry.lookup(level, captured.sourceId()).status()
                            == com.kuronami.musicdiscmaker.event.GoldenSourceRegistry.Status.UNIQUE,
                    "Stopped actor did not release its identity reservation");
            final var restored = CapturedGoldenPlayback.read(level, BlockPos.ZERO, data).orElseThrow();
            helper.assertTrue(restored.sourceId().equals(captured.sourceId())
                    && restored.cursor().equals(captured.cursor()), "Stop changed source identity or selected track");
            helper.assertTrue(Math.abs(restored.offsetMs() - expectedOffset) <= 100L,
                    "Stop did not save audio-clock offset: expected near " + expectedOffset + ", got " + restored.offsetMs());
            final var saved = data.copy();
            behavior.stopMoving(context);
            helper.assertTrue(saved.equals(data), "Repeated stop changed the saved playback position");
            helper.succeed();
        });
    }
    private static long audioTime(net.minecraft.server.level.ServerLevel level) {
        return level.getServer() instanceof com.kuronami.musicdiscmaker.component.PlaybackClockAccess clock
                ? clock.mdm$playbackTimeMs()
                : com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock.realTimeMs();
    }
    }
}
*///?}
