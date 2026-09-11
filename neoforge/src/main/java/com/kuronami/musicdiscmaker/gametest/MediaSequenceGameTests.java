package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.MediaSequence;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver;
import com.kuronami.musicdiscmaker.component.PlaylistTracks;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
*///?}
public final class MediaSequenceGameTests {
    private static final String TEMPLATE = "empty8x3x8";
    private static final String URL_A = "https://example.invalid/a";

    private MediaSequenceGameTests() {
    }

    //? if <1.21.2 {
    /*@GameTest(template = TEMPLATE)
    *///?}
    public static void rawAndAlbumResolveToDistinctKinds(GameTestHelper helper) {
        final ItemStack raw = disc(track(URL_A, "A", 120_000L, false));
        final ItemStack album = album(raw,
                disc(track("https://example.invalid/b", "B", 120_000L, false)),
                disc(track(URL_A, "A again", 120_000L, false)));

        helper.assertTrue(MediaSequenceResolver.resolve(raw).kind() == MediaSequenceResolver.Kind.RAW_SINGLE,
                "raw custom disc was not classified as RAW_SINGLE");
        helper.assertTrue(MediaSequenceResolver.resolve(album).kind() == MediaSequenceResolver.Kind.MDM_ALBUM,
                "MDM Album was not classified as MDM_ALBUM");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = TEMPLATE)
    *///?}
    public static void albumKeepsDiscTrackPositionsAndDuplicateUrls(GameTestHelper helper) {
        final ItemStack album = album(
                disc(track(URL_A, "A", 120_000L, false)),
                disc(track("https://example.invalid/b", "B", 120_000L, false)),
                disc(track(URL_A, "A again", 120_000L, false)));

        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(album);
        helper.assertTrue(resolved.positions().positions().equals(List.of(
                        new MediaSequence.Position(0, 0),
                        new MediaSequence.Position(1, 0),
                        new MediaSequence.Position(2, 0))),
                "Album did not resolve to 0/0, 1/0, 2/0");
        helper.assertTrue(resolved.tracks().size() == 3, "duplicate URL position was collapsed");
        helper.assertTrue(URL_A.equals(resolved.tracks().get(0).track().url())
                        && URL_A.equals(resolved.tracks().get(2).track().url()),
                "same URL did not survive at both Album positions");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = TEMPLATE)
    *///?}
    public static void invalidDiscsAreSkippedWithoutRenumberingAlbumPositions(GameTestHelper helper) {
        final ItemStack missingTrack = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        final ItemStack legacyComponentWithoutCanonicalTrack = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        legacyComponentWithoutCanonicalTrack.set(ModDataComponents.PLAYLIST_TRACKS.get(),
                new PlaylistTracks(List.of(track("https://example.invalid/orphan", "orphan", 120_000L, false))));
        final ItemStack album = album(new ItemStack(Items.STONE), missingTrack,
                disc(track("https://example.invalid/valid", "valid", 120_000L, false)));

        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(album);
        helper.assertTrue(resolved.tracks().size() == 1, "invalid Album discs became playable tracks");
        helper.assertTrue(resolved.first().orElseThrow().position().equals(new MediaSequence.Position(2, 0)),
                "skipping invalid discs renumbered the surviving Album position");
        helper.assertTrue(MediaSequenceResolver.resolve(new ItemStack(Items.STONE)).kind()
                        == MediaSequenceResolver.Kind.INVALID,
                "non-media item was accepted by the resolver");
        helper.assertTrue(MediaSequenceResolver.resolve(legacyComponentWithoutCanonicalTrack).kind()
                        == MediaSequenceResolver.Kind.INVALID,
                "旧componentだけの試作盤を再生機能として扱った");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = TEMPLATE)
    *///?}
    public static void liveAndUnknownOnlyBlockAutomaticAdvance(GameTestHelper helper) {
        final ItemStack album = album(
                disc(track("https://example.invalid/live", "LIVE", 0L, true)),
                disc(track("https://example.invalid/unknown", "unknown", 0L, false)),
                disc(track("https://example.invalid/end", "end", 120_000L, false)));
        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(album);
        final MediaSequence.Position live = new MediaSequence.Position(0, 0);
        final MediaSequence.Position unknown = new MediaSequence.Position(1, 0);

        helper.assertTrue(resolved.next(live, false, false).isEmpty(),
                "LIVE track advanced automatically");
        helper.assertTrue(resolved.next(live, false, true).orElseThrow().position().equals(unknown),
                "manual next could not leave LIVE track");
        helper.assertTrue(resolved.next(unknown, false, false).isEmpty(),
                "unknown-duration track advanced automatically");
        helper.assertTrue(resolved.next(unknown, false, true).orElseThrow().position()
                        .equals(new MediaSequence.Position(2, 0)),
                "manual next could not leave unknown-duration track");
        helper.succeed();
    }

    private static CustomTrackData track(String url, String title, long durationMs, boolean radio) {
        return new CustomTrackData(url, title, "GameTest", durationMs, "", radio);
    }

    private static ItemStack disc(CustomTrackData track) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), track);
        return disc;
    }

    private static ItemStack album(ItemStack... discs) {
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(discs)));
        return album;
    }
}
