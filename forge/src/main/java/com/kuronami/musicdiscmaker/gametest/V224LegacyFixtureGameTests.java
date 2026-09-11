package com.kuronami.musicdiscmaker.gametest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Public v2.2.4 output, rather than synthetic v3 data with fields removed. */
@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
public final class V224LegacyFixtureGameTests {
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void publishedDiscNbtSurvivesUpgradeAndResave(GameTestHelper helper) {
        final String fixture = "/data/music_disc_maker/compat/v2.2.4/forge-1.20.1/custom-disc-1.20.1.snbt";
        try (InputStream input = V224LegacyFixtureGameTests.class.getResourceAsStream(fixture)) {
            if (input == null) throw new IllegalStateException("Missing published fixture: " + fixture);
            final CompoundTag saved = TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            final ItemStack original = ItemStack.of(saved);
            helper.assertTrue(original.is(ModItems.CUSTOM_MUSIC_DISC.get()), "Published disc item ID was lost");
            helper.assertTrue(original.getCount() == 1, "Published disc count was lost");
            assertTrack(helper, CustomMusicDiscItem.getTrack(original));
            final ItemStack restored = ItemStack.of(original.save(new CompoundTag()));
            helper.assertTrue(ItemStack.isSameItemSameTags(original, restored), "Disc NBT changed on v3 resave");
            helper.assertTrue(restored.getCount() == 1, "Disc count changed on v3 resave");
            assertTrack(helper, CustomMusicDiscItem.getTrack(restored));
            helper.succeed();
        } catch (Exception error) {
            helper.fail("Published v2.2.4 disc migration failed: " + error);
        }
    }

    private static void assertTrack(GameTestHelper helper, CustomTrackData track) {
        assertTrack(helper, track, true);
    }

    private static void assertTrack(GameTestHelper helper, CustomTrackData track, boolean radio) {
        helper.assertTrue(track != null, "custom_track was lost");
        helper.assertTrue("https://example.invalid/v224-fixture?x=1".equals(track.url()), "URL changed");
        helper.assertTrue("v2.2.4 fixture title".equals(track.title()), "Title changed");
        helper.assertTrue("v2.2.4 fixture author".equals(track.author()), "Author changed");
        helper.assertTrue(track.durationMs() == 187_654L, "Duration changed");
        helper.assertTrue("https://example.invalid/v224-cover.png".equals(track.thumbnailUrl()), "Thumbnail changed");
        helper.assertTrue(track.radio() == radio, "Radio flag changed");
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void publishedGoldenStatesSurviveUpgradeAndResave(GameTestHelper helper) {
        try {
            final String[] states = {"stopped-empty", "playing", "paused"};
            for (int index = 0; index < states.length; index++) {
                final String state = states[index];
                final var pos = new net.minecraft.core.BlockPos(1 + index * 2, 1, 1);
                helper.setBlock(pos, com.kuronami.musicdiscmaker.register.ModBlocks.GOLDEN_JUKEBOX.get());
                final var placed = helper.getBlockEntity(pos);
                final String file = "/data/music_disc_maker/compat/v2.2.4/forge-1.20.1/golden-" + state + "-1.20.1.snbt";
                final CompoundTag old;
                try (InputStream stream = V224LegacyFixtureGameTests.class.getResourceAsStream(file)) {
                    if (stream == null) throw new IllegalStateException("Missing published fixture: " + file);
                    old = TagParser.parseTag(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
                final var golden = (com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity)
                        net.minecraft.world.level.block.entity.BlockEntity.loadStatic(placed.getBlockPos(), placed.getBlockState(), old);
                helper.assertTrue(golden != null, "Published Golden could not be loaded: " + state);
                golden.setLevel(helper.getLevel());
                final var identity = golden.sourceIdentity();
                final var cursor = golden.playbackCursor();
                final var expected = state.equals("playing")
                        ? com.kuronami.musicdiscmaker.component.PlaybackCursor.State.PLAYING
                        : state.equals("paused") ? com.kuronami.musicdiscmaker.component.PlaybackCursor.State.PAUSED
                        : com.kuronami.musicdiscmaker.component.PlaybackCursor.State.STOPPED;
                helper.assertTrue(cursor.state() == expected, "Published playback state changed: " + state);
                final CompoundTag saved = golden.saveWithFullMetadata();
                final var restored = (com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity)
                        net.minecraft.world.level.block.entity.BlockEntity.loadStatic(placed.getBlockPos(), placed.getBlockState(), saved);
                helper.assertTrue(restored != null, "Resaved Golden could not be loaded: " + state);
                helper.assertTrue(identity.equals(restored.peekSourceIdentity()), "Assigned source ID was lost");
                helper.assertTrue(cursor.equals(restored.playbackCursor()), "Cursor changed on v3 resave");
                for (var current : new com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity[] {golden, restored}) {
                    helper.assertTrue(current.getRangeBlocks() == 173 && current.getVolumePercent() == 137,
                            "Range/volume changed: " + state);
                    helper.assertTrue(current.isRepeat() && !current.isDirectional(), "Playback settings changed: " + state);
                    if (state.equals("stopped-empty")) helper.assertFalse(current.hasDisc(), "Empty Golden gained a disc");
                    else {
                        helper.assertTrue(current.getDisc().is(ModItems.CUSTOM_MUSIC_DISC.get()), "Golden disc ID changed");
                        assertTrack(helper, CustomMusicDiscItem.getTrack(current.getDisc()), false);
                    }
                }
                if (state.equals("paused")) helper.assertTrue(saved.getLong("pausedOffset") == 7_650L,
                        "Paused position was lost on v3 resave");
            }
            helper.succeed();
        } catch (Exception error) {
            helper.fail("Published v2.2.4 Golden migration failed: " + error);
        }
    }

    private V224LegacyFixtureGameTests() {}
}
