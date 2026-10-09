package com.kuronami.musicdiscmaker.gametest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver;
import com.kuronami.musicdiscmaker.component.PlayableDiscs;
import com.kuronami.musicdiscmaker.component.VanillaTrackData;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if <1.21.11 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
*///?}

/**
 * MDM の再生機器はどの盤でも鳴らす (2026-10-09 本人裁定「MDMのディスクとか、本家のとか、他のMODのとか
 * 関係なく、なんでもいけるようなプラットフォーム」)。アルバムとブームボックスがバニラの盤を受け付け、
 * アルバムの中のバニラの盤を 1 曲として数え、ブームボックスがバニラの曲として鳴らして尺で次へ送ること。
 */
public final class PlayableDiscsGameTests {

    private PlayableDiscsGameTests() {
    }

    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8")
    *///?}
    public static void albumsCountVanillaDiscsAsTracks(GameTestHelper helper) {
        final ItemStack cat = new ItemStack(Items.MUSIC_DISC_CAT);
        final ItemStack custom = custom("albums-count", 100L);
        helper.assertTrue(PlayableDiscs.isDisc(cat) && PlayableDiscs.isDisc(custom),
                "a vanilla disc or a custom disc was not accepted as a disc");
        helper.assertTrue(!PlayableDiscs.isDisc(new ItemStack(ModItems.ALBUM.get()))
                        && !PlayableDiscs.isDisc(new ItemStack(ModItems.BOOMBOX.get()))
                        && !PlayableDiscs.isDisc(new ItemStack(Items.STONE)),
                "an album, a boombox or a non-disc item was accepted as a disc");

        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(cat, custom)));
        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(album);
        helper.assertTrue(resolved.tracks().size() == 2 && resolved.tracks().get(0).isVanilla()
                        && !resolved.tracks().get(1).isVanilla(),
                "the album did not count its vanilla disc as a track: " + resolved.tracks());
        helper.assertTrue(MediaSequenceResolver.resolve(cat).kind() == MediaSequenceResolver.Kind.RAW_SINGLE,
                "a single vanilla disc was not playable media");

        final VanillaTrackData vanilla = PlayableDiscs.vanillaTrackOf(cat, helper.getLevel());
        helper.assertTrue(vanilla != null && vanilla.soundEventId().equals("minecraft:music_disc.cat")
                        && vanilla.durationMs() > 0L,
                "the cat disc did not resolve to its sound and length: " + vanilla);
        helper.assertTrue(PlayableDiscs.vanillaTrackOf(custom, helper.getLevel()) == null,
                "a custom disc was treated as a vanilla disc");
        helper.assertTrue(PlayableDiscs.descriptionOfSound(vanilla.soundEventId(), helper.getLevel()) != null,
                "the cat disc's song name could not be found from its sound");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void boomboxPlaysVanillaDiscsAndAdvancesAfterTheirLength(GameTestHelper helper) {
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(new ItemStack(Items.MUSIC_DISC_CAT),
                custom("after-vanilla", 120_000L))));
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get());
        box.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(album, BoomboxContents.mintId()));
        final long id = box.get(ModDataComponents.BOOMBOX_CONTENTS.get()).id();
        final var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "boombox-vanilla"));
        final BlockPos playerPos = helper.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(playerPos.getX() + 0.5, playerPos.getY() + 0.5, playerPos.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, box);
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            helper.assertTrue(BoomboxPlayback.startCarried(player, box), "the boombox did not start a vanilla disc");
            final BoomboxPlayPayload started = lastPlay(net);
            helper.assertTrue(started != null && started.isVanilla()
                            && started.vanilla().soundEventId().equals("minecraft:music_disc.cat")
                            && started.track().isEmpty(),
                    "the boombox did not send the vanilla disc as a vanilla sound: " + started);
            final long length = started.vanilla().durationMs();
            final long now = System.currentTimeMillis();

            BoomboxPlayback.scan(List.of(player), now + length);
            helper.assertTrue(BoomboxPlayback.stateOf(box).cursor().discIndex() == 0,
                    "the boombox left the vanilla disc before its end grace");
            net.clear();
            BoomboxPlayback.scan(List.of(player), now + length + BoomboxPlayback.VANILLA_TAIL_GRACE_MS + 200L);
            helper.assertTrue(BoomboxPlayback.stateOf(box).cursor().discIndex() == 1,
                    "the boombox did not move on after the vanilla disc ended");
            final BoomboxPlayPayload next = lastPlay(net);
            helper.assertTrue(next != null && !next.isVanilla()
                            && next.track().url().equals("https://example.invalid/after-vanilla"),
                    "the next track after the vanilla disc was not the custom disc: " + next);
            helper.succeed();
        } finally {
            Services.swapNetwork(previous);
            BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), id);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void movingGoldenJukeboxAlbumsAdvancePastVanillaDiscs(GameTestHelper helper) {
        final var level = helper.getLevel();
        final BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlock(pos, com.kuronami.musicdiscmaker.register.ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState(), 3);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(new ItemStack(Items.MUSIC_DISC_CAT),
                custom("after-captured-vanilla", 120_000L))));
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            final var jukebox = (com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity) level.getBlockEntity(pos);
            jukebox.setItem(com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity.SLOT_DISC, album);
            helper.assertTrue(jukebox.playbackCursor().discIndex() == 0 && !jukebox.isStopped(),
                    "the album did not start on its vanilla disc");
            final long catLength = PlayableDiscs.vanillaTrackOf(new ItemStack(Items.MUSIC_DISC_CAT), level).durationMs();
            // Create の動く構造物は、載せた金のジュークボックスの保存データだけを進める。
            final net.minecraft.nbt.CompoundTag captured = jukebox.saveCustomOnly(level.registryAccess());
            com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback.advance(level, pos, captured, catLength + 1L);
            //? if >=1.21.11 {
            final int disc = captured.getIntOr("albumTrack", -1);
            final String state = captured.getStringOr("playbackState", "");
            //?} else {
            /*final int disc = captured.getInt("albumTrack");
            final String state = captured.getString("playbackState");
            *///?}
            helper.assertTrue(disc == 1 && state.equals("PLAYING"),
                    "a moving golden jukebox did not move from the vanilla disc to the next album track: disc="
                            + disc + " state=" + state);
            level.removeBlock(pos, false);
            helper.succeed();
        } finally {
            Services.swapNetwork(previous);
        }
    }

    private static BoomboxPlayPayload lastPlay(CapturingNetwork net) {
        BoomboxPlayPayload last = null;
        for (final var sent : net.of(BoomboxPlayPayload.class)) {
            last = (BoomboxPlayPayload) sent.payload();
        }
        return last;
    }

    private static ItemStack custom(String name, long durationMs) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData("https://example.invalid/" + name,
                name, "GameTest", durationMs, "", false));
        return disc;
    }
}
