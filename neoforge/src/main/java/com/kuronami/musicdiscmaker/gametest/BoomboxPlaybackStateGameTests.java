package com.kuronami.musicdiscmaker.gametest;

import java.util.List;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStopPayload;
import com.kuronami.musicdiscmaker.network.ControlBoomboxPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
*///?}

/** component 側の媒体差し替えが古い GUI 操作を失効させることを帯実体で固定する。 */
public final class BoomboxPlaybackStateGameTests {
    private static final BlockPos POS = new BlockPos(3, 1, 3);
    private BoomboxPlaybackStateGameTests() {
    }

    //? if <1.21.2 {
    /*@GameTest(template = "empty8x3x8")
    *///?}
    public static void replacingMediaClearsPositionWithoutResettingGeneration(GameTestHelper helper) {
        final PlaybackCursor playing = PlaybackCursor.initial().startAt(0, 0);
        final BoomboxContents before = new BoomboxContents(new ItemStack(Items.MUSIC_DISC_13), 74L,
                playing, 12_345L, true, true, 55L, 0, 0, 63);
        final BoomboxContents after = before.withDisc(new ItemStack(Items.MUSIC_DISC_CAT));
        helper.assertTrue(after.cursor().state() == PlaybackCursor.State.STOPPED
                        && !after.cursor().hasPosition()
                        && after.cursor().generation() > before.cursor().generation(),
                "媒体差し替えが位置を消すか generation を進めていない");
        helper.assertTrue(after.pausedOffsetMs() == 0L && !after.shuffle()
                        && after.shuffleAnchorDisc() == PlaybackCursor.NO_INDEX
                        && after.volumePercent() == 63,
                "媒体差し替えが pause/shuffle を初期化せず、機体設定まで失った");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = "empty8x3x8")
    *///?}
    public static void sessionlessPlayingStateDoesNotOfferImplicitResume(GameTestHelper helper) {
        final PlaybackCursor playing = PlaybackCursor.initial().startAt(0, 0);
        final long id = 987L;
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        boombox.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(
                new ItemStack(Items.MUSIC_DISC_13), id, playing, 0L, false, false, 0L,
                PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX, 100));
        helper.assertTrue(BoomboxPlayback.stateOf(boombox).cursor().state() == PlaybackCursor.State.STOPPED
                        && BoomboxPlayback.stateOf(boombox).cursor().generation() == playing.generation(),
                "Session無しのPLAYING componentが表示上も勝手に再開されている");
        final var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        BoomboxPlayback.nextCarried(player, boombox, playing.generation());
        helper.assertTrue(!BoomboxPlayback.isPlaying(id)
                        && BoomboxPlayback.stateOf(boombox).cursor().state() == PlaybackCursor.State.STOPPED,
                "Session無しのNEXTが意図せず再生を開始した");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void carriedPlacedStaleLifecycleKeepsCursorAndNeverAutoRestarts(GameTestHelper helper) {
        final CustomTrackData first = new CustomTrackData("https://example.invalid/same", "first", "test",
                120_000L, "", false);
        final CustomTrackData second = new CustomTrackData(first.url(), "second", "test", 120_000L, "", false);
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), first);

        final ItemStack other = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        other.set(ModDataComponents.CUSTOM_TRACK.get(), second);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(disc, other)));
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get());
        box.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(album, BoomboxContents.mintId()));
        final var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "boombox-lifecycle"));
        final BlockPos playerPos = helper.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(playerPos.getX() + 0.5, playerPos.getY() + 0.5, playerPos.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, box);
        final long id = box.get(ModDataComponents.BOOMBOX_CONTENTS.get()).id();
        try {
            helper.assertTrue(BoomboxPlayback.startCarried(player, box), "carried start failed");
            long generation = BoomboxPlayback.stateOf(box).cursor().generation();
            helper.assertTrue(BoomboxPlayback.pauseCarried(player, box, generation), "pause failed");
            generation = BoomboxPlayback.stateOf(box).cursor().generation();
            helper.assertTrue(BoomboxPlayback.seekCarried(player, box, 5_000L, generation), "paused seek failed");
            helper.assertTrue(BoomboxPlayback.startCarried(player, box), "resume failed");
            final long now = System.currentTimeMillis();
            BoomboxPlayback.sweep(helper.getLevel().getServer(), now);
            helper.assertTrue(BoomboxPlayback.isPlaying(id), "seek/resume session was swept as stale");

            helper.setBlock(POS, ModBlocks.BOOMBOX.get());
            final BoomboxBlockEntity be = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(POS));
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            be.setStored(box);
            BoomboxPlayback.servePlaced(helper.getLevel(), be.getBlockPos(), be);
            helper.assertTrue(BoomboxPlayback.isPlaying(id) && BoomboxPlayback.stateOf(be.getStored()).elapsedMs() >= 5_000L,
                    "placed transition lost id or paused offset");

            helper.getLevel().destroyBlock(be.getBlockPos(), true, player);
            final List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(be.getBlockPos()).inflate(1.0),
                    entity -> entity.getItem().is(ModItems.BOOMBOX.get()));
            helper.assertTrue(drops.size() == 1, "Boombox removal did not drop exactly one stored machine");
            final ItemStack carried = drops.get(0).getItem().copy();
            drops.get(0).discard();
            player.setItemInHand(InteractionHand.MAIN_HAND, carried);
            BoomboxPlayback.scan(List.of(player), now + 1L);
            helper.assertTrue(!BoomboxPlayback.isPlaying(id)
                            && com.kuronami.musicdiscmaker.event.BoomboxCarry.idOf(carried) == id,
                    "block removal did not stop the placed session or changed the recovered machine id");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            BoomboxPlayback.sweep(helper.getLevel().getServer(), now + BoomboxPlayback.STALE_MS + 3L);
            BoomboxPlayback.scan(List.of(player), now + BoomboxPlayback.STALE_MS + 4L);
            helper.assertFalse(BoomboxPlayback.isPlaying(id), "stale removal or post-stale scan restarted playback");
            helper.succeed();
        } finally {
            BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), id);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    //? if <1.21.2 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void creativePlacementTransfersPlaybackAndLeavesIndependentPausedCopy(GameTestHelper helper) {
        final ItemStack box = sequenceBox("creative-placement", 120_000L, true);
        final BoomboxContents configured = box.get(ModDataComponents.BOOMBOX_CONTENTS.get())
                .withShuffle(true, 77L, 0, 0).withVolumePercent(37);
        box.set(ModDataComponents.BOOMBOX_CONTENTS.get(), configured);
        final ServerPlayer player = networkedPlayer(helper);
        player.setGameMode(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND, box);
        final long originalId = configured.id();

        withCapturedNetwork(net -> {
            helper.assertTrue(BoomboxPlayback.startCarried(player, box), "creative placement fixture start failed");
            long generation = BoomboxPlayback.stateOf(box).cursor().generation();
            helper.assertTrue(BoomboxPlayback.pauseCarried(player, box, generation), "fixture pause failed");
            generation = BoomboxPlayback.stateOf(box).cursor().generation();
            helper.assertTrue(BoomboxPlayback.seekCarried(player, box, 5_000L, generation), "fixture seek failed");
            helper.assertTrue(BoomboxPlayback.startCarried(player, box), "fixture resume failed");
            final var beforePlacement = BoomboxPlayback.stateOf(box);

            final BlockPos placedPos = placeHeldBoombox(helper, player, GameType.CREATIVE);
            final BoomboxBlockEntity placed = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(placedPos);
            helper.assertTrue(placed != null, "creative useOn did not create a Boombox block entity");
            final ItemStack held = player.getMainHandItem();
            final ItemStack stored = placed.getStored();
            final BoomboxContents heldContents = held.get(ModDataComponents.BOOMBOX_CONTENTS.get());
            final BoomboxContents placedContents = stored.get(ModDataComponents.BOOMBOX_CONTENTS.get());
            final var heldAfterPlacement = BoomboxPlayback.stateOf(held);
            final var placedAfterPlacement = BoomboxPlayback.stateOf(stored);

            // The real failure leaves the creative copy paused while the placed copy still says PLAYING.
            // Normalize the old implementation to that state through the same menu payload a player uses.
            if (heldAfterPlacement.cursor().state() == PlaybackCursor.State.PLAYING) {
                final BoomboxMenu heldMenu = new BoomboxMenu(90, player.getInventory(),
                        BoomboxSource.held(InteractionHand.MAIN_HAND));
                player.containerMenu = heldMenu;
                ModNetwork.handleControlBoombox(new ControlBoomboxPayload(90,
                        heldAfterPlacement.cursor().generation(), ControlBoomboxPayload.PAUSE, 0L), player);
            }
            final var heldPaused = BoomboxPlayback.stateOf(held);
            final BoomboxMenu menu = new BoomboxMenu(91, player.getInventory(), BoomboxSource.placed(placedPos));
            player.containerMenu = menu;
            var placedState = BoomboxPlayback.stateOf(stored);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(91,
                    placedState.cursor().generation(), ControlBoomboxPayload.PLAY, 0L), player);
            net.clear();
            BoomboxPlayback.scan(List.of(player), System.currentTimeMillis());
            BoomboxPlayback.servePlaced(helper.getLevel(), placedPos, placed);
            final boolean heldScanStoppedPlacedSession = net.of(BoomboxStopPayload.class).stream()
                    .map(sent -> (BoomboxStopPayload) sent.payload())
                    .anyMatch(payload -> payload.boomboxId() == originalId);

            placedState = BoomboxPlayback.stateOf(stored);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(91,
                    placedState.cursor().generation(), ControlBoomboxPayload.PAUSE, 0L), player);
            placedState = BoomboxPlayback.stateOf(stored);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(91,
                    placedState.cursor().generation(), ControlBoomboxPayload.SEEK, 7_500L), player);
            placedState = BoomboxPlayback.stateOf(stored);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(91,
                    placedState.cursor().generation(), ControlBoomboxPayload.PLAY, 0L), player);
            final var resumedPlaced = BoomboxPlayback.stateOf(stored);
            net.clear();
            BoomboxPlayback.scan(List.of(player), System.currentTimeMillis());
            BoomboxPlayback.servePlaced(helper.getLevel(), placedPos, placed);
            final List<BoomboxPlayPayload> plays = net.of(BoomboxPlayPayload.class).stream()
                    .map(sent -> (BoomboxPlayPayload) sent.payload()).toList();
            final boolean carriedClaimedPlacedId = plays.stream()
                    .anyMatch(payload -> payload.boomboxId() == originalId && payload.isCarried());

            helper.assertTrue(placedContents.id() == originalId && heldContents.id() != originalId,
                    "creative useOn left held and placed Boomboxes on one id: "
                            + heldContents.id() + "/" + placedContents.id());
            helper.assertTrue(ItemStack.matches(placedContents.disc(), heldContents.disc())
                            && placedContents.repeat() == heldContents.repeat()
                            && placedContents.shuffle() == heldContents.shuffle()
                            && placedContents.shuffleSeed() == heldContents.shuffleSeed()
                            && placedContents.volumePercent() == heldContents.volumePercent(),
                    "creative placement lost medium or machine settings");
            helper.assertTrue(placedAfterPlacement.cursor().state() == PlaybackCursor.State.PLAYING
                            && placedAfterPlacement.elapsedMs() >= beforePlacement.elapsedMs(),
                    "placed original did not inherit active playback and offset");
            helper.assertTrue(heldPaused.cursor().state() == PlaybackCursor.State.PAUSED
                            && Math.abs(heldPaused.elapsedMs() - beforePlacement.elapsedMs()) <= 250L,
                    "held creative copy did not preserve the current position as paused");
            final var heldAfterControls = BoomboxPlayback.stateOf(held);
            helper.assertTrue(heldAfterControls.equals(heldPaused),
                    "placed pause/seek/play mutated the held creative copy");
            helper.assertTrue(resumedPlaced.cursor().state() == PlaybackCursor.State.PLAYING
                            && resumedPlaced.elapsedMs() >= 7_500L
                            && BoomboxPlayback.isPlaying(originalId)
                            && !BoomboxPlayback.isPlaying(heldContents.id()),
                    "placed controls did not resume the independent original at the requested offset");
            helper.assertFalse(heldScanStoppedPlacedSession,
                    "paused held copy stopped the placed machine's shared session");
            helper.assertFalse(carriedClaimedPlacedId,
                    "held creative copy reclaimed the placed session as a carried audio source");
        }, () -> {
            BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), originalId);
            final long heldId = com.kuronami.musicdiscmaker.event.BoomboxCarry.idOf(player.getMainHandItem());
            if (heldId != BoomboxContents.UNASSIGNED) {
                BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), heldId);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.discard();
        });
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void survivalPlacementKeepsThePlayingMachineAndConsumesTheItem(GameTestHelper helper) {
        final ItemStack box = sequenceBox("survival-placement", 120_000L, false);
        final ServerPlayer player = networkedPlayer(helper);
        player.setGameMode(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, box);
        final long originalId = box.get(ModDataComponents.BOOMBOX_CONTENTS.get()).id();
        withCapturedNetwork(net -> {
            helper.assertTrue(BoomboxPlayback.startCarried(player, box), "survival placement fixture start failed");
            long generation = BoomboxPlayback.stateOf(box).cursor().generation();
            helper.assertTrue(BoomboxPlayback.pauseCarried(player, box, generation), "fixture pause failed");
            generation = BoomboxPlayback.stateOf(box).cursor().generation();
            helper.assertTrue(BoomboxPlayback.seekCarried(player, box, 5_000L, generation), "fixture seek failed");
            helper.assertTrue(BoomboxPlayback.startCarried(player, box), "fixture resume failed");
            final var beforePlacement = BoomboxPlayback.stateOf(box);

            final BlockPos placedPos = placeHeldBoombox(helper, player, GameType.SURVIVAL);
            final BoomboxBlockEntity placed = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(placedPos);
            helper.assertTrue(placed != null && player.getMainHandItem().isEmpty(),
                    "survival useOn did not consume exactly the placed item");
            final BoomboxContents placedContents = placed.getStored().get(ModDataComponents.BOOMBOX_CONTENTS.get());
            BoomboxPlayback.servePlaced(helper.getLevel(), placedPos, placed);
            final var afterPlacement = BoomboxPlayback.stateOf(placed.getStored());
            helper.assertTrue(placedContents.id() == originalId && BoomboxPlayback.isPlaying(originalId),
                    "survival placement did not transfer the active machine id/session");
            helper.assertTrue(afterPlacement.cursor().state() == PlaybackCursor.State.PLAYING
                            && afterPlacement.elapsedMs() >= beforePlacement.elapsedMs()
                            && afterPlacement.elapsedMs() < beforePlacement.elapsedMs() + 1_500L,
                    "survival placement lost or restarted the playback offset");
        }, () -> {
            BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), originalId);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.discard();
        });
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void existingPlacedAndPausedCarriedCollisionSelfHeals(GameTestHelper helper) {
        final ItemStack fixture = sequenceBox("existing-collision", 120_000L, false);
        final ItemStack medium = fixture.get(ModDataComponents.BOOMBOX_CONTENTS.get()).disc();
        final long sharedId = 4_204_204L;
        final ItemStack placedStack = new ItemStack(ModItems.BOOMBOX.get());
        placedStack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(medium, sharedId,
                new PlaybackCursor(0, 0, PlaybackCursor.State.PLAYING, 48L), 0L,
                false, false, 0L, PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX, 4));
        final ItemStack heldStack = new ItemStack(ModItems.BOOMBOX.get());
        heldStack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(medium, sharedId,
                new PlaybackCursor(0, 0, PlaybackCursor.State.PAUSED, 42L), 8_873L,
                false, false, 0L, PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX, 4));
        helper.setBlock(POS, ModBlocks.BOOMBOX.get());
        final BlockPos placedPos = helper.absolutePos(POS);
        final BoomboxBlockEntity placed = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(placedPos);
        placed.setStored(placedStack);
        final ServerPlayer player = networkedPlayer(helper);
        player.setPos(placedPos.getX() + 0.5, placedPos.getY() + 0.5, placedPos.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, heldStack);

        withCapturedNetwork(net -> {
            final BoomboxMenu menu = new BoomboxMenu(92, player.getInventory(), BoomboxSource.placed(placedPos));
            player.containerMenu = menu;
            final long stoppedViewGeneration = BoomboxPlayback.stateOf(placedStack).cursor().generation();
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(92, stoppedViewGeneration,
                    ControlBoomboxPayload.PLAY, 0L), player);
            helper.assertTrue(BoomboxPlayback.isPlaying(sharedId), "placed PLAY did not create a recovery session");
            net.clear();
            BoomboxPlayback.scan(List.of(player), System.currentTimeMillis());
            BoomboxPlayback.servePlaced(helper.getLevel(), placedPos, placed);
            final boolean firstScanStoppedPlaced = net.of(BoomboxStopPayload.class).stream()
                    .map(sent -> (BoomboxStopPayload) sent.payload())
                    .anyMatch(payload -> payload.boomboxId() == sharedId);
            helper.assertTrue(BoomboxPlayback.isPlaying(sharedId) && !firstScanStoppedPlaced,
                    "paused held duplicate stopped the recovered placed session during inventory scan");
            helper.assertTrue(heldStack.get(ModDataComponents.BOOMBOX_CONTENTS.get()).id() != sharedId,
                    "existing paused held duplicate was not split from the placed id");

            var placedState = BoomboxPlayback.stateOf(placedStack);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(92, placedState.cursor().generation(),
                    ControlBoomboxPayload.PAUSE, 0L), player);
            placedState = BoomboxPlayback.stateOf(placedStack);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(92, placedState.cursor().generation(),
                    ControlBoomboxPayload.SEEK, 7_500L), player);
            placedState = BoomboxPlayback.stateOf(placedStack);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(92, placedState.cursor().generation(),
                    ControlBoomboxPayload.PLAY, 0L), player);
            net.clear();
            BoomboxPlayback.scan(List.of(player), System.currentTimeMillis());

            final BoomboxContents healedHeld = heldStack.get(ModDataComponents.BOOMBOX_CONTENTS.get());
            final var finalPlaced = BoomboxPlayback.stateOf(placedStack);
            helper.assertTrue(healedHeld.id() != sharedId
                            && healedHeld.cursor().state() == PlaybackCursor.State.PAUSED
                            && healedHeld.pausedOffsetMs() == 8_873L,
                    "existing paused held duplicate was not split from the placed id");
            helper.assertTrue(placedStack.get(ModDataComponents.BOOMBOX_CONTENTS.get()).id() == sharedId
                            && finalPlaced.cursor().state() == PlaybackCursor.State.PLAYING
                            && finalPlaced.elapsedMs() >= 7_500L && BoomboxPlayback.isPlaying(sharedId),
                    "existing collision stopped the placed machine again after normal controls");
            helper.assertFalse(net.of(BoomboxStopPayload.class).stream()
                            .map(sent -> (BoomboxStopPayload) sent.payload())
                            .anyMatch(payload -> payload.boomboxId() == sharedId),
                    "existing paused duplicate stopped the recovered placed session");
        }, () -> {
            BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), sharedId);
            final long heldId = com.kuronami.musicdiscmaker.event.BoomboxCarry.idOf(heldStack);
            if (heldId != BoomboxContents.UNASSIGNED) {
                BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), heldId);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.discard();
        });
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void existingPlacedDuplicatesBecomeIndependentBeforeFirstControl(GameTestHelper helper) {
        final ItemStack fixture = sequenceBox("placed-collision", 120_000L, false);
        final ItemStack medium = fixture.get(ModDataComponents.BOOMBOX_CONTENTS.get()).disc();
        final long sharedId = 5_205_205L;
        final ItemStack firstStack = new ItemStack(ModItems.BOOMBOX.get());
        firstStack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(medium, sharedId,
                new PlaybackCursor(0, 0, PlaybackCursor.State.PLAYING, 48L), 0L,
                false, false, 0L, PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX, 4));
        final ItemStack duplicateStack = new ItemStack(ModItems.BOOMBOX.get());
        duplicateStack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(medium, sharedId,
                new PlaybackCursor(0, 0, PlaybackCursor.State.PAUSED, 42L), 8_873L,
                false, false, 0L, PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX, 4));
        final BlockPos firstPos = helper.absolutePos(POS);
        final BlockPos duplicatePos = helper.absolutePos(POS.offset(2, 0, 0));
        helper.setBlock(POS, ModBlocks.BOOMBOX.get());
        helper.setBlock(POS.offset(2, 0, 0), ModBlocks.BOOMBOX.get());
        final BoomboxBlockEntity first = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(firstPos);
        final BoomboxBlockEntity duplicate = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(duplicatePos);
        first.setStored(firstStack);
        duplicate.setStored(duplicateStack);
        final ServerPlayer player = networkedPlayer(helper);
        player.setPos(duplicatePos.getX() + 0.5, duplicatePos.getY() + 0.5, duplicatePos.getZ() + 0.5);

        withCapturedNetwork(net -> {
            final BoomboxMenu firstMenu = new BoomboxMenu(93, player.getInventory(), BoomboxSource.placed(firstPos));
            player.containerMenu = firstMenu;
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(93,
                    BoomboxPlayback.stateOf(firstStack).cursor().generation(),
                    ControlBoomboxPayload.PLAY, 0L), player);
            helper.assertTrue(BoomboxPlayback.isPlaying(sharedId), "first placed machine did not start");

            // B has not ticked yet. Its first PLAY must split it before begin() can replace A's session.
            final BoomboxMenu duplicateMenu = new BoomboxMenu(94, player.getInventory(),
                    BoomboxSource.placed(duplicatePos));
            player.containerMenu = duplicateMenu;
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(94,
                    BoomboxPlayback.stateOf(duplicateStack).cursor().generation(),
                    ControlBoomboxPayload.PLAY, 0L), player);
            final long duplicateId = duplicateStack.get(ModDataComponents.BOOMBOX_CONTENTS.get()).id();
            helper.assertTrue(duplicateId != sharedId && BoomboxPlayback.isPlaying(sharedId)
                            && BoomboxPlayback.isPlaying(duplicateId),
                    "second placed PLAY replaced the first machine's shared-id session");

            var state = BoomboxPlayback.stateOf(duplicateStack);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(94, state.cursor().generation(),
                    ControlBoomboxPayload.PAUSE, 0L), player);
            helper.assertTrue(BoomboxPlayback.isPlaying(sharedId) && !BoomboxPlayback.isPlaying(duplicateId),
                    "pausing the repaired duplicate stopped the first placed machine");
            state = BoomboxPlayback.stateOf(duplicateStack);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(94, state.cursor().generation(),
                    ControlBoomboxPayload.SEEK, 7_500L), player);
            state = BoomboxPlayback.stateOf(duplicateStack);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(94, state.cursor().generation(),
                    ControlBoomboxPayload.PLAY, 0L), player);

            player.containerMenu = firstMenu;
            state = BoomboxPlayback.stateOf(firstStack);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(93, state.cursor().generation(),
                    ControlBoomboxPayload.PAUSE, 0L), player);
            helper.assertTrue(!BoomboxPlayback.isPlaying(sharedId) && BoomboxPlayback.isPlaying(duplicateId),
                    "pausing the first placed machine stopped the repaired duplicate");
            state = BoomboxPlayback.stateOf(firstStack);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(93, state.cursor().generation(),
                    ControlBoomboxPayload.SEEK, 6_000L), player);
            state = BoomboxPlayback.stateOf(firstStack);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(93, state.cursor().generation(),
                    ControlBoomboxPayload.PLAY, 0L), player);
            helper.assertTrue(BoomboxPlayback.isPlaying(sharedId) && BoomboxPlayback.isPlaying(duplicateId)
                            && BoomboxPlayback.stateOf(firstStack).elapsedMs() >= 6_000L
                            && BoomboxPlayback.stateOf(duplicateStack).elapsedMs() >= 7_500L,
                    "repaired placed machines did not keep independent seek/play state");
        }, () -> {
            BoomboxPlayback.stop(helper.getLevel(), BoomboxPlayback.chunkOf(firstPos), sharedId);
            final long duplicateId = com.kuronami.musicdiscmaker.event.BoomboxCarry.idOf(duplicateStack);
            if (duplicateId != BoomboxContents.UNASSIGNED) {
                BoomboxPlayback.stop(helper.getLevel(), BoomboxPlayback.chunkOf(duplicatePos), duplicateId);
            }
            player.discard();
        });
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void removingUnclaimedPlacedDuplicateDoesNotStopOwner(GameTestHelper helper) {
        final ItemStack fixture = sequenceBox("placed-removal-collision", 120_000L, false);
        final ItemStack medium = fixture.get(ModDataComponents.BOOMBOX_CONTENTS.get()).disc();
        final long sharedId = 6_206_206L;
        final ItemStack firstStack = new ItemStack(ModItems.BOOMBOX.get());
        firstStack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(medium, sharedId,
                new PlaybackCursor(0, 0, PlaybackCursor.State.PLAYING, 48L), 0L,
                false, false, 0L, PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX, 4));
        final ItemStack duplicateStack = firstStack.copy();
        final BlockPos firstPos = helper.absolutePos(POS);
        final BlockPos duplicatePos = helper.absolutePos(POS.offset(2, 0, 0));
        helper.setBlock(POS, ModBlocks.BOOMBOX.get());
        helper.setBlock(POS.offset(2, 0, 0), ModBlocks.BOOMBOX.get());
        final BoomboxBlockEntity first = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(firstPos);
        final BoomboxBlockEntity duplicate = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(duplicatePos);
        first.setStored(firstStack);
        duplicate.setStored(duplicateStack);
        final ServerPlayer player = networkedPlayer(helper);
        player.setPos(duplicatePos.getX() + 0.5, duplicatePos.getY() + 0.5, duplicatePos.getZ() + 0.5);

        withCapturedNetwork(net -> {
            helper.assertTrue(BoomboxPlayback.startPlaced(helper.getLevel(), firstPos, first),
                    "first placed removal fixture did not start");
            helper.assertTrue(helper.getLevel().destroyBlock(duplicatePos, true, player),
                    "unclaimed duplicate could not be removed");
            final List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(duplicatePos).inflate(1.0),
                    entity -> entity.getItem().is(ModItems.BOOMBOX.get()));
            helper.assertTrue(drops.size() == 1, "duplicate removal did not drop exactly one stored machine");
            final long droppedId = drops.get(0).getItem().get(ModDataComponents.BOOMBOX_CONTENTS.get()).id();
            helper.assertTrue(droppedId != sharedId && BoomboxPlayback.isPlaying(sharedId),
                    "removing an unclaimed duplicate stopped the active placed owner");
            drops.get(0).discard();
        }, () -> {
            BoomboxPlayback.stop(helper.getLevel(), BoomboxPlayback.chunkOf(firstPos), sharedId);
            player.discard();
        });
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void tailGraceDelaysSequenceAdvance(GameTestHelper helper) {
        final CustomTrackData first = new CustomTrackData("https://example.invalid/short-a", "first", "test",
                100L, "", false);
        final CustomTrackData second = new CustomTrackData("https://example.invalid/short-b", "second", "test",
                100L, "", false);
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), first);

        final ItemStack other = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        other.set(ModDataComponents.CUSTOM_TRACK.get(), second);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(disc, other)));
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get());
        box.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(album, BoomboxContents.mintId()));
        final var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "boombox-tail-grace"));
        final BlockPos playerPos = helper.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(playerPos.getX() + 0.5, playerPos.getY() + 0.5, playerPos.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, box);
        final long id = box.get(ModDataComponents.BOOMBOX_CONTENTS.get()).id();
        try {
            helper.assertTrue(BoomboxPlayback.startCarried(player, box), "short sequence start failed");
            final long now = System.currentTimeMillis();
            BoomboxPlayback.scan(List.of(player), now + 100L);
            helper.assertTrue(BoomboxPlayback.stateOf(box).cursor().discIndex() == 0,
                    "duration直後に末尾猶予を待たず送っている");
            BoomboxPlayback.scan(List.of(player), now + 100L + BoomboxPlayback.TAIL_GRACE_MS + 1L);
            helper.assertTrue(BoomboxPlayback.stateOf(box).cursor().discIndex() == 1,
                    "末尾猶予後に次曲へ送らない");
            helper.succeed();
        } finally {
            BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), id);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    //? if <1.21.2 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void manualNavigationWrapsBothDirectionsAndPreservesPause(GameTestHelper helper) {
        final ItemStack box = sequenceBox("manual-wrap", 120_000L, false);
        final ServerPlayer player = networkedPlayer(helper);
        final long id = box.get(ModDataComponents.BOOMBOX_CONTENTS.get()).id();
        withCapturedNetwork(net -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, box);
            helper.assertTrue(BoomboxPlayback.startCarried(player, box), "manual wrap fixture start failed");
            long generation = BoomboxPlayback.stateOf(box).cursor().generation();
            helper.assertTrue(BoomboxPlayback.previousCarried(player, box, generation),
                    "manual previous at head was rejected");
            var state = BoomboxPlayback.stateOf(box);
            helper.assertTrue(state.cursor().discIndex() == 1
                            && state.cursor().state() == PlaybackCursor.State.PLAYING && !state.repeat(),
                    "manual previous did not wrap head to tail while playing with repeat off");

            generation = state.cursor().generation();
            helper.assertTrue(BoomboxPlayback.nextCarried(player, box, generation),
                    "manual next at tail was rejected");
            state = BoomboxPlayback.stateOf(box);
            helper.assertTrue(state.cursor().discIndex() == 0
                            && state.cursor().state() == PlaybackCursor.State.PLAYING && !state.repeat(),
                    "manual next did not wrap tail to head while playing with repeat off");

            generation = state.cursor().generation();
            helper.assertTrue(BoomboxPlayback.pauseCarried(player, box, generation), "manual wrap pause failed");
            state = BoomboxPlayback.stateOf(box);
            generation = state.cursor().generation();
            helper.assertTrue(BoomboxPlayback.previousCarried(player, box, generation),
                    "paused previous at head was rejected");
            state = BoomboxPlayback.stateOf(box);
            helper.assertTrue(state.cursor().discIndex() == 1
                            && state.cursor().state() == PlaybackCursor.State.PAUSED && !state.repeat(),
                    "paused previous did not wrap to tail or changed pause/repeat state");

            generation = state.cursor().generation();
            helper.assertTrue(BoomboxPlayback.nextCarried(player, box, generation),
                    "paused next at tail was rejected");
            state = BoomboxPlayback.stateOf(box);
            helper.assertTrue(state.cursor().discIndex() == 0
                            && state.cursor().state() == PlaybackCursor.State.PAUSED && !state.repeat(),
                    "paused next did not wrap to head or changed pause/repeat state");
        }, () -> cleanupPlayer(helper, player, id));
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void naturalTailStillUsesRepeatSetting(GameTestHelper helper) {
        final ItemStack noRepeat = sequenceBox("natural-stop", 100L, false);
        final ItemStack repeat = sequenceBox("natural-repeat", 100L, true);
        final ServerPlayer player = networkedPlayer(helper);
        final long noRepeatId = noRepeat.get(ModDataComponents.BOOMBOX_CONTENTS.get()).id();
        final long repeatId = repeat.get(ModDataComponents.BOOMBOX_CONTENTS.get()).id();
        withCapturedNetwork(net -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, noRepeat);
            helper.assertTrue(BoomboxPlayback.startCarried(player, noRepeat), "non-repeat fixture start failed");
            long generation = BoomboxPlayback.stateOf(noRepeat).cursor().generation();
            helper.assertTrue(BoomboxPlayback.previousCarried(player, noRepeat, generation),
                    "non-repeat fixture could not select tail manually");
            long afterTailStart = System.currentTimeMillis();
            BoomboxPlayback.scan(List.of(player), afterTailStart + 100L + BoomboxPlayback.TAIL_GRACE_MS + 1L);
            var state = BoomboxPlayback.stateOf(noRepeat);
            helper.assertTrue(state.cursor().state() == PlaybackCursor.State.STOPPED
                            && state.cursor().discIndex() == 0 && !state.repeat(),
                    "natural tail with repeat off did not stop at the sequence head");

            player.setItemInHand(InteractionHand.MAIN_HAND, repeat);
            helper.assertTrue(BoomboxPlayback.startCarried(player, repeat), "repeat fixture start failed");
            generation = BoomboxPlayback.stateOf(repeat).cursor().generation();
            helper.assertTrue(BoomboxPlayback.previousCarried(player, repeat, generation),
                    "repeat fixture could not select tail manually");
            afterTailStart = System.currentTimeMillis();
            BoomboxPlayback.scan(List.of(player), afterTailStart + 100L + BoomboxPlayback.TAIL_GRACE_MS + 1L);
            state = BoomboxPlayback.stateOf(repeat);
            helper.assertTrue(state.cursor().state() == PlaybackCursor.State.PLAYING
                            && state.cursor().discIndex() == 0 && state.repeat(),
                    "natural tail with repeat on did not wrap and keep playing");
        }, () -> cleanupPlayer(helper, player, noRepeatId, repeatId));
        helper.succeed();
    }

    private static ItemStack sequenceBox(String prefix, long durationMs, boolean repeat) {
        final CustomTrackData first = new CustomTrackData("https://example.invalid/" + prefix + "-a",
                "first", "test", durationMs, "", false);
        final CustomTrackData second = new CustomTrackData("https://example.invalid/" + prefix + "-b",
                "second", "test", durationMs, "", false);
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), first);

        final ItemStack other = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        other.set(ModDataComponents.CUSTOM_TRACK.get(), second);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(disc, other)));
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get());
        box.set(ModDataComponents.BOOMBOX_CONTENTS.get(),
                new BoomboxContents(album, BoomboxContents.mintId()).withRepeat(repeat));
        return box;
    }

    private static BlockPos placeHeldBoombox(GameTestHelper helper, ServerPlayer player, GameType gameType) {
        player.setGameMode(gameType);
        player.setShiftKeyDown(true);
        final BlockPos support = new BlockPos(gameType == GameType.CREATIVE ? 5 : 2, 0, 5);
        helper.setBlock(support, Blocks.STONE);
        final BlockPos absoluteSupport = helper.absolutePos(support);
        player.setPos(absoluteSupport.getX() + 0.5, absoluteSupport.getY() + 1.0,
                absoluteSupport.getZ() + 0.5);
        final BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absoluteSupport), Direction.UP,
                absoluteSupport, false);
        final InteractionResult result;
        try {
            result = player.getMainHandItem().getItem().useOn(
                    new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        } finally {
            player.setShiftKeyDown(false);
        }
        helper.assertTrue(result.consumesAction(), "BoomboxItem.useOn did not accept placement");
        return absoluteSupport.above();
    }

    private static ServerPlayer networkedPlayer(GameTestHelper helper) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        return player;
    }

    private static void cleanupPlayer(GameTestHelper helper, ServerPlayer player, long... ids) {
        try {
            for (long id : ids) {
                BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), id);
            }
        } finally {
            try {
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            } finally {
                player.discard();
            }
        }
    }

    /** Keep playback assertions server-side while isolating mock-player channel negotiation. */
    private static void withCapturedNetwork(java.util.function.Consumer<CapturingNetwork> body,
            Runnable cleanup) {
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        Throwable bodyFailure = null;
        try {
            body.accept(net);
        } catch (RuntimeException | Error failure) {
            bodyFailure = failure;
            throw failure;
        } finally {
            try {
                cleanup.run();
            } catch (RuntimeException | Error cleanupFailure) {
                if (bodyFailure != null) {
                    bodyFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            } finally {
                Services.swapNetwork(previous);
            }
        }
    }
}
