package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver;
import com.kuronami.musicdiscmaker.component.PlayableDiscs;
import com.kuronami.musicdiscmaker.component.VanillaTrackData;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 1.20.1 の「どの盤でも」(2026-10-09 本人裁定)。1.20.1 は盤を RecordItem で判定し、曲は RecordItem の音と
 * 長さから引く (1.21 以降の jukebox_playable と別の道なので別に固定する)。
 */
@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
public final class PlayableDiscsLegacyGameTests {

    private PlayableDiscsLegacyGameTests() {
    }

    @GameTest(template = "empty8x3x8")
    public static void vanillaRecordsAreDiscsAndAlbumTracks(GameTestHelper helper) {
        final ItemStack cat = new ItemStack(Items.MUSIC_DISC_CAT);
        final ItemStack custom = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        CustomMusicDiscItem.setTrack(custom, new CustomTrackData("https://example.invalid/legacy-any-disc",
                "Legacy", "GameTest", 100L, "", false));
        helper.assertTrue(PlayableDiscs.isDisc(cat) && PlayableDiscs.isDisc(custom),
                "a vanilla record or a custom disc was not accepted as a disc");
        helper.assertTrue(!PlayableDiscs.isDisc(new ItemStack(ModItems.ALBUM.get()))
                        && !PlayableDiscs.isDisc(new ItemStack(Items.STONE)),
                "an album or a non-disc item was accepted as a disc");

        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(cat, custom)));
        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(album);
        helper.assertTrue(resolved.tracks().size() == 2 && resolved.tracks().get(0).isVanilla()
                        && !resolved.tracks().get(1).isVanilla(),
                "the album did not count its vanilla record as a track: " + resolved.tracks());

        final VanillaTrackData vanilla = PlayableDiscs.vanillaTrackOf(cat, helper.getLevel());
        helper.assertTrue(vanilla != null && vanilla.soundEventId().equals("minecraft:music_disc.cat")
                        && vanilla.durationMs() > 0L,
                "the cat record did not resolve to its sound and length: " + vanilla);
        helper.assertTrue(PlayableDiscs.descriptionOfSound(vanilla.soundEventId(), helper.getLevel()) != null,
                "the cat record's name could not be found from its sound");
        helper.succeed();
    }
}
