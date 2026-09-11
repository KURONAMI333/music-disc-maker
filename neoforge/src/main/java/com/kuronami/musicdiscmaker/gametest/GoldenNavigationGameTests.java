package com.kuronami.musicdiscmaker.gametest;

import java.util.List;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.NavigateJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ShuffleJukeboxPayload;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlock;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
//? if <26.1 {
/*import net.minecraft.world.item.EitherHolder;
*///?}
//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class GoldenNavigationGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);

    private static GoldenJukeboxBlockEntity jukebox(GameTestHelper helper) {
        helper.setBlock(POS, ModBlocks.GOLDEN_JUKEBOX.get());
        //? if >=1.21.2 {
        return helper.getBlockEntity(POS, GoldenJukeboxBlockEntity.class);
        //?} else {
        /*return helper.getBlockEntity(POS);
        *///?}
    }

    private static ItemStack album(GameTestHelper helper) {
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(
                disc(helper, new CustomTrackData("https://example.invalid/a", "A", "test", 120_000L, "", false)),
                disc(helper, new CustomTrackData("https://example.invalid/b", "B", "test", 120_000L, "", false)))));
        return album;
    }

    private static ItemStack disc(GameTestHelper helper, CustomTrackData track) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), track);
        final var song = helper.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                .getOrThrow(SilentSongs.pick(track.durationMs()));
        //? if >=26.1 {
        disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(song));
        //?} elif >=1.21.2 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song)));
        *///?}
        //? if <1.21.2 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song), false));
        *///?}
        return disc;
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void navigationRequiresOpenMenuAndCurrentGeneration(GameTestHelper helper) {
        final var be = jukebox(helper);
        be.setItem(0, album(helper));
        final var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        final BlockPos pos = be.getBlockPos();
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        final long generation = be.playbackCursor().generation();
        final var request = new NavigateJukeboxPayload(pos, 42, generation, false);
        final FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            request.write(wire);
            helper.assertTrue(request.equals(NavigateJukeboxPayload.read(wire)) && !wire.isReadable(),
                    "navigation wire lost menu, generation or direction");
        } finally {
            wire.release();
        }
        ModNetwork.handleNavigateJukebox(request, player);
        helper.assertTrue(be.playbackCursor().generation() == generation, "closed menu accepted navigation");
        player.containerMenu = new GoldenJukeboxMenu(42, player.getInventory(), be);
        ModNetwork.handleNavigateJukebox(new NavigateJukeboxPayload(pos, 43, generation, false), player);
        ModNetwork.handleNavigateJukebox(new NavigateJukeboxPayload(pos.above(), 42, generation, false), player);
        ModNetwork.handleNavigateJukebox(new NavigateJukeboxPayload(pos, 42, generation - 1, false), player);
        helper.assertTrue(be.playbackCursor().generation() == generation, "wrong target, menu or generation was accepted");
        player.setPos(pos.getX() + 20, pos.getY(), pos.getZ());
        ModNetwork.handleNavigateJukebox(request, player);
        helper.assertTrue(be.playbackCursor().generation() == generation, "distant player advanced playback");
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        ModNetwork.handleNavigateJukebox(request, player);
        helper.assertTrue(be.playbackCursor().discIndex() == 1, "current open menu did not advance");
        final long advanced = be.playbackCursor().generation();
        ModNetwork.handleNavigateJukebox(request, player);
        helper.assertTrue(be.playbackCursor().generation() == advanced, "replayed operation was accepted twice");
        ModNetwork.handleNavigateJukebox(new NavigateJukeboxPayload(pos, 42, advanced, true), player);
        helper.assertTrue(be.playbackCursor().discIndex() == 0, "previous direction did not survive dispatch");
        final long beforeReplace = be.playbackCursor().generation();
        be.setItem(0, album(helper));
        final long replaced = be.playbackCursor().generation();
        ModNetwork.handleNavigateJukebox(new NavigateJukeboxPayload(pos, 42, beforeReplace, false), player);
        helper.assertTrue(be.playbackCursor().generation() == replaced && be.playbackCursor().discIndex() == 0,
                "old operation affected replacement media");
        final var shuffle = new ShuffleJukeboxPayload(pos, 42, replaced, true);
        final FriendlyByteBuf shuffleWire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            shuffle.write(shuffleWire);
            helper.assertTrue(shuffle.equals(ShuffleJukeboxPayload.read(shuffleWire)) && !shuffleWire.isReadable(),
                    "shuffle wire lost menu, generation or enabled state");
        } finally {
            shuffleWire.release();
        }
        ModNetwork.handleShuffleJukebox(new ShuffleJukeboxPayload(pos, 43, replaced, true), player);
        helper.assertTrue(!be.isShuffle(), "wrong menu changed shuffle");
        ModNetwork.handleShuffleJukebox(shuffle, player);
        helper.assertTrue(be.isShuffle() && be.playbackCursor().discIndex() == 0,
                "shuffle dispatch changed the selected track or failed");
        ModNetwork.handleShuffleJukebox(new ShuffleJukeboxPayload(pos, 42, replaced, false), player);
        helper.assertTrue(be.isShuffle(), "stale operation changed shuffle");
        be.clearContent();
        player.containerMenu = player.inventoryMenu;
        helper.succeed();
    }
}


