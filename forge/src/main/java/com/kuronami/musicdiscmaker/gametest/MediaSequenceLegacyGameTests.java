package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.MediaSequence;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Forge 1.20.1 の実 ItemStack/NBT で媒体resolverを固定する。 */
@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
public final class MediaSequenceLegacyGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);
    private static final String URL_A = "https://example.invalid/a";
    private static final long DURATION_MS = 120_000L;

    private MediaSequenceLegacyGameTests() {
    }

    @GameTest(template = "empty8x3x8")
    public static void albumResolvesPositionsAndManualEscapeFromLive(GameTestHelper helper) {
        final ItemStack album = album(
                disc(track(URL_A, "A", 120_000L, false)),
                disc(track("https://example.invalid/live", "LIVE", 0L, true)),
                disc(track("https://example.invalid/b2", "B2", 120_000L, false)),
                disc(track(URL_A, "A again", 120_000L, false)));

        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(album);
        helper.assertTrue(resolved.kind() == MediaSequenceResolver.Kind.MDM_ALBUM,
                "legacy NBT Album was not classified as MDM_ALBUM");
        helper.assertTrue(resolved.positions().positions().equals(List.of(
                        new MediaSequence.Position(0, 0),
                        new MediaSequence.Position(1, 0),
                        new MediaSequence.Position(2, 0),
                        new MediaSequence.Position(3, 0))),
                "legacy Album did not preserve disc/track positions");
        final MediaSequence.Position live = new MediaSequence.Position(1, 0);
        helper.assertTrue(resolved.next(live, false, false).isEmpty(),
                "legacy LIVE track advanced automatically");
        helper.assertTrue(resolved.next(live, false, true).orElseThrow().position()
                        .equals(new MediaSequence.Position(2, 0)),
                "legacy manual next could not leave LIVE track");
        helper.assertTrue(resolved.tracks().size() == 4
                        && URL_A.equals(resolved.tracks().get(0).track().url())
                        && URL_A.equals(resolved.tracks().get(3).track().url()),
                "legacy resolver collapsed duplicate URL positions");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8")
    public static void invalidAndEmptyAlbumStaySafe(GameTestHelper helper) {
        final ItemStack missingTrack = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        final ItemStack album = album(new ItemStack(Items.STONE), missingTrack,
                disc(track("https://example.invalid/valid", "valid", 120_000L, false)));
        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(album);

        helper.assertTrue(resolved.tracks().size() == 1
                        && resolved.first().orElseThrow().position().equals(new MediaSequence.Position(2, 0)),
                "legacy invalid discs were played or surviving position was renumbered");
        helper.assertTrue(MediaSequenceResolver.resolve(new ItemStack(ModItems.ALBUM.get())).tracks().isEmpty(),
                "empty legacy Album produced a track");
        helper.assertTrue(MediaSequenceResolver.resolve(new ItemStack(Items.STONE)).kind()
                        == MediaSequenceResolver.Kind.INVALID,
                "legacy non-media item was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void goldenAlbumTransportCrossesDiscsAndHonorsBoundaries(GameTestHelper helper) {
        final GoldenJukeboxBlockEntity be = jukebox(helper);
        final ItemStack album = album(
                disc(track(URL_A, "A", DURATION_MS, false)),
                disc(track("https://example.invalid/live", "LIVE", 0L, true)),
                disc(track("https://example.invalid/b2", "B2", DURATION_MS, false)),
                disc(track(URL_A, "A again", DURATION_MS, false)));
        helper.assertTrue(be.canPlaceItem(0, album),
                "legacy golden jukebox slot rejected a playable MDM Album");
        be.setItem(0, album);

        assertPosition(helper, be, 0, 0, "Album start");
        be.seekTo(DURATION_MS);
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        assertPosition(helper, be, 1, 0, "automatic next crossed into second Album disc");
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        assertPosition(helper, be, 1, 0, "LIVE must not auto-advance");
        be.nextTrack();
        assertPosition(helper, be, 2, 0, "manual next left LIVE");
        be.nextTrack();
        assertPosition(helper, be, 3, 0, "manual next reached duplicate URL position");
        be.previousTrack();
        assertPosition(helper, be, 2, 0, "previous crossed disc boundary");

        be.setPaused(true);
        be.previousTrack();
        be.previousTrack();
        assertPosition(helper, be, 0, 0, "paused previous reached sequence head");
        be.previousTrack();
        assertPosition(helper, be, 3, 0, "manual previous wraps to tail without repeat");
        be.nextTrack();
        assertPosition(helper, be, 0, 0, "manual next reverses the wrap without repeat");
        helper.assertTrue(be.playbackCursor().state() == PlaybackCursor.State.PAUSED,
                "previous at head changed paused state");

        be.setPaused(false);
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        assertPosition(helper, be, 3, 0, "manual transport reached sequence tail");
        be.setRepeat(true);
        be.nextTrack();
        assertPosition(helper, be, 0, 0, "repeat did not wrap the whole Album");
        be.setRepeat(false);
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        assertPosition(helper, be, 0, 0, "manual wrap did not return to first");
        helper.assertTrue(!be.isStopped() && !be.isPaused(), "manual wrap interrupted playback");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void goldenAlbumDiscAndTrackPositionSurviveLegacyNbtReload(GameTestHelper helper) {
        GoldenJukeboxBlockEntity be = jukebox(helper);
        be.setItem(0, album(
                disc(track(URL_A, "A", DURATION_MS, false)),
                disc(track("https://example.invalid/b1", "B1", DURATION_MS, false)),
                disc(track("https://example.invalid/b2", "B2", DURATION_MS, false))));
        be.nextTrack();
        be.nextTrack();
        be.seekTo(30_000L);
        be.setPaused(true);
        final long offset = be.currentElapsedMs();

        final CompoundTag saved = be.saveWithFullMetadata();
        helper.assertTrue(saved.getInt("albumTrack") == 2 && saved.getInt("playlistTrack") == 0,
                "legacy NBT did not store the Album disc/track cursor");
        be = reload(helper, be, saved);
        assertPosition(helper, be, 2, 0, "legacy reload");
        helper.assertTrue(be.isPaused() && be.currentElapsedMs() == offset,
                "legacy reload lost paused state or current-track offset");
        helper.assertTrue(be.currentTrack() != null && "B2".equals(be.currentTrack().title()),
                "legacy reload resolved a different track");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void goldenShufflePreservesPositionOffsetAndFixedOrderAfterLegacyReload(GameTestHelper helper) {
        GoldenJukeboxBlockEntity be = jukebox(helper);
        try {
        be.setItem(0, album(
                disc(track(URL_A, "A", DURATION_MS, false)),
                disc(track("https://example.invalid/b", "B", DURATION_MS, false)),
                disc(track("https://example.invalid/c", "C", DURATION_MS, false)),
                disc(track("https://example.invalid/d", "D", DURATION_MS, false))));
        be.nextTrack();
        be.seekTo(30_000L);
        be.setPaused(true);
        final long offset = be.currentElapsedMs();
        final long generation = be.playbackCursor().generation();

        be.setShuffle(true);
        assertPosition(helper, be, 1, 0, "legacy shuffle anchor");
        helper.assertTrue(be.isShuffle() && be.isPaused() && be.currentElapsedMs() == offset,
                "legacy shuffle changed the selected track, paused state, or offset");
        helper.assertTrue(be.playbackCursor().generation() > generation,
                "legacy shuffle did not invalidate the previous playback generation");

        final CompoundTag saved = be.saveWithFullMetadata();
        be = reload(helper, be, saved);
        assertPosition(helper, be, 1, 0, "legacy shuffle reload");
        helper.assertTrue(be.isShuffle() && be.isPaused() && be.currentElapsedMs() == offset,
                "legacy shuffle state, anchor, or paused offset did not survive reload");

        be.nextTrack();
        helper.assertTrue(be.playbackCursor().discIndex() != 1,
                "legacy shuffle next did not leave the anchor");
        be.previousTrack();
        assertPosition(helper, be, 1, 0, "legacy shuffle previous returns to anchor");
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        assertPosition(helper, be, 1, 0, "legacy manual shuffle wrap returns to anchor");
        helper.assertTrue(be.isPaused(), "legacy manual shuffle wrap changed paused state");
        be.setPaused(false);
        assertPosition(helper, be, 1, 0, "legacy shuffle restart begins at anchor");
        helper.assertTrue(!be.isStopped(), "legacy shuffle restart did not start playback");

        be.setRepeat(true);
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        be.nextTrack();
        assertPosition(helper, be, 1, 0, "legacy shuffle repeat wraps fixed order to anchor");
        helper.succeed();
        } finally {
            be.clearContent();
        }
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void goldenShuffleMissingLegacySeedAndAnchorFallsBackSafely(GameTestHelper helper) {
        GoldenJukeboxBlockEntity be = jukebox(helper);
        try {
        be.setItem(0, album(
                disc(track(URL_A, "A", DURATION_MS, false)),
                disc(track("https://example.invalid/b", "B", DURATION_MS, false))));
        be.setShuffle(true);
        final CompoundTag saved = be.saveWithFullMetadata();
        saved.remove("shuffleSeed");
        saved.remove("shuffleAnchorDisc");
        saved.remove("shuffleAnchorTrack");
        be = reload(helper, be, saved);
        helper.assertTrue(be.isShuffle(), "legacy shuffle flag did not survive a legacy shuffle save");
        be.nextTrack();
        helper.succeed();
        } finally {
            be.clearContent();
        }
    }

    private static CustomTrackData track(String url, String title, long durationMs, boolean radio) {
        return new CustomTrackData(url, title, "GameTest", durationMs, "", radio);
    }

    private static ItemStack disc(CustomTrackData track) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        CustomMusicDiscItem.setTrack(disc, track);
        return disc;
    }

    private static ItemStack album(ItemStack... discs) {
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(discs)));
        return album;
    }

    private static GoldenJukeboxBlockEntity jukebox(GameTestHelper helper) {
        helper.setBlock(POS, ModBlocks.GOLDEN_JUKEBOX.get());
        return (GoldenJukeboxBlockEntity) helper.getBlockEntity(POS);
    }

    private static GoldenJukeboxBlockEntity reload(GameTestHelper helper, GoldenJukeboxBlockEntity be,
            CompoundTag saved) {
        be.onBlockRemoved();
        final BlockEntity loaded = BlockEntity.loadStatic(be.getBlockPos(), be.getBlockState(), saved);
        helper.assertTrue(loaded instanceof GoldenJukeboxBlockEntity,
                "saved golden jukebox did not reload");
        final GoldenJukeboxBlockEntity restored = (GoldenJukeboxBlockEntity) loaded;
        restored.setLevel(helper.getLevel());
        helper.getLevel().setBlockEntity(restored);
        GoldenJukeboxBlockEntity.serverTick(
                helper.getLevel(), restored.getBlockPos(), restored.getBlockState(), restored);
        return restored;
    }

    private static void assertPosition(GameTestHelper helper, GoldenJukeboxBlockEntity be,
            int discIndex, int trackIndex, String step) {
        helper.assertTrue(be.playbackCursor().discIndex() == discIndex
                        && be.playbackCursor().trackIndex() == trackIndex,
                step + " expected " + discIndex + "/" + trackIndex + " but was "
                        + be.playbackCursor().discIndex() + "/" + be.playbackCursor().trackIndex());
    }
}
