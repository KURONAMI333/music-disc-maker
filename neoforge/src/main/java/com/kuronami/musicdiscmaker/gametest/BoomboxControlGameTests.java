package com.kuronami.musicdiscmaker.gametest;

import java.util.List;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.BoomboxPlaybackState;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStatePayload;
import com.kuronami.musicdiscmaker.network.ControlBoomboxPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//? if <1.21.2 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
*///?}
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import io.netty.buffer.Unpooled;

//? if <1.21.2 {
/*@GameTestHolder("music_disc_maker")
*///?}
public final class BoomboxControlGameTests {
    private static ItemStack box() {
        final var first = new CustomTrackData("https://example.invalid/same", "First", "test", 120_000L, "", false);
        final var second = new CustomTrackData(first.url(), "Second", "test", 120_000L, "", false);
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), first);

        final ItemStack other = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        other.set(ModDataComponents.CUSTOM_TRACK.get(), second);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(disc, other)));
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get());
        box.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(album, BoomboxContents.mintId()));
        return box;
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void wireRetainsLongGenerationAndAtomicState(GameTestHelper helper) {
        final long generation = (1L << 49) + 65537L;
        final var state = new BoomboxPlaybackState(new PlaybackCursor(255, 255,
                PlaybackCursor.State.PAUSED, generation), 7_200_123L, true, true, 37);
        final var status = new BoomboxStatePayload(42, state);
        final var control = new ControlBoomboxPayload(42, generation, ControlBoomboxPayload.SEEK, 7_200_123L);
        final var track = new CustomTrackData("https://example.invalid/test", "test", "test", 9_000_000L, "", false);
        final var play = BoomboxPlayPayload.placed(73L, new BlockPos(2, 3, 4), track, 7_200_123L, generation, 37);
        final FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            status.write(wire); control.write(wire); play.write(wire);
            helper.assertTrue(status.equals(BoomboxStatePayload.read(wire)), "state lost 64-bit fields or flags");
            helper.assertTrue(control.equals(ControlBoomboxPayload.read(wire)), "control lost menu/generation/value");
            helper.assertTrue(play.equals(BoomboxPlayPayload.read(wire)), "audio lost epoch, offset or volume");
            helper.assertTrue(!wire.isReadable(), "wire left unread bytes");
        } finally { wire.release(); }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void controlsRequireCurrentMenuSourceAndGeneration(GameTestHelper helper) {
        final var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "boombox-control"));
        final BlockPos playerPos = helper.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(playerPos.getX() + 0.5, playerPos.getY() + 0.5, playerPos.getZ() + 0.5);
        final ItemStack box = box();
        player.setItemInHand(InteractionHand.OFF_HAND, box);
        final var menu = new BoomboxMenu(42, player.getInventory(), BoomboxSource.held(InteractionHand.OFF_HAND));
        final long initial = BoomboxPlayback.stateOf(box).cursor().generation();
        try {
            final var start = new ControlBoomboxPayload(42, initial, ControlBoomboxPayload.PLAY, 0);
            ModNetwork.handleControlBoombox(start, player);
            helper.assertTrue(BoomboxPlayback.stateOf(box).cursor().generation() == initial, "closed menu started playback");
            player.containerMenu = menu;
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(43, initial, ControlBoomboxPayload.PLAY, 0), player);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(42, initial + 1, ControlBoomboxPayload.PLAY, 0), player);
            helper.assertTrue(BoomboxPlayback.stateOf(box).cursor().generation() == initial, "wrong menu or stale generation accepted");
            ModNetwork.handleControlBoombox(start, player);
            var state = BoomboxPlayback.stateOf(box);
            helper.assertTrue(state.cursor().state() == PlaybackCursor.State.PLAYING, "valid PLAY did not start");
            ModNetwork.handleControlBoombox(start, player);
            helper.assertTrue(BoomboxPlayback.stateOf(box).cursor().generation() == state.cursor().generation(), "old PLAY restarted twice");
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(42, state.cursor().generation(), ControlBoomboxPayload.PAUSE, 0), player);
            state = BoomboxPlayback.stateOf(box);
            helper.assertTrue(state.cursor().state() == PlaybackCursor.State.PAUSED, "PAUSE failed");
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(42, state.cursor().generation(), ControlBoomboxPayload.SEEK, 5_000L), player);
            state = BoomboxPlayback.stateOf(box);
            helper.assertTrue(state.elapsedMs() == 5_000L, "paused SEEK lost offset");
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(42, state.cursor().generation(), ControlBoomboxPayload.PLAY, 0), player);
            state = BoomboxPlayback.stateOf(box);
            helper.assertTrue(state.cursor().state() == PlaybackCursor.State.PLAYING
                    && state.elapsedMs() >= 5_000L && state.elapsedMs() < 6_500L, "PLAY did not resume paused offset");
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(42, state.cursor().generation(), ControlBoomboxPayload.VOLUME, 37), player);
            state = BoomboxPlayback.stateOf(box);
            helper.assertTrue(state.volumePercent() == 37, "volume dispatch failed");
            final long beforeInvalid = state.cursor().generation();
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(42, beforeInvalid, ControlBoomboxPayload.VOLUME, 101), player);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(42, beforeInvalid, ControlBoomboxPayload.SEEK, -1), player);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(42, beforeInvalid, ControlBoomboxPayload.REPEAT, 2), player);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(42, beforeInvalid, 999, 0), player);
            helper.assertTrue(BoomboxPlayback.stateOf(box).cursor().generation() == beforeInvalid, "invalid action or value mutated state");
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(42, beforeInvalid, ControlBoomboxPayload.NEXT, 0), player);
            state = BoomboxPlayback.stateOf(box);
            helper.assertTrue(state.cursor().discIndex() == 1 && state.cursor().generation() > beforeInvalid,
                    "same URL second position was not selected");
            final ItemStack replacement = box.copy();
            player.setItemInHand(InteractionHand.OFF_HAND, replacement);
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(42, state.cursor().generation(), ControlBoomboxPayload.VOLUME, 0), player);
            helper.assertTrue(BoomboxPlayback.stateOf(box).volumePercent() == 37
                    && BoomboxPlayback.stateOf(replacement).volumePercent() == 37, "stale source mutated an old or replacement box");
        } finally {
            BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), box.get(ModDataComponents.BOOMBOX_CONTENTS.get()).id());
            player.containerMenu = player.inventoryMenu;
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
        helper.succeed();
    }
    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void legacyContentsDefaultAndExtendedStateRoundTrip(GameTestHelper helper) {
        final BoomboxContents initial = box().get(ModDataComponents.BOOMBOX_CONTENTS.get());
        final var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,
                helper.getLevel().registryAccess());
        final var old = new com.google.gson.JsonObject();
        old.add("disc", ItemStack.OPTIONAL_CODEC.encodeStart(ops, initial.disc()).getOrThrow());
        old.addProperty("id", initial.id());
        final BoomboxContents restoredOld = BoomboxContents.CODEC.parse(ops, old).getOrThrow();
        helper.assertTrue(restoredOld.id() == initial.id() && ItemStack.matches(restoredOld.disc(), initial.disc()),
                "old contents lost machine identity or medium");
        helper.assertTrue(restoredOld.cursor().equals(PlaybackCursor.initial()) && restoredOld.pausedOffsetMs() == 0
                && !restoredOld.repeat() && !restoredOld.shuffle() && restoredOld.volumePercent() == 100,
                "old contents did not receive compatible playback defaults");
        final BoomboxContents extended = restoredOld.withPlayback(
                new PlaybackCursor(0, 1, PlaybackCursor.State.PAUSED, (1L << 49) + 7), 7_123L)
                .withRepeat(true).withShuffle(true, Long.MIN_VALUE + 73, 0, 1).withVolumePercent(37);
        final var encoded = BoomboxContents.CODEC.encodeStart(ops, extended).getOrThrow();
        helper.assertTrue(extended.equals(BoomboxContents.CODEC.parse(ops, encoded).getOrThrow()),
                "persistent codec lost playback settings");
        final var wire = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            BoomboxContents.STREAM_CODEC.encode(wire, extended);
            helper.assertTrue(extended.equals(BoomboxContents.STREAM_CODEC.decode(wire)) && !wire.isReadable(),
                    "component wire lost cursor/offset/shuffle/volume");
        } finally { wire.release(); }
        helper.succeed();
    }
}
