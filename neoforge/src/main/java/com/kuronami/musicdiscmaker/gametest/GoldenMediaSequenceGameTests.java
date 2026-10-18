package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
//? if <26.1 {
/*import net.minecraft.world.item.EitherHolder;
*///?}
//? if <1.21.11 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
*///?}
public final class GoldenMediaSequenceGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);
    private static final long DURATION_MS = 120_000L;

    private GoldenMediaSequenceGameTests() {
    }

    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void manualTransportCrossesAlbumDiscs(GameTestHelper helper) {
        final GoldenJukeboxBlockEntity be = jukebox(helper);
        try {
        final ItemStack album = album(helper,
                disc(helper, "https://example.invalid/a", "A", DURATION_MS, false),
                disc(helper, "https://example.invalid/b1", "B1", DURATION_MS, false),
                disc(helper, "https://example.invalid/b2", "B2", DURATION_MS, false),
                disc(helper, "https://example.invalid/a", "A again", DURATION_MS, false));
        helper.assertTrue(GoldenJukeboxBlockEntity.isPlayableInSlot(album),
                "golden jukebox slot rejected a playable MDM Album");
        be.setItem(0, album);

        assertPosition(helper, be, 0, 0, "Album start");
        be.seekTo(DURATION_MS);
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        assertPosition(helper, be, 1, 0, "automatic next to Album disc B1");
        be.nextTrack();
        assertPosition(helper, be, 2, 0, "next to Album disc B2");
        be.nextTrack();
        assertPosition(helper, be, 3, 0, "next to duplicate URL A");
        be.previousTrack();
        assertPosition(helper, be, 2, 0, "previous across disc boundary");
        helper.succeed();
        } finally {
            be.clearContent();
        }
    }

    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void liveManualNextRepeatTailAndPausedPreviousAreDistinct(GameTestHelper helper) {
        final GoldenJukeboxBlockEntity be = jukebox(helper);
        try {
        be.setItem(0, album(helper,
                disc(helper, "https://example.invalid/live", "LIVE", 0L, true),
                disc(helper, "https://example.invalid/middle", "middle", DURATION_MS, false),
                disc(helper, "https://example.invalid/tail", "tail", DURATION_MS, false)));

        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        assertPosition(helper, be, 0, 0, "LIVE must not auto-advance");
        be.nextTrack();
        assertPosition(helper, be, 1, 0, "manual next leaves LIVE");

        be.setPaused(true);
        be.previousTrack();
        assertPosition(helper, be, 0, 0, "paused previous selects LIVE");
        helper.assertTrue(be.playbackCursor().state() == PlaybackCursor.State.PAUSED,
                "previous while paused resumed or stopped playback");
        be.previousTrack();
        assertPosition(helper, be, 2, 0, "manual previous wraps to tail without repeat");
        be.nextTrack();
        assertPosition(helper, be, 0, 0, "manual next reverses the wrap without repeat");
        helper.assertTrue(be.playbackCursor().state() == PlaybackCursor.State.PAUSED,
                "previous at head changed paused state");

        be.nextTrack();
        be.nextTrack();
        assertPosition(helper, be, 2, 0, "paused selection reached tail");
        be.setRepeat(true);
        be.nextTrack();
        assertPosition(helper, be, 0, 0, "repeat wraps whole Album");
        helper.assertTrue(be.playbackCursor().state() == PlaybackCursor.State.PAUSED,
                "repeat selection while paused resumed playback");

        be.setRepeat(false);
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        assertPosition(helper, be, 0, 0, "manual next wraps selection to first");
        helper.assertTrue(be.isPaused(), "manual wrap changed paused state");
        helper.succeed();
        } finally {
            be.clearContent();
        }
    }

    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void albumDiscAndTrackPositionSurviveReload(GameTestHelper helper) {
        GoldenJukeboxBlockEntity be = jukebox(helper);
        try {
        be.setItem(0, album(helper,
                disc(helper, "https://example.invalid/a", "A", DURATION_MS, false),
                disc(helper, "https://example.invalid/b1", "B1", DURATION_MS, false),
                disc(helper, "https://example.invalid/b2", "B2", DURATION_MS, false)));
        be.nextTrack();
        be.nextTrack();
        be.seekTo(30_000L);
        be.setPaused(true);
        final long offset = be.currentElapsedMs();

        be = reload(helper, be);
        assertPosition(helper, be, 2, 0, "reload");
        helper.assertTrue(be.isPaused() && be.currentElapsedMs() == offset,
                "reload lost paused state or current-track offset");
        helper.assertTrue("B2".equals(be.currentTrack().title()), "reload resolved a different track");
        helper.succeed();
        } finally {
            be.clearContent();
        }
    }

    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void shufflePreservesPositionOffsetAndFixedTransportAfterReload(GameTestHelper helper) {
        GoldenJukeboxBlockEntity be = jukebox(helper);
        try {
        be.setItem(0, album(helper,
                disc(helper, "https://example.invalid/a", "A", DURATION_MS, false),
                disc(helper, "https://example.invalid/b", "B", DURATION_MS, false),
                disc(helper, "https://example.invalid/c", "C", DURATION_MS, false),
                disc(helper, "https://example.invalid/d", "D", DURATION_MS, false)));
        be.nextTrack();
        be.seekTo(30_000L);
        be.setPaused(true);
        final long offset = be.currentElapsedMs();
        final long generation = be.playbackCursor().generation();

        be.setShuffle(true);
        assertPosition(helper, be, 1, 0, "shuffle anchor");
        helper.assertTrue(be.isShuffle() && be.isPaused() && be.currentElapsedMs() == offset,
                "shuffle changed the selected track, paused state, or offset");
        helper.assertTrue(be.playbackCursor().generation() > generation,
                "shuffle did not invalidate the previous playback generation");

        be = reload(helper, be);
        assertPosition(helper, be, 1, 0, "shuffle reload");
        helper.assertTrue(be.isShuffle() && be.isPaused() && be.currentElapsedMs() == offset,
                "shuffle state, anchor, or paused offset did not survive reload");

        be.nextTrack();
        helper.assertTrue(be.playbackCursor().discIndex() != 1,
                "shuffle next did not leave the anchor");
        be.previousTrack();
        assertPosition(helper, be, 1, 0, "shuffle previous returns to anchor");
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        assertPosition(helper, be, 1, 0, "manual shuffle wrap returns to anchor");
        helper.assertTrue(be.isPaused(), "manual shuffle wrap changed paused state");
        be.setPaused(false);
        assertPosition(helper, be, 1, 0, "shuffle restart begins at anchor");
        helper.assertTrue(!be.isStopped(), "shuffle restart did not start playback");

        be.setRepeat(true);
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        assertPosition(helper, be, 1, 0, "shuffle repeat wraps fixed order to anchor");
        helper.succeed();
        } finally {
            be.clearContent();
        }
    }

    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void shuffleMissingSavedSeedAndAnchorFallsBackSafely(GameTestHelper helper) {
        GoldenJukeboxBlockEntity be = jukebox(helper);
        try {
        be.setItem(0, album(helper,
                disc(helper, "https://example.invalid/a", "A", DURATION_MS, false),
                disc(helper, "https://example.invalid/b", "B", DURATION_MS, false)));
        be.setShuffle(true);
        final CompoundTag saved = be.saveWithFullMetadata(helper.getLevel().registryAccess());
        saved.remove("shuffleSeed");
        saved.remove("shuffleAnchorDisc");
        saved.remove("shuffleAnchorTrack");
        be = reload(helper, be, saved);
        helper.assertTrue(be.isShuffle(), "shuffle flag did not survive a legacy shuffle save");
        be.nextTrack();
        helper.succeed();
        } finally {
            be.clearContent();
        }
    }

    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void listenerLagHoldsNaturalAlbumAdvance(GameTestHelper helper) {
        final GoldenJukeboxBlockEntity be = jukebox(helper);
        try {
        be.setItem(0, album(helper,
                disc(helper, "https://example.invalid/lag-a", "A", DURATION_MS, false),
                disc(helper, "https://example.invalid/lag-b", "B", DURATION_MS, false)));
        be.seekTo(DURATION_MS);
        final var playing = be.playbackIdentity();
        be.noteListenerLag(playing, 60_000L);
        helper.assertTrue(be.naturalAdvanceGraceMs() == GoldenJukeboxBlockEntity.MAX_LISTENER_LAG_MS,
                "listener lag was not capped: " + be.naturalAdvanceGraceMs());
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        assertPosition(helper, be, 0, 0, "Album moved on before the slowest listener finished");
        be.setShuffle(true);
        be.noteListenerLag(playing, 2_000L);
        helper.assertTrue(be.naturalAdvanceGraceMs() == GoldenJukeboxBlockEntity.MAX_LISTENER_LAG_MS,
                "turning shuffle on mid-song dropped the wait for the song that is still playing");
        be.noteListenerLag(new com.kuronami.musicdiscmaker.network.PlaybackSourceStamp(
                playing.sourceId(), playing.generation() + 7L), 1_000L);
        helper.assertTrue(be.naturalAdvanceGraceMs() == GoldenJukeboxBlockEntity.MAX_LISTENER_LAG_MS,
                "a report for another playback changed the wait");
        be.seekTo(DURATION_MS);
        helper.assertTrue(be.naturalAdvanceGraceMs() == 0L, "the wait carried over to a new playback");
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        assertPosition(helper, be, 1, 0, "Album did not move on without a listener report");
        helper.succeed();
        } finally {
            be.clearContent();
        }
    }

    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void listenerLagHoldsSingleRepeat(GameTestHelper helper) {
        final GoldenJukeboxBlockEntity be = jukebox(helper);
        try {
        be.setItem(0, disc(helper, "https://example.invalid/lag-single", "Single", DURATION_MS, false));
        be.setRepeat(true);
        be.seekTo(DURATION_MS);
        final var playing = be.playbackIdentity();
        be.noteListenerLag(playing, 3_000L);
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        helper.assertTrue(be.playbackIdentity().equals(playing),
                "repeat restarted before the slowest listener finished");
        be.seekTo(DURATION_MS);
        final var reseeked = be.playbackIdentity();
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        helper.assertTrue(!be.playbackIdentity().equals(reseeked) && be.currentElapsedMs() < 1_000L,
                "repeat did not restart without a listener report");
        helper.succeed();
        } finally {
            be.clearContent();
        }
    }

    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void listenerLagHoldsSingleTerminalStop(GameTestHelper helper) {
        final GoldenJukeboxBlockEntity be = jukebox(helper);
        try {
        final ItemStack single = disc(helper, "https://example.invalid/lag-terminal", "Single", DURATION_MS, false);
        be.setItem(0, single);
        be.seekTo(DURATION_MS);
        final var playing = be.playbackIdentity();
        be.noteListenerLag(playing, 3_000L);
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        helper.assertTrue(!be.isStopped() && be.playbackIdentity().equals(playing),
                "a single disc stopped before the slowest listener heard its end");
        helper.assertTrue(!be.isAutomationExtractionReady()
                        && !be.canTakeItemThroughFace(GoldenJukeboxBlockEntity.SLOT_DISC, be.getItem(0), Direction.DOWN),
                "the hopper below could take the disc before the slowest listener heard its end");
        helper.assertTrue(be.getComparatorOutput() == 15,
                "the comparator dropped before the slowest listener heard the end: " + be.getComparatorOutput());
        be.seekTo(DURATION_MS);
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        helper.assertTrue(be.isStopped() && be.isAutomationExtractionReady(),
                "a single disc did not stop at its end without a listener report");
        helper.assertTrue(be.getComparatorOutput() == 1,
                "the comparator did not show a stopped disc: " + be.getComparatorOutput());
        helper.succeed();
        } finally {
            be.clearContent();
        }
    }

    /**
     * While the slowest listener is still hearing the end of a single disc, nothing tells clients to
     * stop at once; the only stop is the natural-end one after the wait (clients let their audio finish).
     */
    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void listenerLagSendsOnlyNaturalStopForSingleDisc(GameTestHelper helper) {
        final GoldenJukeboxBlockEntity be = jukebox(helper);
        final var captured = new CapturingNetwork();
        final var previous = com.kuronami.musicdiscmaker.platform.Services.swapNetwork(captured);
        try {
        be.setItem(0, disc(helper, "https://example.invalid/lag-stop", "Single", DURATION_MS, false));
        be.seekTo(DURATION_MS);
        be.noteListenerLag(be.playbackIdentity(), 3_000L);
        captured.clear();
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        helper.assertTrue(captured.of(StopDiscPayload.class).isEmpty(),
                "a stop was sent while the slowest listener was still hearing the end");
        // Re-seeking starts a new playback without the report, so the next tick ends the disc naturally.
        be.seekTo(DURATION_MS);
        captured.clear();
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        final var stops = captured.of(StopDiscPayload.class);
        helper.assertTrue(!stops.isEmpty() && stops.stream()
                        .allMatch(sent -> sent.payload() instanceof StopDiscPayload stop && stop.naturalEnd()),
                "the end of a single disc was not sent as a natural end: " + stops.size());
        helper.succeed();
        } finally {
            com.kuronami.musicdiscmaker.platform.Services.swapNetwork(previous);
            be.clearContent();
        }
    }

    /** A real hopper under the jukebox waits for the slowest listener before pulling a finished disc. */
    //? if <1.21.11 {
    /*@GameTest(template = "empty8x3x8", timeoutTicks = 200)
    *///?}
    public static void hopperWaitsForSlowestListener(GameTestHelper helper) {
        final GoldenJukeboxBlockEntity be = jukebox(helper);
        helper.setBlock(POS.below(), Blocks.HOPPER.defaultBlockState());
        be.setItem(0, disc(helper, "https://example.invalid/lag-hopper", "Single", DURATION_MS, false));
        be.seekTo(DURATION_MS);
        be.noteListenerLag(be.playbackIdentity(), GoldenJukeboxBlockEntity.MAX_LISTENER_LAG_MS);
        // A hopper tries every 8 ticks; 40 ticks are far shorter than the 10 s wait even on a slow server.
        helper.runAfterDelay(40, () -> {
            try {
                helper.assertTrue(be.hasDisc() && !be.isStopped(),
                        "the hopper pulled the disc while the slowest listener was still hearing the end");
                helper.succeed();
            } finally {
                be.clearContent();
            }
        });
    }

    private static GoldenJukeboxBlockEntity jukebox(GameTestHelper helper) {
        helper.setBlock(POS, ModBlocks.GOLDEN_JUKEBOX.get());
        //? if >=1.21.2 {
        return GameTestBlockEntities.get(helper, POS, GoldenJukeboxBlockEntity.class);
        //?} else {
        /*return helper.getBlockEntity(POS);
        *///?}
    }

    private static GoldenJukeboxBlockEntity reload(GameTestHelper helper, GoldenJukeboxBlockEntity be) {
        final CompoundTag saved = be.saveWithFullMetadata(helper.getLevel().registryAccess());
        return reload(helper, be, saved);
    }

    private static GoldenJukeboxBlockEntity reload(GameTestHelper helper, GoldenJukeboxBlockEntity be,
            CompoundTag saved) {
        be.onBlockRemoved();
        final GoldenJukeboxBlockEntity restored = (GoldenJukeboxBlockEntity) BlockEntity.loadStatic(
                be.getBlockPos(), be.getBlockState(), saved, helper.getLevel().registryAccess());
        helper.assertTrue(restored != null, "saved golden jukebox could not reload");
        restored.setLevel(helper.getLevel());
        helper.getLevel().setBlockEntity(restored);
        return restored;
    }

    private static ItemStack album(GameTestHelper helper, ItemStack... discs) {
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(discs)));
        return album;
    }

    private static ItemStack disc(GameTestHelper helper, String url, String title, long durationMs, boolean radio) {
        return disc(helper, track(url, title, durationMs, radio));
    }

    private static ItemStack disc(GameTestHelper helper, CustomTrackData track) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), track);
        final var song = helper.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                .getOrThrow(SilentSongs.pick(track.durationMs()));
        //? if >=26.1 {
        disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(song));
        //?} elif >=1.21.11 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song)));
        *///?}
        //? if <1.21.11 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song), false));
        *///?}
        return disc;
    }

    private static CustomTrackData track(String url, String title, long durationMs, boolean radio) {
        return new CustomTrackData(url, title, "GameTest", durationMs, "", radio);
    }

    private static void assertPosition(GameTestHelper helper, GoldenJukeboxBlockEntity be,
            int discIndex, int trackIndex, String step) {
        helper.assertTrue(be.playbackCursor().discIndex() == discIndex
                        && be.playbackCursor().trackIndex() == trackIndex,
                step + " expected " + discIndex + "/" + trackIndex + " but was "
                        + be.playbackCursor().discIndex() + "/" + be.playbackCursor().trackIndex());
    }
}
